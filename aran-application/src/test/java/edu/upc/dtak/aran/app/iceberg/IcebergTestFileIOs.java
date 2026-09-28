package edu.upc.dtak.aran.app.iceberg;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.io.SeekableInputStream;

/**
 * The two {@link FileIO}s the live Iceberg ITs hand to iceberg-core's {@code RESTCatalog} through
 * its {@code io-impl} property.
 *
 * <p>Both exist because iceberg-core ships no FileIO for either job: every real one lives in a
 * cloud or Hadoop module (S3FileIO, HadoopFileIO…) this repository deliberately does not depend
 * on. They are test plumbing — nothing in production reads or writes Iceberg files through them.
 */
public final class IcebergTestFileIOs {

    private IcebergTestFileIOs() {
    }

    /**
     * Local-filesystem FileIO for the REST fixture's bind-mounted {@code file://} warehouse: the
     * test JVM writes data files and manifests into the same host directory the container writes
     * table metadata into.
     */
    public static final class LocalFileIO implements FileIO {

        public LocalFileIO() {
        }

        @Override
        public InputFile newInputFile(String path) {
            return org.apache.iceberg.Files.localInput(toFile(path));
        }

        @Override
        public OutputFile newOutputFile(String path) {
            return org.apache.iceberg.Files.localOutput(toFile(path));
        }

        @Override
        public void deleteFile(String path) {
            try {
                Files.deleteIfExists(toFile(path).toPath());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        static File toFile(String location) {
            return location.startsWith("file:")
                    ? Path.of(URI.create(location)).toFile()
                    : new File(location);
        }
    }

    /**
     * Read-only HTTP FileIO that sends the catalog's own {@code token} property as a bearer header
     * on every file read — the one thing a client needs to follow Aran's produce-side metadata,
     * whose every location is an authenticated {@code /api/iceberg/files/…} URL.
     *
     * <p>Whole-file reads, buffered: the files here are a metadata JSON and two small Avro
     * manifests, and a range-reading implementation would be testing itself rather than the
     * server.
     */
    public static final class BearerHttpFileIO implements FileIO {

        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        private String token = "";

        public BearerHttpFileIO() {
        }

        @Override
        public void initialize(Map<String, String> properties) {
            this.token = properties.getOrDefault("token", "");
        }

        @Override
        public InputFile newInputFile(String path) {
            return new BufferedInputFile(path);
        }

        @Override
        public OutputFile newOutputFile(String path) {
            throw new UnsupportedOperationException("Aran's Iceberg catalog is read-only: " + path);
        }

        @Override
        public void deleteFile(String path) {
            throw new UnsupportedOperationException("Aran's Iceberg catalog is read-only: " + path);
        }

        private byte[] fetch(String location) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(location))
                    .timeout(Duration.ofSeconds(30))
                    .GET();
            if (!token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }
            try {
                HttpResponse<byte[]> response =
                        http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException(
                            "GET " + location + " answered HTTP " + response.statusCode());
                }
                return response.body();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        private final class BufferedInputFile implements InputFile {
            private final String location;
            private byte[] bytes;

            BufferedInputFile(String location) {
                this.location = location;
            }

            private byte[] bytes() {
                if (bytes == null) {
                    bytes = fetch(location);
                }
                return bytes;
            }

            @Override
            public long getLength() {
                return bytes().length;
            }

            @Override
            public SeekableInputStream newStream() {
                return new ByteArraySeekableStream(bytes());
            }

            @Override
            public String location() {
                return location;
            }

            @Override
            public boolean exists() {
                return true;
            }
        }
    }

    private static final class ByteArraySeekableStream extends SeekableInputStream {
        private final byte[] bytes;
        private int pos;

        ByteArraySeekableStream(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public long getPos() {
            return pos;
        }

        @Override
        public void seek(long newPos) {
            pos = (int) newPos;
        }

        @Override
        public int read() {
            return pos < bytes.length ? bytes[pos++] & 0xff : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= bytes.length) {
                return -1;
            }
            int n = Math.min(len, bytes.length - pos);
            System.arraycopy(bytes, pos, b, off, n);
            pos += n;
            return n;
        }
    }
}
