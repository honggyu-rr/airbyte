package io.airbyte.integrations.source.oracle;

import io.airbyte.cdk.db.factory.DatabaseDriver;
import io.airbyte.cdk.db.jdbc.JdbcUtils;
import io.airbyte.cdk.testutils.TestDatabase;
import org.jooq.SQLDialect;

import java.util.List;
import java.util.stream.Stream;

public class OracleTestDatabase extends
        TestDatabase<AirbyteOracleTestContainer, OracleTestDatabase, OracleTestDatabase.OracleDbConfigBuilder> {
    private static boolean containerStarted = false;
    private final AirbyteOracleTestContainer container;
    private final List<String> schemaNames;
    protected OracleTestDatabase(final AirbyteOracleTestContainer container, final List<String> schemaNames) {
        super(container);
        this.container = container;
        this.schemaNames = schemaNames;
    }

    @Override
    public OracleTestDatabase initialized() {
        if (!containerStarted) {
            container.start();
            containerStarted = true;
        }
        return super.initialized();
    }

    @Override
    public String getJdbcUrl() {
        return container.getJdbcUrl();
    }

    @Override
    public String getUserName() {
        return container.getUsername();
    }

    @Override
    public String getPassword() {
        return container.getPassword();
    }

    @Override
    public String getDatabaseName() {
        return container.getDatabaseName();
    }

    @Override
    protected Stream<Stream<String>> inContainerBootstrapCmd() {
        return Stream.empty();
    }

    @Override
    protected Stream<String> inContainerUndoBootstrapCmd() {
        return Stream.empty();
    }

    @Override
    public DatabaseDriver getDatabaseDriver() {
        return DatabaseDriver.ORACLE;
    }

    @Override
    public SQLDialect getSqlDialect() {
        return SQLDialect.DEFAULT;
    }

    @Override
    public OracleDbConfigBuilder configBuilder() {
        return new OracleDbConfigBuilder(this)
                .with(JdbcUtils.HOST_KEY, container.getHost())
                .with(JdbcUtils.PORT_KEY, container.getFirstMappedPort())
                .with("sid", container.getSid())
                .with(JdbcUtils.USERNAME_KEY, container.getUsername())
                .with(JdbcUtils.PASSWORD_KEY, container.getPassword())
                .with(JdbcUtils.SCHEMAS_KEY, schemaNames);
    }

    @Override
    public void close() {
    }

    static public class OracleDbConfigBuilder extends TestDatabase.ConfigBuilder<OracleTestDatabase, OracleDbConfigBuilder> {

        protected OracleDbConfigBuilder(final OracleTestDatabase testdb) {
            super(testdb);
        }

    }
}