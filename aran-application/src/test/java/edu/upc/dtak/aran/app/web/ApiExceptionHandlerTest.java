package edu.upc.dtak.aran.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import edu.upc.dtak.aran.core.service.PromoteConflictException;
import edu.upc.dtak.aran.core.service.StateConflictException;
import edu.upc.dtak.aran.core.domain.IdentityProviderPort;
import edu.upc.dtak.aran.core.service.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Status mapping for {@link ApiExceptionHandler}, exercised directly.
 */
@DisplayName("ApiExceptionHandler status mapping")
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @DisplayName("an unmatched route maps to 404, not 500 (F26)")
    void unmatchedRouteIsNotFound() {
        var ex = new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/actuator/health", "/actuator/health");

        ProblemDetail pd = handler.handleNoResourceFound(ex);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getTitle()).isEqualTo("Resource not found");
        assertThat(pd.getDetail()).contains("/actuator/health");
    }

    @Test
    @DisplayName("the handler is bound to NoResourceFoundException, so the catch-all cannot reclaim it")
    void handlerIsBoundToTheSpringException() throws Exception {
        var method = ApiExceptionHandler.class
                .getDeclaredMethod("handleNoResourceFound", NoResourceFoundException.class);
        var annotation = method.getAnnotation(ExceptionHandler.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).containsExactly(NoResourceFoundException.class);
    }

    @Test
    @DisplayName("a genuinely unhandled exception is still 500, with a generic detail")
    void unhandledIsStill500() {
        ProblemDetail pd = handler.handleThrowable(new RuntimeException("connection reset by peer"));

        assertThat(pd.getStatus()).isEqualTo(500);
        assertThat(pd.getTitle()).isEqualTo("Unexpected error");
        // The exception's own message must never reach the caller — it can carry internals.
        assertThat(pd.getDetail()).isEqualTo("An internal server error occurred.");
    }

    @Test
    @DisplayName("the catch-all defers to a 4xx an ErrorResponse declares, rather than flattening it to 500")
    void deferToDeclaredClientStatus() {
        ProblemDetail pd = handler.handleThrowable(
                new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE));

        assertThat(pd.getStatus()).isEqualTo(415);
    }

    @Test
    @DisplayName("a 4xx ResponseStatusException keeps the reason its thrower wrote for the caller (S20-6)")
    void clientErrorKeepsItsReason() {
        ProblemDetail pd = handler.handleThrowable(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "no renames supplied"));

        assertThat(pd.getStatus()).isEqualTo(400);
        assertThat(pd.getTitle()).isEqualTo("Bad Request");
        assertThat(pd.getDetail()).isEqualTo("no renames supplied");
    }

    @Test
    @DisplayName("a declared 5xx keeps its status and stays a server error")
    void deferToDeclaredServerStatus() {
        ProblemDetail pd = handler.handleThrowable(
                new ResponseStatusException(HttpStatus.BAD_GATEWAY));

        assertThat(pd.getStatus()).isEqualTo(502);
    }

    @Test
    @DisplayName("a 5xx's reason stays in the log — it is written for operators, not the caller")
    void serverErrorReasonIsNotExposed() {
        ProblemDetail pd = handler.handleThrowable(new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, "lake at http://aran-lake:8085 refused"));

        assertThat(pd.getStatus()).isEqualTo(500);
        assertThat(pd.getDetail()).isNull();
    }

    @Test
    @DisplayName("a promote refusal is 409 with its code, the classes and the owning projects the caller may see (UI-OD2)")
    void promoteRefusalCarriesClassesAndProjects() {
        ProblemDetail pd = handler.handleStateConflict(new PromoteConflictException(
                PromoteConflictException.REMOVES_OTHER_MODELS_CLASSES, "would remove Author",
                java.util.List.of("http://ex/Author"), java.util.List.of("Bibliography")));

        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getProperties())
                .containsEntry("errorCode", PromoteConflictException.REMOVES_OTHER_MODELS_CLASSES)
                .containsEntry("classes", java.util.List.of("http://ex/Author"))
                .containsEntry("projects", java.util.List.of("Bibliography"));
    }

    @Test
    @DisplayName("any other state conflict is 409 with its code and none of the promote-only properties")
    void otherStateConflictsCarryOnlyTheirCode() {
        ProblemDetail pd = handler.handleStateConflict(
                new StateConflictException("LIVE_DATASET_RENAME", "'authors' is read live"));

        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getDetail()).isEqualTo("'authors' is read live");
        assertThat(pd.getProperties())
                .containsEntry("errorCode", "LIVE_DATASET_RENAME")
                .doesNotContainKeys("classes", "projects");
    }

    @Test
    @DisplayName("a ConflictException is a 409 carrying its sentence and its errorCode (J2-F12, J4-F11, J2-F21, J6-F6)")
    void conflictIsA409WithItsCode() {
        ProblemDetail pd = handler.handleConflict(new ConflictException(
                ConflictException.NAME_TAKEN, "A data product named 'papers' already exists in this project."));

        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getTitle()).isEqualTo("Conflict");
        assertThat(pd.getDetail()).isEqualTo("A data product named 'papers' already exists in this project.");
        assertThat(pd.getProperties()).containsEntry("errorCode", "NAME_TAKEN");
    }

    /** The shape a JPA save's unique violation arrives in: Spring's wrapper over the driver's SQLSTATE. */
    private static DataIntegrityViolationException integrityViolation(String sqlState, String driverMessage) {
        return new DataIntegrityViolationException(
                "could not execute statement; SQL [insert into dataset ...]; constraint [uq_dataset_repository_name]",
                new SQLException(driverMessage, sqlState));
    }

    @Test
    @DisplayName("a unique-key violation is a 409 whose body never quotes the SQL or the constraint")
    void integrityViolationIsA409WithoutInternals() {
        ProblemDetail pd = handler.handleDataIntegrityViolation(integrityViolation("23505",
                "ERROR: duplicate key value violates unique constraint \"uq_dataset_repository_name\""));

        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getTitle()).isEqualTo("Conflict");
        assertThat(pd.getDetail())
                .doesNotContain("uq_dataset_repository_name")
                .doesNotContain("insert into")
                .contains("already exists");
        assertThat(pd.getProperties()).containsEntry("errorCode", "CONFLICT");
    }

    @Test
    @DisplayName("Spring's own DuplicateKeyException (JdbcTemplate's translation) is the same 409")
    void duplicateKeyIsA409() {
        ProblemDetail pd = handler.handleDataIntegrityViolation(new DuplicateKeyException("duplicate key"));

        assertThat(pd.getStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("a NOT NULL, foreign-key or check violation is a server fault — 500, never 'a name already in use'")
    void otherIntegrityViolationsStay500() {
        for (String state : new String[] {"23502", "23503", "23514"}) {
            ProblemDetail pd = handler.handleDataIntegrityViolation(integrityViolation(state, "ERROR: violation"));

            assertThat(pd.getStatus()).as("SQLSTATE %s", state).isEqualTo(500);
            assertThat(pd.getDetail()).isEqualTo("An internal server error occurred.");
            assertThat(pd.getProperties()).as("SQLSTATE %s", state).isNull();
        }
    }

    @Test
    @DisplayName("an integrity violation that says nothing about its cause is not assumed to be a duplicate")
    void causelessViolationIsNotAssumedUnique() {
        assertThat(handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("constraint [uq_dataset_repository_name]")).getStatus())
                .isEqualTo(500);
    }

    @Test
    @DisplayName("an identity account that already exists is a 409 that says why, not a 500 (J7-F2)")
    void existingAccountIsA409() {
        ProblemDetail pd = handler.handleAccountAlreadyExists(
                new IdentityProviderPort.AccountAlreadyExistsException(
                        "an account with that username or email already exists"));

        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getDetail()).contains("already has an account");
        // J7-F7: the likeliest cause of a repeat is a create that timed out in the browser.
        assertThat(pd.getDetail()).contains("If an earlier attempt to add them timed out");
        assertThat(pd.getProperties()).containsEntry("errorCode", "ACCOUNT_EXISTS");
    }

    @Test
    @DisplayName("NoResourceFoundException reaching the catch-all would still be 404, not 500")
    void catchAllAlsoHonoursNoResource() {
        ProblemDetail pd = handler.handleThrowable(
                new NoResourceFoundException(
                        org.springframework.http.HttpMethod.GET, "nope", "/nope"));

        assertThat(pd.getStatus()).isEqualTo(404);
    }
}
