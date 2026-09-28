package edu.upc.dtak.aran.app.iceberg;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The one derivation of the absolute base every produce-side Iceberg file location is minted
 * under. One component rather than a private method in each controller because the artifact cache
 * keys on the result: the catalog controller and the file controller minting different bases would
 * split the cache and, worse, regenerate manifests under fresh random names — turning every file
 * read into a 404-and-reload loop.
 */
@Component
@NullMarked
public class IcebergFilesBaseUrl {

    /** Absolute external origin override (e.g. {@code https://aran.example}) for deployments
     * behind a proxy whose origin the request does not carry; blank derives from the request. */
    private final String configuredBaseUrl;

    public IcebergFilesBaseUrl(@Value("${aran.iceberg.public-base-url:}") String configuredBaseUrl) {
        this.configuredBaseUrl = configuredBaseUrl;
    }

    public String forRequest(HttpServletRequest request) {
        String origin = configuredBaseUrl.isBlank()
                ? ServletUriComponentsBuilder.fromRequestUri(request)
                        .replacePath(null)
                        .build()
                        .toUriString()
                : configuredBaseUrl.replaceAll("/+$", "");
        return origin + "/api/iceberg/files";
    }
}
