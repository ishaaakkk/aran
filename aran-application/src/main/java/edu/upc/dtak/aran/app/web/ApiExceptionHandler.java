package edu.upc.dtak.aran.app.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import edu.upc.dtak.aran.core.domain.IdentityProviderPort;
import edu.upc.dtak.aran.core.licensing.LicenseExpiredException;
import edu.upc.dtak.aran.core.licensing.LicenseLimitExceededException;
import edu.upc.dtak.aran.core.licensing.LicenseRequiredException;
import edu.upc.dtak.aran.core.service.AccessDeniedException;
import edu.upc.dtak.aran.core.service.ConflictException;
import edu.upc.dtak.aran.core.service.PreviewFailedException;
import edu.upc.dtak.aran.core.service.PromoteConflictException;
import edu.upc.dtak.aran.core.service.ResourceNotFoundException;
import edu.upc.dtak.aran.core.service.StateConflictException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.exc.InvalidFormatException;

/**
 * Maps exceptions thrown by the core service layer to HTTP responses.
 *
 * <p>Uses {@link ProblemDetail} (RFC 7807) — the standard, structured error
 * format Spring Framework 6+/Boot supports natively, rather than an ad-hoc
 * error JSON shape.
 *
 * <p>The mapping reflects the deliberate distinction in the core's exception
 * types:
 * <ul>
 *   <li>{@link ResourceNotFoundException} → <b>404</b>. Note the core throws
 *       this both for genuinely missing resources and for resources the caller
 *       may not see, so a 404 here does not confirm non-existence — by design,
 *       to avoid leaking which ids exist.</li>
 *   <li>{@link AccessDeniedException} → <b>403</b>. Used only where saying
 *       "not allowed" leaks nothing (e.g. role-insufficient, no membership in
 *       the tenant).</li>
 *   <li>Bean-validation failures on request DTOs → <b>400</b>.</li>
 *   <li>{@link ConflictException}, a unique-key violation and an identity account that already
 *       exists → <b>409</b>: the request was fine, the current state refuses it. Any other
 *       integrity violation stays a <b>500</b>.</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Resource not found");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        pd.setTitle("Access denied");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    /**
     * A preview that cannot be produced fails loudly, carrying the driver's own
     * message. 502 is the honest code: Aran's request was fine; the upstream
     * source (a JDBC driver, the lake, the file system, DuckDB) is what refused.
     */
    @ExceptionHandler(PreviewFailedException.class)
    ProblemDetail handlePreviewFailed(PreviewFailedException ex) {
        log.warn("Preview failed: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        pd.setTitle("Preview failed");
        pd.setDetail(ex.getMessage());
        pd.setProperty("errorCode", PreviewFailedException.ERROR_CODE);
        return pd;
    }

    /**
     * A table import against a repository type/engine the import pipeline does
     * not serve. 400: the request itself named an unservable source.
     */
    @ExceptionHandler(edu.upc.dtak.aran.core.service.UnsupportedImportSourceException.class)
    ProblemDetail handleUnsupportedImportSource(edu.upc.dtak.aran.core.service.UnsupportedImportSourceException ex) {
        log.warn("Unsupported import source: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Unsupported import source");
        pd.setDetail(ex.getMessage());
        pd.setProperty("errorCode", edu.upc.dtak.aran.core.service.UnsupportedImportSourceException.ERROR_CODE);
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Invalid request");
        // Surface the first field error; the full set is available on ex if needed.
        var fieldError = ex.getBindingResult().getFieldError();
        pd.setDetail(fieldError != null
                ? fieldError.getField() + ": " + fieldError.getDefaultMessage()
                : "request validation failed");
        return pd;
    }

    /**
     * A request body Jackson could not deserialize — malformed JSON, or (the common case) a
     * wire-format enum value the DTO doesn't recognise — is a 400, not a 500 (V48: posting
     * {@code {"kind":"DATATYPE"}} against the lower-case wire enum in {@link
     * edu.upc.dtak.aran.app.web.dto.ModelDtos.MappingKindDto} answered 500 "Unexpected error"
     * while the useful diagnosis — which field, which values are accepted — sat only in the
     * log). Without this handler {@link HttpMessageNotReadableException} doesn't declare its own
     * status ({@code ErrorResponse}), so it fell through to {@link #handleThrowable}.
     *
     * <p>The detail is rebuilt from Jackson's own structured exception data, never
     * {@code ex.getMessage()} or the exception chain verbatim: Jackson's message embeds a
     * {@code JsonLocation} that can carry a fragment of the source JSON, and the request body is
     * not safe to hand back to a caller who does not expect it to reappear in a response.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Malformed request body");
        pd.setDetail(safeParseSummary(ex.getCause()));
        return pd;
    }

    /**
     * Names the offending field and, for an enum, the accepted wire values — nothing else.
     * Falls back to a generic message for anything that isn't a Jackson {@link
     * InvalidFormatException} with a resolvable field (e.g. a syntactically broken body, where
     * Jackson has no field to name at all).
     */
    private static String safeParseSummary(Throwable cause) {
        if (cause instanceof InvalidFormatException ife && !ife.getPath().isEmpty()) {
            String field = ife.getPath().get(ife.getPath().size() - 1).getPropertyName();
            Class<?> targetType = ife.getTargetType();
            if (field != null && targetType != null && targetType.isEnum()) {
                return "Field '" + field + "' has an invalid value; accepted values: "
                        + acceptedEnumValues(targetType) + ".";
            }
            if (field != null) {
                return "Field '" + field + "' has a value that does not match its expected type.";
            }
        }
        return "The request body could not be parsed as valid JSON.";
    }

    /**
     * The wire-format name Jackson accepts for each constant — its {@code @JsonProperty} value
     * where the enum declares one (as {@link edu.upc.dtak.aran.app.web.dto.ModelDtos.MappingKindDto}
     * does), the Java constant name otherwise.
     */
    private static String acceptedEnumValues(Class<?> enumType) {
        return Arrays.stream(enumType.getEnumConstants())
                .map(constant -> wireName(enumType, (Enum<?>) constant))
                .collect(Collectors.joining(", "));
    }

    private static String wireName(Class<?> enumType, Enum<?> constant) {
        try {
            JsonProperty ann = enumType.getField(constant.name()).getAnnotation(JsonProperty.class);
            return ann != null ? ann.value() : constant.toString();
        } catch (NoSuchFieldException e) {
            return constant.toString();
        }
    }

    /**
     * A well-formed request refused by the state of what it names — a promote that would remove
     * another project's classes (UI-OD2), a superseded draft, a rename of a dataset read live
     * (UI-OD8). 409, never the generic 500 a bare {@link IllegalStateException} used to reach:
     * the sentence is written for the caller and says what to do instead.
     */
    @ExceptionHandler(StateConflictException.class)
    ProblemDetail handleStateConflict(StateConflictException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Conflict");
        pd.setDetail(ex.getMessage());
        pd.setProperty("errorCode", ex.errorCode());
        if (ex instanceof PromoteConflictException promote) {
            if (!promote.classIris().isEmpty()) {
                pd.setProperty("classes", promote.classIris());
            }
            if (!promote.ownerProjects().isEmpty()) {
                pd.setProperty("projects", promote.ownerProjects());
            }
        }
        return pd;
    }

    // --- Conflicts: 409 (V48) ---------------------------------------------------
    //
    // A 409 tells the caller the request was fine and the STATE disagrees: look again, and it
    // may make sense after a change. Before these, every one of the cases below answered a
    // generic 500 "An internal server error occurred." (UI audit J2-F12, J2-F21, J4-F11, J6-F6,
    // J7-F2), which reads as "Aran is broken" when the truth is "this already exists" or "that
    // already happened" — and a user who retries a 500 makes the second case worse.

    @ExceptionHandler(ConflictException.class)
    ProblemDetail handleConflict(ConflictException ex) {
        log.debug("Conflict {}: {}", ex.errorCode(), ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Conflict");
        pd.setDetail(ex.getMessage());
        pd.setProperty("errorCode", ex.errorCode());
        return pd;
    }

    /**
     * The database refused a write that collides with an existing row — a unique key, most often a
     * name. The service-level pre-checks catch the ordinary case with a precise sentence; this is
     * the backstop for the race two concurrent requests can still win, and for the tables no
     * pre-check covers yet.
     *
     * <p>The detail is generic on purpose: the exception's message quotes the SQL statement and
     * the constraint name, which are internals, and the caller cannot be told which field
     * collided without them.
     *
     * <p><b>Only a unique violation is a 409.</b> A NOT NULL, a check or a foreign key collides
     * with no existing row, so "this already exists" would be false — and those are almost always
     * a server bug in a write path, which must stay a 500 in monitoring rather than read to the
     * user as a name clash. They go to the catch-all.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        if (!isUniqueViolation(ex)) {
            return handleThrowable(ex);
        }
        log.warn("Unique-key violation answered as 409: {}", ex.getMostSpecificCause().getMessage());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Conflict");
        pd.setDetail("This conflicts with something that already exists — most often a name "
                + "already in use. Reload to see the current state before trying again.");
        pd.setProperty("errorCode", "CONFLICT");
        return pd;
    }

    /** PostgreSQL's SQLSTATE for {@code unique_violation}. */
    private static final String UNIQUE_VIOLATION = "23505";

    /**
     * Whether an integrity violation is a duplicate key. Spring's JDBC translation says so by type
     * ({@link DuplicateKeyException}); through JPA it arrives as a plain
     * {@link DataIntegrityViolationException} over Hibernate's exception over the driver's
     * {@link SQLException}, so the SQLSTATE on the cause chain is the one reliable signal.
     */
    static boolean isUniqueViolation(Throwable ex) {
        Throwable t = ex;
        for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause()) {
            if (t instanceof DuplicateKeyException) {
                return true;
            }
            if (t instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The identity provider already holds an account for that email (J7-F2): typically someone
     * removed from this organization earlier — removal disables the sign-in rather than deleting
     * it — or a person who belongs to another organization (V23). Before this, re-adding them
     * answered a 500 forever. Restoring a removed member is its own feature (UI-OD9), not yet
     * built, so the sentence says so rather than inviting a retry that cannot succeed.
     *
     * <p>A current member of the caller's organization never reaches this: that case is refused
     * first, as {@code ALREADY_A_MEMBER} ({@code TenantMemberService.requireNotAlreadyMember}).
     * What can still reach it from a timed-out create is an attempt that is still finishing, so
     * the sentence says to reload before concluding anything (J7-F7).
     */
    @ExceptionHandler(IdentityProviderPort.AccountAlreadyExistsException.class)
    ProblemDetail handleAccountAlreadyExists(IdentityProviderPort.AccountAlreadyExistsException ex) {
        log.info("Member creation refused, account exists: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Conflict");
        pd.setDetail("That email already has an account. If an earlier attempt to add them timed "
                + "out, it may still be finishing — reload the list of members. Otherwise the "
                + "account belongs to someone removed from this organization earlier, or to a "
                + "member of another organization; restoring a removed member is not available "
                + "yet.");
        pd.setProperty("errorCode", "ACCOUNT_EXISTS");
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        // Domain-record invariant violations (e.g. blank name) land here.
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Invalid request");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    // --- Licensing — three distinct 402 cases the frontend can act on -----
    //
    // 402 (Payment Required) is the right code: the request is well-formed
    // and the caller is authenticated and authorised; it fails for
    // commercial reasons. Splitting the three at the type level lets the
    // frontend show different UI for each ("no license — contact admin",
    // "license expired — read-only", "upgrade your plan").

    @ExceptionHandler(LicenseRequiredException.class)
    ProblemDetail handleLicenseRequired(LicenseRequiredException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.PAYMENT_REQUIRED);
        pd.setTitle("License required");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(LicenseExpiredException.class)
    ProblemDetail handleLicenseExpired(LicenseExpiredException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.PAYMENT_REQUIRED);
        pd.setTitle("License expired");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(LicenseLimitExceededException.class)
    ProblemDetail handleLicenseLimit(LicenseLimitExceededException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.PAYMENT_REQUIRED);
        pd.setTitle("License limit exceeded");
        pd.setDetail(ex.getMessage());
        // Structured fields so the frontend can render "8 / 10 used" badges
        // and "you've hit your project limit" prompts without parsing the
        // detail string.
        pd.setProperty("limit", ex.limitName());
        pd.setProperty("currentUsage", ex.currentUsage());
        pd.setProperty("maxAllowed", ex.limit());
        return pd;
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ProblemDetail handleSpringAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        pd.setTitle("Access denied");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    /**
     * A request that matched no handler is a 404, not a 500 (finding F26).
     *
     * <p>Spring raises {@link NoResourceFoundException} for an unmatched path. With only the
     * {@code Throwable} catch-all below, it landed there: every unmatched route answered
     * <b>500 with a stack trace</b>, reproduced live against {@code /actuator/health}, and it
     * cost a real debugging session because a typo in a URL looked like a server fault.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail handleNoResourceFound(NoResourceFoundException ex) {
        log.debug("No handler for {}", ex.getResourcePath());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Resource not found");
        pd.setDetail("No endpoint " + ex.getHttpMethod() + " " + ex.getResourcePath() + ".");
        return pd;
    }

    /**
     * Last resort. Anything that reaches here is unrecognised, so the body stays generic —
     * the detail is never the exception's message, which can carry internals.
     *
     * <p><b>Defers to {@link ErrorResponse} when the exception declares its own status.</b>
     * Spring's own web exceptions (unsupported method, unreadable body, unsupported media
     * type, {@code ResponseStatusException}) all implement it and all mean "the request was
     * wrong", not "the server broke".
     *
     * <p>For a 4xx the exception's own detail is kept — for a {@code ResponseStatusException}
     * that is the reason a controller wrote for the caller, and without it a drift check's
     * "no renames supplied" reached the client as a bare "Bad Request" (S20-6). A 5xx keeps the
     * generic body: its reason is written for the log, not the caller.
     */
    @ExceptionHandler(Throwable.class)
    ProblemDetail handleThrowable(Throwable ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            if (status.is4xxClientError()) {
                log.debug("Client error {}: {}", status.value(), ex.toString());
            } else {
                log.error("Server error {} raised as ErrorResponse", status.value(), ex);
            }
            ProblemDetail pd = ProblemDetail.forStatus(status);
            pd.setTitle(status.getReasonPhrase());
            if (status.is4xxClientError()) {
                pd.setDetail(errorResponse.getBody().getDetail());
            }
            return pd;
        }
        log.error("Unhandled exception occurred", ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Unexpected error");
        pd.setDetail("An internal server error occurred.");
        return pd;
    }
}
