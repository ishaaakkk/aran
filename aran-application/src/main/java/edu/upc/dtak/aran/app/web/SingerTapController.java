package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.core.source.SingerTapCatalog;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes which Singer taps this deployment allows, so a registration form can offer them.
 *
 * <p>Deliberately <em>not</em> publishing the command. It is an absolute path on the server, so
 * it tells a caller where things are installed and confirms whether a guessed path exists —
 * neither of which a user needs in order to choose a tap from a list. The catalogue is
 * configuration a user cannot change, and this endpoint is the read-only view of the part they
 * legitimately need: what exists, and which settings it wants.
 */
@RestController
@RequestMapping("/api/singer-taps")
public class SingerTapController {

    private final SingerTapCatalog catalog;

    public SingerTapController(SingerTapCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * The allowed taps. Empty when the deployment has configured none, which is the default and
     * means the feature is off — the form should say so rather than show an empty dropdown.
     */
    @GetMapping
    public List<SingerTapResponse> listTaps() {
        return catalog.all().stream()
                .map(t -> new SingerTapResponse(
                        t.id(), t.label(), t.configKeys().stream().sorted().toList(), t.secretKeys()))
                .toList();
    }

    /**
     * @param configKeys the settings this tap accepts, sorted so a form renders them stably
     * @param secretKeys which of those hold credentials, so a form can mask the input and avoid
     *                   echoing the value back
     */
    public record SingerTapResponse(
            String id,
            String label,
            List<String> configKeys,
            Set<String> secretKeys) {
    }
}
