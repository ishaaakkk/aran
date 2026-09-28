package edu.upc.dtak.aran.app.jdbc;

import org.apache.calcite.avatica.remote.Handler;
import org.apache.calcite.avatica.remote.JsonHandler;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The wire end of Aran's JDBC support: Avatica's JSON protocol over one POST route.
 *
 * <p>Clients connect with Avatica's own remote driver — there is no Aran-specific driver to
 * install, only a connection string:
 *
 * <pre>
 * jdbc:avatica:remote:url=http://host:8080/api/jdbc;serialization=json;
 *     authentication=BASIC;avatica_user=aran;avatica_password=aran_pat_…
 * </pre>
 *
 * <p>This is a plain Spring controller rather than Avatica's {@code AvaticaJsonHandler} because
 * that class extends a Jetty handler, and this application runs on Tomcat. {@link JsonHandler}
 * lives in avatica-core and is transport-agnostic, so the protocol is handled without a second
 * embedded web server, a second port, or a second security configuration to keep in step with
 * this one.
 */
@RestController
@RequestMapping("/api/jdbc")
@NullMarked
public class JdbcEndpointController {

    private final JsonHandler handler;

    public JdbcEndpointController(JsonHandler handler) {
        this.handler = handler;
    }

    /**
     * One Avatica request in, one out.
     *
     * <p>The body is taken as a String and returned as a String: it is Avatica's own JSON envelope,
     * and letting Jackson bind it here would mean re-deriving a protocol Avatica already
     * serialises. The handler reports the HTTP status the protocol expects — including for an
     * error, which arrives as a structured Avatica error the driver turns back into a
     * {@code SQLException} carrying the engine's message.
     */
    @PostMapping(consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handle(@RequestBody String request) {
        Handler.HandlerResponse<String> response = handler.apply(request);
        return ResponseEntity.status(response.getStatusCode()).body(response.getResponse());
    }
}
