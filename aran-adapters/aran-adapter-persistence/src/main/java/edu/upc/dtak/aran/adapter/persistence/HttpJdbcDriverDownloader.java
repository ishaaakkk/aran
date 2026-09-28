package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.core.domain.DriverDownloader;
import edu.upc.dtak.aran.core.service.JdbcDriverRegistry;

import java.io.File;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Driver;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Downloads JDBC drivers dynamically from Maven Central and constructs isolated ClassLoaders.
 */
@Component
public final class HttpJdbcDriverDownloader implements DriverDownloader, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(HttpJdbcDriverDownloader.class);

    private final HttpClient httpClient;
    private final Path baseDir;
    private final ConcurrentHashMap<String, Driver> loadedDrivers = new ConcurrentHashMap<>();

    public HttpJdbcDriverDownloader(
            @Value("${aran.jdbc.driver-cache-dir:drivers}") String cacheDirPath) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .proxy(ProxySelector.getDefault())
                .build();

        this.baseDir = Paths.get(cacheDirPath).toAbsolutePath().normalize();
        try {
            if (!Files.exists(baseDir)) {
                Files.createDirectories(baseDir);
                log.info("Created local driver cache directory at: {}", baseDir.toAbsolutePath());
            }
        } catch (java.io.IOException e) {
            log.error("Failed to create driver cache directory at {}: {}", baseDir.toAbsolutePath(), e.getMessage());
        }
    }

    /**
     * Downloads the driver JAR file for the given engine if not already cached and verified.
     *
     * @return the local cached file
     */
    public File getOrDownloadDriver(String engine) throws Exception {
        Optional<JdbcDriverRegistry.DriverMetadata> metaOpt = JdbcDriverRegistry.get(engine);
        if (metaOpt.isEmpty()) {
            throw new IllegalArgumentException("Unsupported database engine: " + engine);
        }

        JdbcDriverRegistry.DriverMetadata meta = metaOpt.get();
        String filename = getFilenameFromUrl(meta.jarDownloadUrl());
        File cachedFile = baseDir.resolve(filename).toFile();
        Path targetPath = cachedFile.toPath();

        if (Files.exists(targetPath)) {
            try {
                verifySha256(targetPath, meta.expectedSha256());
                log.debug("Found valid cached driver JAR for engine {}: {}", engine, cachedFile.getName());
                return cachedFile;
            } catch (java.io.IOException | SecurityException | java.security.NoSuchAlgorithmException e) {
                log.warn("Cached driver JAR for engine {} is corrupted or invalid ({}). Deleting and re-downloading...", engine, e.getMessage());
                Files.deleteIfExists(targetPath);
            }
        }

        log.info("Driver JAR not found or invalid for {}. Downloading from: {}", engine, meta.jarDownloadUrl());
        Path tempFile = Files.createTempFile(baseDir, "driver-", ".tmp");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(meta.jarDownloadUrl()))
                    .GET()
                    .build();

            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));

            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to download driver JAR from Maven Central. HTTP status: " + response.statusCode());
            }

            verifySha256(tempFile, meta.expectedSha256());

            Files.move(tempFile, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("Successfully downloaded and verified driver JAR to: {}", cachedFile.getAbsolutePath());
            return cachedFile;
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Loads the SQL Driver class dynamically using an isolated, cached URLClassLoader.
     */
    public Driver loadDriver(String engine) throws Exception {
        Optional<JdbcDriverRegistry.DriverMetadata> metaOpt = JdbcDriverRegistry.get(engine);
        if (metaOpt.isEmpty()) {
            throw new IllegalArgumentException("Unsupported database engine: " + engine);
        }

        JdbcDriverRegistry.DriverMetadata meta = metaOpt.get();

        // Standard classpath fast-path for ALL engines (not just PostgreSQL)
        try {
            Class<?> loaded = Class.forName(meta.driverClassName());
            log.debug("Found driver class {} on main classpath, bypassing dynamic load", meta.driverClassName());
            return (Driver) loaded.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            log.debug("Driver class {} not found on main classpath, proceeding with dynamic load", meta.driverClassName());
        }

        // Return from memory cache if already loaded to prevent ClassLoader metaspace and file handle leaks
        if (loadedDrivers.containsKey(meta.engine())) {
            return loadedDrivers.get(meta.engine());
        }

        synchronized (loadedDrivers) {
            if (loadedDrivers.containsKey(meta.engine())) {
                return loadedDrivers.get(meta.engine());
            }

            File jarFile = getOrDownloadDriver(engine);
            URL jarUrl = jarFile.toURI().toURL();

            URLClassLoader ucl = new URLClassLoader(new URL[]{jarUrl}, this.getClass().getClassLoader());

            Class<?> driverClass = Class.forName(meta.driverClassName(), true, ucl);
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            loadedDrivers.put(meta.engine(), driver);
            return driver;
        }
    }

    /**
     * Unloads the specified JDBC driver, deregistering all dynamic driver instances from
     * DriverManager and cleanly closing the isolated ClassLoader to release system resources.
     */
    public void unloadDriver(String engine) {
        synchronized (loadedDrivers) {
            Driver driver = loadedDrivers.remove(engine);
            if (driver != null) {
                ClassLoader cl = driver.getClass().getClassLoader();
                log.info("Deregistering JDBC driver engine '{}' from DriverManager", engine);

                try {
                    java.sql.DriverManager.deregisterDriver(driver);
                } catch (java.sql.SQLException e) {
                    log.warn("Failed to deregister direct driver instance for engine {}: {}", engine, e.getMessage());
                }

                // Deregister any self-registered static driver instances inside the dynamic ClassLoader
                try {
                    java.util.Enumeration<Driver> registered = java.sql.DriverManager.getDrivers();
                    while (registered.hasMoreElements()) {
                        Driver reg = registered.nextElement();
                        if (reg.getClass().getClassLoader() == cl) {
                            try {
                                java.sql.DriverManager.deregisterDriver(reg);
                                log.debug("Deregistered dynamic self-registered instance {} from DriverManager", reg.getClass().getName());
                            } catch (java.sql.SQLException e) {
                                log.warn("Failed to deregister self-registered instance for dynamic ClassLoader: {}", e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Error checking for self-registered drivers inside dynamic ClassLoader: {}", e.getMessage());
                }

                // Close ClassLoader explicitly to release file descriptor locks on the cached JAR
                if (cl instanceof URLClassLoader ucl) {
                    try {
                        ucl.close();
                        log.info("Closed URLClassLoader for dynamic JDBC driver engine '{}'", engine);
                    } catch (java.io.IOException e) {
                        log.warn("Failed to close URLClassLoader for engine {}: {}", engine, e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Context pre-destruction lifecycle listener ensuring all dynamically loaded drivers
     * are completely scrubbed from DriverManager and ClassLoader memory at shutdown.
     */
    @Override
    public void destroy() {
        log.info("Spring context pre-destruction triggered: Unloading all dynamically cached JDBC driver classloaders...");
        for (String engine : java.util.Set.copyOf(loadedDrivers.keySet())) {
            unloadDriver(engine);
        }
    }

    private void verifySha256(Path file, String expectedSha256) throws java.io.IOException, java.security.NoSuchAlgorithmException, SecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hashBytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        String actualSha256 = sb.toString();
        if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
            throw new SecurityException("Downloaded driver JAR SHA-256 hash mismatch! " +
                    "Expected: " + expectedSha256 + ", Actual: " + actualSha256);
        }
    }

    private String getFilenameFromUrl(String url) {
        if (url == null) return "driver.jar";
        int idx = url.lastIndexOf('/');
        if (idx == -1 || idx == url.length() - 1) {
            return "driver.jar";
        }
        return url.substring(idx + 1);
    }
}
