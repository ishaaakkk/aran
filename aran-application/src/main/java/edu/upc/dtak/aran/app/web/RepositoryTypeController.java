package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.core.domain.RepositoryType;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the repository kinds this build knows, by the names the rest of the API uses.
 *
 * <p>It used to publish a {@code connectorAvailable} flag per kind as well, so the registration
 * form could mark a kind "connect &amp; test only" before the user committed. Every kind that ships
 * has had a connector since the three that did not were deleted (D1, Flyway V44), so the flag
 * could only say {@code true}; it went with the form state it drove (D1 PR 2). A future kind
 * arrives with its connector rather than bringing the flag back.
 *
 * <p>Deliberately not returning display labels. Naming a kind in a dropdown is presentation and
 * stays in the frontend.
 */
@RestController
@RequestMapping("/api/repository-types")
public class RepositoryTypeController {

    /**
     * Every repository kind.
     *
     * <p>Authenticated like the rest of {@code /api/**}, though it discloses nothing tenant-specific
     * — it is a compile-time property of the build, identical for every caller.
     */
    @GetMapping
    public List<RepositoryTypeResponse> listRepositoryTypes() {
        return Arrays.stream(RepositoryType.values())
                .map(t -> new RepositoryTypeResponse(t.name()))
                .toList();
    }

    /**
     * @param type the {@link RepositoryType} constant name, as used everywhere else on the wire
     */
    public record RepositoryTypeResponse(String type) {
    }
}
