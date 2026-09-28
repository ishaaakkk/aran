package edu.upc.dtak.aran.core.domain;

import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * A source reached by running a Singer tap (T6, audit IMPORTS-6).
 *
 * <p>Its own kind rather than a mechanism bolted onto a generic web-API kind (one existed, with no
 * connector, until D1 deleted it): a tap is a connection to a
 * SaaS API through a program the operator installed, and the two halves of that — <em>which</em>
 * program, and the settings it is given — have different owners. The program comes from
 * {@code SingerTapCatalog} (deployment configuration; a user can only name an id from it), the
 * settings come from the user. Modelling it as a Web API would have hidden that split behind a
 * base URL nothing dials.
 *
 * @param tapId      an id the deployment's {@code SingerTapCatalog} declares. Validated at create,
 *                   update and test — never trusted from the row alone, because the catalogue is
 *                   configuration and can be narrowed after a repository was registered
 * @param configJson the user's settings as a JSON object, stored encrypted: the tap declares which
 *                   keys are secret, so the column has to assume the whole map is
 */
@NullMarked
public record SingerTapRepository(
        UUID id,
        UUID projectId,
        UUID tenantId,
        String name,
        String description,
        String tapId,
        String configJson
) implements DataRepository {

    public SingerTapRepository {
        if (tapId == null || tapId.isBlank()) {
            throw new IllegalArgumentException("a tap repository must name a tap id");
        }
        // An absent config is "the tap needs no settings", which is a real case (a public feed).
        // Normalised here so every reader parses an object rather than branching on null.
        configJson = (configJson == null || configJson.isBlank()) ? "{}" : configJson;
    }

    @Override
    public RepositoryType type() {
        return RepositoryType.SINGER_TAP;
    }
}
