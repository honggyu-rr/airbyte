/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination_async;

import static io.airbyte.integrations.destination_async.BufferManager.QUEUE_FLUSH_THRESHOLD_BYTES;
import static io.airbyte.integrations.destination_async.BufferManager.TOTAL_QUEUES_MAX_SIZE_LIMIT_BYTES;

import io.airbyte.protocol.models.v0.AirbyteMessage;
import io.airbyte.protocol.models.v0.StreamDescriptor;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.mina.util.ConcurrentHashSet;

/**
 * In charge of looking for records in queues and efficiently getting those records uploaded.
 */
@Slf4j
public class UploadWorkers implements AutoCloseable {

  private static final long MAX_TIME_BETWEEN_REC_MINS = 5L;

  private static final long SUPERVISOR_INITIAL_DELAY_SECS = 0L;
  private static final long SUPERVISOR_PERIOD_SECS = 1L;
  private final ScheduledExecutorService supervisorThread = Executors.newScheduledThreadPool(1);
  // note: this queue size is unbounded.
  private final ExecutorService workerPool = Executors.newFixedThreadPool(5);
  private final BufferManager.BufferManagerDequeue bufferManagerDequeue;
  private final StreamDestinationFlusher flusher;
  private final ScheduledExecutorService debugLoop = Executors.newSingleThreadScheduledExecutor();
  private final ConcurrentHashSet<StreamDescriptor> inProgressStreams = new ConcurrentHashSet<>();
  private final ConcurrentHashMap<StreamDescriptor, AtomicInteger> streamToInProgressWorkers = new ConcurrentHashMap<>();

  public UploadWorkers(final BufferManager.BufferManagerDequeue bufferManagerDequeue, final StreamDestinationFlusher flusher1) {
    this.bufferManagerDequeue = bufferManagerDequeue;
    flusher = flusher1;
  }

  public void start() {
    supervisorThread.scheduleAtFixedRate(this::retrieveWork, SUPERVISOR_INITIAL_DELAY_SECS, SUPERVISOR_PERIOD_SECS,
        TimeUnit.SECONDS);
    debugLoop.scheduleAtFixedRate(this::printWorkerInfo, 0L, 15L, TimeUnit.SECONDS);
  }

  private void retrieveWork() {
    // todo (cgardens) - i'm not convinced this makes sense. as we get close to the limit, we should
    // flush more eagerly, but "flush all" is never a particularly useful thing in this world.
    // if the total size is > n, flush all buffers
    if (bufferManagerDequeue.getTotalGlobalQueueSizeBytes() > TOTAL_QUEUES_MAX_SIZE_LIMIT_BYTES) {
      flushAll();
      return;
    }

    // todo (davin) - rethink how to allocate threads once we get to multiple streams.
    final ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) workerPool;
    var allocatableThreads = threadPoolExecutor.getMaximumPoolSize() - threadPoolExecutor.getActiveCount();
    log.info("Allocatable threads: {}", allocatableThreads);

    // todo (cgardens) - build a score to prioritize which queue to flush next. e.g. if a queue is very
    // large, flush it first. if a queue has not been flushed in a while, flush it next.
    // otherwise, if each individual stream has crossed a specific threshold, flush
    for (final Map.Entry<StreamDescriptor, MemoryBoundedLinkedBlockingQueue<AirbyteMessage>> entry : bufferManagerDequeue.getBuffers().entrySet()) {
      final var stream = entry.getKey();
      // if no worker threads left - return
      if (allocatableThreads == 0) {
        break;
      }

      final var pendingSizeByte = (bufferManagerDequeue.getQueueSizeBytes(stream) -
          streamToInProgressWorkers.get(stream).get() * flusher.getOptimalBatchSizeBytes());
      final var exceedSize = pendingSizeByte >= QUEUE_FLUSH_THRESHOLD_BYTES;
      log.info("Stream {} size in queue: {} computed pending size: {} , threshold: {}",
          stream.getName(),
          FileUtils.byteCountToDisplaySize(bufferManagerDequeue.getQueueSizeBytes(stream)),
          FileUtils.byteCountToDisplaySize(pendingSizeByte),
          FileUtils.byteCountToDisplaySize(QUEUE_FLUSH_THRESHOLD_BYTES));

      final var tooLongSinceLastRecord = bufferManagerDequeue.getTimeOfLastRecord(stream)
          .map(time -> time.isBefore(Instant.now().minus(MAX_TIME_BETWEEN_REC_MINS, ChronoUnit.MINUTES)))
          .orElse(false);
      if (exceedSize || tooLongSinceLastRecord) {
        allocatableThreads--;

        if (streamToInProgressWorkers.containsKey(stream)) {
          streamToInProgressWorkers.get(stream).getAndAdd(1);
        } else {
          streamToInProgressWorkers.put(stream, new AtomicInteger(1));
        }

        flush(stream);
      }
    }
  }

  private void printWorkerInfo() {
    final var workerInfo = new StringBuilder().append("WORKER INFO").append(System.lineSeparator());

    final ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) workerPool;

    final int queueSize = threadPoolExecutor.getQueue().size();
    final int activeCount = threadPoolExecutor.getActiveCount();

    workerInfo.append(String.format("  Pool queue size: %d, Active threads: %d", queueSize, activeCount))
        .append(System.lineSeparator());

    for (final var streams : inProgressStreams) {
      workerInfo.append(String.format("  Stream %s in progress", streams.getName()))
          .append(System.lineSeparator());
    }
    log.info(workerInfo.toString());

  }

  private void flushAll() {
    log.info("Flushing all buffers..");
    for (final StreamDescriptor desc : bufferManagerDequeue.getBuffers().keySet()) {
      flush(desc);
    }
  }

  private void flush(final StreamDescriptor desc) {
    inProgressStreams.add(desc);
    workerPool.submit(() -> {
      log.info("Worker picked up work..");
      try {
        log.info("Attempting to read from queue {}. Current queue size: {}", desc, bufferManagerDequeue.getQueueSizeInRecords(desc));

        final var start = System.currentTimeMillis();
        final var batch = bufferManagerDequeue.take(desc, flusher.getOptimalBatchSizeBytes());
        log.info("Time to read from queue seconds: {}", (System.currentTimeMillis() - start) / 1000);

        try (batch) {
          flusher.flush(desc, batch.getData());
        }
        inProgressStreams.remove(desc);
        final var inProg = streamToInProgressWorkers.get(desc).decrementAndGet();
        if (inProg < 0) {
          log.warn("in progress counter should never be <0");
        }

        log.info("Worker finished flushing. Current queue size: {}", bufferManagerDequeue.getQueueSizeInRecords(desc));
      } catch (final Exception e) {
        throw new RuntimeException(e);
      }
    });
  }

  @Override
  public void close() throws Exception {
    flushAll();

    supervisorThread.shutdown();
    final var supervisorShut = supervisorThread.awaitTermination(5L, TimeUnit.MINUTES);
    log.info("Supervisor shut status: {}", supervisorShut);

    log.info("Starting worker pool shutdown..");
    workerPool.shutdown();
    final var workersShut = workerPool.awaitTermination(5L, TimeUnit.MINUTES);
    log.info("Workers shut status: {}", workersShut);

    debugLoop.shutdownNow();
  }

}

// var s = Stream.generate(() -> {
// try {
// return queue.take();
// } catch (InterruptedException e) {
// throw new RuntimeException(e);
// }
// }).map(MemoryBoundedLinkedBlockingQueue.MemoryItem::item);
