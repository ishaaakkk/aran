package edu.upc.dtak.aran.core.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry holding JDBC driver metadata (class names, download URLs, URL templates, and SHA-256 hashes).
 */
public final class JdbcDriverRegistry {

    public record DriverMetadata(
            String engine,
            String mavenCoordinates,
            String driverClassName,
            String urlTemplate,
            String jarDownloadUrl,
            String expectedSha256
    ) {}

    private static final Map<String, DriverMetadata> REGISTRY = new HashMap<>();

    static {
        // Oracle DB (Thin driver) - Modern Version (late 2024)
        REGISTRY.put("ORACLE", new DriverMetadata(
                "ORACLE",
                "com.oracle.database.jdbc:ojdbc11:23.6.0.24.10",
                "oracle.jdbc.OracleDriver",
                "jdbc:oracle:thin:@{host}:{port}:{database}",
                "https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc11/23.6.0.24.10/ojdbc11-23.6.0.24.10.jar",
                "8e6af2c3401c64270922e0dca66879fd07281de3347ad1d1a0e1153ed41423a6"
        ));

        // MySQL DB - Modern Version (early 2025)
        REGISTRY.put("MYSQL", new DriverMetadata(
                "MYSQL",
                "com.mysql:mysql-connector-j:9.2.0",
                "com.mysql.cj.jdbc.Driver",
                "jdbc:mysql://{host}:{port}/{database}",
                "https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/9.2.0/mysql-connector-j-9.2.0.jar",
                "7e9941bbdcca244d878ea95bfff788fd9ba6a65af757f24be6c632930d61c7ed"
        ));

        // PostgreSQL - Modern Version
        REGISTRY.put("POSTGRESQL", new DriverMetadata(
                "POSTGRESQL",
                "org.postgresql:postgresql:42.7.5",
                "org.postgresql.Driver",
                "jdbc:postgresql://{host}:{port}/{database}",
                "https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.5/postgresql-42.7.5.jar",
                "69020b3bd20984543e817393f2e6c01a890ef2e37a77dd11d6d8508181d079ab"
        ));

        // MariaDB DB - Modern Version (early 2025)
        REGISTRY.put("MARIADB", new DriverMetadata(
                "MARIADB",
                "org.mariadb.jdbc:mariadb-java-client:3.5.2",
                "org.mariadb.jdbc.Driver",
                "jdbc:mariadb://{host}:{port}/{database}",
                "https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.5.2/mariadb-java-client-3.5.2.jar",
                "f2f3c3c1a3bdaca69dd1d4e1cd8aed075242fc72ae41463ddb82e367b388f6ad"
        ));

        // SQL Server. Hash verified against the jar downloaded from Maven Central.
        REGISTRY.put("SQLSERVER", new DriverMetadata(
                "SQLSERVER",
                "com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11",
                "com.microsoft.sqlserver.jdbc.SQLServerDriver",
                "jdbc:sqlserver://{host}:{port};databaseName={database};encrypt=false",
                "https://repo1.maven.org/maven2/com/microsoft/sqlserver/mssql-jdbc/12.8.1.jre11/mssql-jdbc-12.8.1.jre11.jar",
                "e6933c0711e598a224060e52ed31392f720a4a7664e85d8ae37c52a85b67ebb0"
        ));

        REGISTRY.put("HBASE", new DriverMetadata(
                "HBASE",
                "org.apache.phoenix:phoenix-queryserver-client:6.0.0",
                "org.apache.phoenix.queryserver.client.Driver",
                "jdbc:phoenix:thin:url=http://{host}:{port}",
                "https://repo1.maven.org/maven2/org/apache/phoenix/phoenix-queryserver-client/6.0.0/phoenix-queryserver-client-6.0.0.jar",
                "94e634ded958525f1dee090999c77ab1d0e7e022ea2767b0c73416cc67c8ebcf"
        ));
    }

    public static Optional<DriverMetadata> get(String engine) {
        if (engine == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(REGISTRY.get(engine.toUpperCase().trim()));
    }

    private JdbcDriverRegistry() {
    }
}
