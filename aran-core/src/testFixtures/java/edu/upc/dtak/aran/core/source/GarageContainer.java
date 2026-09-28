package edu.upc.dtak.aran.core.source;

import com.github.dockerjava.api.command.InspectContainerResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * A single-node Garage — the S3-compatible server the standalone and e2e stacks ship — with one
 * bucket and one key that can read and write it, for tests that must reach a real {@code s3://}
 * object over httpfs.
 *
 * <p>It lives in aran-core's fixtures rather than in an operator module because bootstrapping and
 * query-engine both need it and must not depend on each other (ArchitectureTest's
 * {@code operatorsAreIndependent}); a fixture in one of them would put that module's main classes
 * on the other's test classpath.
 *
 * <p>Pass {@link #region()} to anything that signs a request: Garage rejects a SigV4 credential
 * scope that names any region other than its configured {@code s3_region}, so a client left on the
 * {@code us-east-1} default fails authentication rather than reading the object.
 */
public final class GarageContainer extends GenericContainer<GarageContainer> {

    /** The image docker/e2e and aran-lake's compose run, so a test proves the server we ship beside. */
    public static final String IMAGE = "dxflrs/garage:v1.0.1";
    public static final String REGION = "garage";
    /**
     * The provider a user registers Garage under: the repository dialog offers S3/MINIO/GCS/AZURE
     * and no GARAGE, and MINIO is its entry for a self-hosted S3-compatible server.
     */
    public static final String PROVIDER = "MINIO";

    private static final int S3_PORT = 3900;
    private static final SecureRandom RANDOM = new SecureRandom();

    // IPv4 binds for the reason docker/e2e/garage.toml gives: a "[::]" bind kills Garage on a
    // kernel without IPv6. rpc_public_addr is what the CLI exec'd below dials, inside the container.
    private static final String CONFIG = """
            metadata_dir = "/var/lib/garage/meta"
            data_dir = "/var/lib/garage/data"
            db_engine = "sqlite"
            replication_factor = 1
            rpc_secret = "%s"
            rpc_bind_addr = "0.0.0.0:3901"
            rpc_public_addr = "127.0.0.1:3901"

            [s3_api]
            api_bind_addr = "0.0.0.0:3900"
            s3_region = "%s"
            """;

    private String bucket = "aran-test";
    // Random per container, not init.sh's all-zero dev key: a test asserting the secret never
    // surfaces needs a needle that cannot occur in an error message by coincidence. Garage imports
    // only its own key shape (GK + 12 hex bytes, a 32-byte hex secret), so a word cannot be used.
    private String accessKey = "GK" + randomHex(12);
    private String secretKey = randomHex(32);

    public GarageContainer() {
        super(DockerImageName.parse(IMAGE));
        withExposedPorts(S3_PORT);
        withCopyToContainer(Transferable.of(CONFIG.formatted(randomHex(32), REGION)),
                "/etc/garage.toml");
        waitingFor(Wait.forLogMessage(".*S3 API server listening on .*", 1));
    }

    public GarageContainer withBucket(String bucket) {
        this.bucket = bucket;
        return this;
    }

    /**
     * aran-lake/docker/garage/init.sh's bootstrap, against a fresh node: Garage stores nothing until
     * the node holds a role in an applied layout, and a key reaches a bucket only through an explicit
     * grant. The image is FROM scratch, so each step execs the binary directly — there is no shell.
     */
    @Override
    protected void containerIsStarted(InspectContainerResponse containerInfo) {
        String nodeId = garage("node", "id", "-q").trim().split("@", 2)[0];
        garage("layout", "assign", "-z", "dc1", "-c", "1G", nodeId);
        garage("layout", "apply", "--version", "1");
        garage("key", "import", "--yes", accessKey, secretKey);
        garage("bucket", "create", bucket);
        garage("bucket", "allow", "--read", "--write", "--owner", bucket, "--key", accessKey);
    }

    private String garage(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "/garage";
        System.arraycopy(args, 0, command, 1, args.length);
        try {
            Container.ExecResult result = execInContainer(command);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("garage " + String.join(" ", args) + " exited "
                        + result.getExitCode() + ": " + result.getStderr());
            }
            return result.getStdout();
        } catch (IOException e) {
            throw new IllegalStateException("garage " + String.join(" ", args) + " could not run", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during garage " + String.join(" ", args), e);
        }
    }

    /** {@code http://host:port} — the scheme is what tells DuckDbS3Config to turn TLS off. */
    public String endpoint() {
        return "http://" + getHost() + ":" + getMappedPort(S3_PORT);
    }

    public String region() {
        return REGION;
    }

    public String bucket() {
        return bucket;
    }

    public String accessKey() {
        return accessKey;
    }

    public String secretKey() {
        return secretKey;
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
