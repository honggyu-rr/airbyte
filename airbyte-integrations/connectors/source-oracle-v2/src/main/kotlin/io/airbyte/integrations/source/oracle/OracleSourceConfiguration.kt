/*
 * Copyright (c) 2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.source.oracle

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonGetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaArrayWithUniqueItems
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaDefault
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaDescription
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaInject
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaTitle
import io.airbyte.cdk.command.CONNECTOR_CONFIG_PREFIX
import io.airbyte.cdk.command.ConnectorConfigurationSupplier
import io.airbyte.cdk.command.JsonParser
import io.airbyte.cdk.command.SourceConnectorConfiguration
import io.airbyte.cdk.ssh.MicronautFriendlySshTunnelMethod
import io.airbyte.cdk.ssh.SshConnectionOptions
import io.airbyte.cdk.ssh.SshTunnelMethod
import io.airbyte.commons.exceptions.ConfigErrorException
import io.airbyte.commons.io.IOs
import io.airbyte.protocol.models.v0.AirbyteStateMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.ConfigurationBuilder
import io.micronaut.context.annotation.ConfigurationProperties
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.util.*
import org.apache.commons.lang3.RandomStringUtils
import org.bouncycastle.util.io.pem.PemReader


private val logger = KotlinLogging.logger {}

data class OracleSourceConfiguration(
    override val realHost: String,
    override val realPort: Int,
    override val sshTunnel: SshTunnelMethod,
    override val sshConnectionOptions: SshConnectionOptions,
    override val jdbcUrlFmt: String,
    override val jdbcProperties: Map<String, String>,
    val defaultSchema: String,
    override val schemas: List<String>
) : SourceConnectorConfiguration {

    override fun getDefaultNamespace(): Optional<String> = Optional.of(defaultSchema)

    override val expectedStateType = AirbyteStateMessage.AirbyteStateType.STREAM
}

@ConfigurationProperties(CONNECTOR_CONFIG_PREFIX)
@JsonSchemaTitle("Oracle Source Spec")
@JsonPropertyOrder(
    value =
    [
        "host",
        "port",
        "connection_data",
        "username",
        "password",
        "schemas",
        "jdbc_url_params",
        "encryption",
    ],
)
private class Configuration : ConnectorConfigurationSupplier<OracleSourceConfiguration> {

    @JsonIgnore var json: String? = null

    @JsonIgnore
    private val validated: Lazy<OracleSourceConfiguration> = lazy {
        buildConfiguration(JsonParser.parse(json, this))
    }

    @JsonIgnore override fun get(): OracleSourceConfiguration = validated.value

    @JsonProperty("host", required = true)
    @JsonSchemaTitle("Host")
    @JsonSchemaInject(json = """{"order":1}""")
    @JsonPropertyDescription("Hostname of the database.")
    var host: String? = null

    @JsonProperty("port", required = true)
    @JsonSchemaTitle("Port")
    @JsonSchemaInject(json = """{"order":2,"minimum": 0,"maximum": 65536}""")
    @JsonSchemaDefault("1521")
    @JsonPropertyDescription(
        "Port of the database.\n" +
            "Oracle Corporations recommends the following port numbers:\n" +
            "1521 - Default listening port for client connections to the listener. \n" +
            "2484 - Recommended and officially registered listening port for client " +
            "connections to the listener using TCP/IP with SSL.",
    )
    var port: Int? = null

    @JsonIgnore
    @ConfigurationBuilder(configurationPrefix = "connection_data")
    val connectionData = MicronautFriendlyConnectionData()

    @JsonIgnore
    var connectionDataJson: ConnectionData? = null

    @JsonSetter("connection_data")
    fun setConnectionDataValue(value: ConnectionData) {
        connectionDataJson = value
    }

    @JsonGetter("connection_data")
    @JsonSchemaInject(json = """{"order":3}""")
    fun getConnectionDataValue(): ConnectionData =
        connectionDataJson ?: connectionData.asConnectionData()


    @JsonProperty("username", required = true)
    @JsonSchemaTitle("User")
    @JsonPropertyDescription("The username which is used to access the database.")
    @JsonSchemaInject(json = """{"order":4}""")
    var username: String? = null

    @JsonProperty("password")
    @JsonSchemaTitle("Password")
    @JsonPropertyDescription("The password associated with the username.")
    @JsonSchemaInject(json = """{"order":5,"airbyte_secret": true}""")
    var password: String? = null

    @JsonProperty("schemas")
    @JsonSchemaTitle("Schemas")
    @JsonSchemaArrayWithUniqueItems("schemas")
    @JsonPropertyDescription("The list of schemas to sync from. Defaults to user. Case sensitive.")
    @JsonSchemaInject(json = """{"order":6,"minItems":1,"uniqueItems":true}""")
    var schemas: List<String> = listOf()

    @JsonProperty("jdbc_url_params")
    @JsonSchemaTitle("JDBC URL Params")
    @JsonPropertyDescription(
        "Additional properties to pass to the JDBC URL string when connecting to the database " +
            "formatted as 'key=value' pairs separated by the symbol '&'. " +
            "(example: key1=value1&key2=value2&key3=value3).",
    )
    @JsonSchemaInject(json = """{"order":7}""")
    var jdbcUrlParams: String? = null

    @JsonIgnore
    @ConfigurationBuilder(configurationPrefix = "encryption")
    val encryption = MicronautFriendlyEncryption()

    @JsonIgnore
    var encryptionJson: Encryption? = null

    @JsonSetter("encryption")
    fun setEncryptionValue(value: Encryption) {
        encryptionJson = value
    }

    @JsonGetter("encryption")
    @JsonSchemaInject(json = """{"order":8}""")
    fun getEncryptionValue(): Encryption =
        encryptionJson ?: encryption.asEncryption()

    @JsonIgnore
    @ConfigurationBuilder(configurationPrefix = "tunnel_method")
    val tunnelMethod = MicronautFriendlySshTunnelMethod()

    @JsonIgnore
    var tunnelMethodJson: SshTunnelMethod? = null

    @JsonSetter("tunnel_method")
    fun setTunnelMethodValue(value: SshTunnelMethod) {
        tunnelMethodJson = value
    }

    @JsonGetter("tunnel_method")
    @JsonSchemaInject(json = """{"order":9}""")
    fun getTunnelMethodValue(): SshTunnelMethod =
        tunnelMethodJson ?: tunnelMethod.asSshTunnelMethod()

    @JsonIgnore
    var additionalPropertiesMap = mutableMapOf<String, Any>()

    @JsonAnyGetter
    fun getAdditionalProperties(): Map<String, Any> {
        return additionalPropertiesMap
    }

    @JsonAnySetter
    fun setAdditionalProperty(name: String, value: Any) {
        additionalPropertiesMap[name] = value
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "connection_type")
@JsonSubTypes(
    JsonSubTypes.Type(value = ServiceName::class, name = "service_name"),
    JsonSubTypes.Type(value = Sid::class, name = "sid"),
)
@JsonSchemaTitle("Connect by")
@JsonSchemaDescription("Connect data that will be used for DB connection.")
private sealed interface ConnectionData

@JsonSchemaTitle("Service name")
@JsonSchemaDescription("Use service name.")
private class ServiceName : ConnectionData {

    @JsonProperty("service_name", required = true)
    @JsonSchemaTitle("Service name")
    var serviceName: String? = null
}

@JsonSchemaTitle("System ID (SID)")
@JsonSchemaDescription("Use Oracle System Identifier.")
private class Sid : ConnectionData {

    @JsonProperty("sid", required = true)
    @JsonSchemaTitle("System ID (SID)")
    var sid: String? = null
}

@ConfigurationProperties("$CONNECTOR_CONFIG_PREFIX.connection_data")
private class MicronautFriendlyConnectionData {

    var connectionType: String = "service_name"
    var serviceName: String? = null
    var sid: String? = null

    @JsonValue
    fun asConnectionData(): ConnectionData =
        when (connectionType) {
            "service_name" -> ServiceName().also { it.serviceName = serviceName }
            "sid" -> Sid().also { it.sid = sid }
            else -> throw ConfigErrorException("invalid value $connectionType")
        }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "encryption_method")
@JsonSubTypes(
    JsonSubTypes.Type(value = Unencrypted::class, name = "unencrypted"),
    JsonSubTypes.Type(value = EncryptionAlgorithm::class, name = "client_nne"),
    JsonSubTypes.Type(value = SslCertificate::class, name = "encrypted_verify_certificate"),
)
@JsonSchemaTitle("Encryption")
@JsonSchemaDescription("The encryption method which is used when communicating with the database.")
private sealed interface Encryption

@JsonSchemaTitle("Unencrypted")
@JsonSchemaDescription("Data transfer will not be encrypted.")
private data object Unencrypted : Encryption

@JsonSchemaTitle("Native Network Encryption (NNE)")
@JsonSchemaDescription(
    "The native network encryption gives you the ability to encrypt database " +
        "connections, without the configuration overhead of TCP/IP and SSL/TLS and without the need " +
        "to open and listen on different ports.",
)
private class EncryptionAlgorithm : Encryption {

    @JsonProperty("encryption_algorithm", required = true)
    @JsonSchemaTitle("Encryption Algorithm")
    @JsonPropertyDescription("This parameter defines what encryption algorithm is used.")
    @JsonSchemaDefault("AES256")
    @JsonSchemaInject(json = """{"enum":["AES256","RC4_56","3DES168"]}""")
    var encryptionAlgorithm: String? = null
}

@JsonSchemaTitle("TLS Encrypted (verify certificate)")
@JsonSchemaDescription("Verify and use the certificate provided by the server.")
private class SslCertificate : Encryption {

    @JsonProperty("ssl_certificate", required = true)
    @JsonSchemaTitle("SL PEM File")
    @JsonPropertyDescription(
        "Privacy Enhanced Mail (PEM) files are concatenated certificate " +
            "containers frequently used in certificate installations.",
    )
    @JsonSchemaInject(json = """{"airbyte_secret":true,"multiline":true}""")
    var sslCertificate: String? = null
}

@ConfigurationProperties("$CONNECTOR_CONFIG_PREFIX.encryption")
private class MicronautFriendlyEncryption {

    var encryptionMethod: String = "unencrypted"
    var encryptionAlgorithm: String? = null
    var sslCertificate: String? = null

    @JsonValue
    fun asEncryption(): Encryption =
        when (encryptionMethod) {
            "unencrypted" -> Unencrypted
            "client_nne" ->
                EncryptionAlgorithm().also { it.encryptionAlgorithm = encryptionAlgorithm }
            "encrypted_verify_certificate" ->
                SslCertificate().also { it.sslCertificate = sslCertificate }
            else -> throw ConfigErrorException("invalid value $encryptionMethod")
        }
}

private fun buildConfiguration(pojo: Configuration): OracleSourceConfiguration {
    val realHost: String = pojo.host!!
    val realPort: Int = pojo.port!!
    val sshTunnel: SshTunnelMethod = pojo.getTunnelMethodValue()
    val jdbcProperties = mutableMapOf<String, String>()
    jdbcProperties["user"] = pojo.username!!
    pojo.password?.let { jdbcProperties["password"] = it }
    /*
     * The property useFetchSizeWithLongColumn required to select LONG or LONG RAW columns. Oracle
     * recommends avoiding LONG and LONG RAW columns. Use LOB instead. They are included in Oracle
     * only for legacy reasons.
     *
     * THIS IS A THIN ONLY PROPERTY. IT SHOULD NOT BE USED WITH ANY OTHER DRIVERS.
     *
     * See https://docs.oracle.com/cd/E11882_01/appdev.112/e13995/oracle/jdbc/OracleDriver.html
     * https://docs.oracle.com/cd/B19306_01/java.102/b14355/jstreams.htm#i1014085
     */
    jdbcProperties["oracle.jdbc.useFetchSizeWithLongColumn"] = "true"
    // Parse URL parameters.
    val pattern = "^([^=]+)=(.*)$".toRegex()
    for (pair in (pojo.jdbcUrlParams ?: "").trim().split("&".toRegex())) {
        if (pair.isBlank()) {
            continue
        }
        val result: MatchResult? = pattern.matchEntire(pair)
        if (result == null) {
            logger.warn { "ignoring invalid JDBC URL param '$pair'" }
        } else {
            val key: String = result.groupValues[1].trim()
            val urlEncodedValue: String = result.groupValues[2].trim()
            jdbcProperties[key] = URLDecoder.decode(urlEncodedValue, StandardCharsets.UTF_8)
        }
    }
    // Determine protocol and configure encryption.
    val encryption: Encryption = pojo.getEncryptionValue()
    if (encryption is SslCertificate) {
        val pemFileContents: String = encryption.sslCertificate!!
        val pemReader = PemReader(StringReader(pemFileContents))
        val certDer = pemReader.readPemObject().content
        val cf: CertificateFactory = CertificateFactory.getInstance("X.509")
        val cert: Certificate = cf.generateCertificate(certDer.inputStream())
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null) // Initialize the KeyStore
        keyStore.setCertificateEntry("rds-root", cert)
        val keyStorePass: String = RandomStringUtils.randomAlphanumeric(8)
        val keyStoreFile = File(IOs.writeFileToRandomTmpDir("clientkeystore.jks", ""))
        keyStoreFile.deleteOnExit()
        val fos = FileOutputStream(keyStoreFile)
        keyStore.store(fos, keyStorePass.toCharArray())
        fos.close()
        jdbcProperties["javax.net.ssl.trustStore"] = keyStoreFile.toString()
        jdbcProperties["javax.net.ssl.trustStoreType"] = "JKS"
        jdbcProperties["javax.net.ssl.trustStorePassword"] = keyStorePass
    } else if (encryption is EncryptionAlgorithm) {
        val algorithm: String = encryption.encryptionAlgorithm!!
        jdbcProperties["oracle.net.encryption_client"] = "REQUIRED"
        jdbcProperties["oracle.net.encryption_types_client"] = "( $algorithm )"
    }
    val protocol: String = if (encryption is SslCertificate) "TCPS" else "TCP"
    // Build JDBC URL
    val address = "(ADDRESS=(PROTOCOL=${protocol})(HOST=%s)(PORT=%d))"
    val connectionData: ConnectionData = pojo.getConnectionDataValue()
    val (connectDataType: String, connectDataValue: String) =
        when (connectionData) {
            is ServiceName -> "SERVICE_NAME" to connectionData.serviceName!!
            is Sid -> "SID" to connectionData.sid!!
        }
    val connectData = "(CONNECT_DATA=($connectDataType=$connectDataValue))"
    val jdbcUrlFmt = "jdbc:oracle:thin:@(DESCRIPTION=${address}${connectData})"
    val defaultSchema: String = pojo.username!!.uppercase()
    return OracleSourceConfiguration(
        realHost = realHost,
        realPort = realPort,
        sshTunnel = sshTunnel,
        sshConnectionOptions = SshConnectionOptions.fromAdditionalProperties(pojo.getAdditionalProperties()),
        jdbcUrlFmt = jdbcUrlFmt,
        jdbcProperties = jdbcProperties,
        defaultSchema = defaultSchema,
        schemas = pojo.schemas.ifEmpty { listOf(defaultSchema) },
    )
}
