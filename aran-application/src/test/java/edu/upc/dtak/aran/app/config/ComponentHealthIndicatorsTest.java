package edu.upc.dtak.aran.app.config;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentHealthIndicatorsTest {

    private HttpServer server;
    private String baseUrl;
    private int healthzStatus;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/healthz", exchange -> {
            exchange.sendResponseHeaders(healthzStatus, -1);
            exchange.close();
        });
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("FREYJA is DOWN when /healthz reports the ranking model missing (503)")
    void freyjaDownOnDegradedHealthz() {
        healthzStatus = 503;
        var health = new ComponentHealthIndicators().freyjaHealthIndicator(baseUrl).health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("status", 503);
    }

    @Test
    @DisplayName("FREYJA is UP when /healthz answers 200")
    void freyjaUpOnHealthyHealthz() {
        healthzStatus = 200;
        var health = new ComponentHealthIndicators().freyjaHealthIndicator(baseUrl).health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("status", 200);
    }

    @Test
    @DisplayName("Other components stay UP on any HTTP answer, including 404")
    void otherProbesTreatAnyResponseAsUp() {
        var health = new ComponentHealthIndicators().lakeHealthIndicator(baseUrl).health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("status", 404);
    }
}
