package edu.upc.dtak.aran.app;

import edu.upc.dtak.aran.core.util.DuckDbSession;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Runs inside {@link ProductImageRuntimeUserTest}'s container as the image's runtime user, never
 * in the test JVM. It opens the product's own session ({@link DuckDbSession#open()}) and installs
 * an extension from a local repository path that holds nothing, so no network is needed. The
 * install fails either way. What matters is the error: with no usable home directory DuckDB fails
 * before it looks for the extension, and with one it fails on the missing file.
 */
final class DuckDbExtensionInstallProbe {

    private DuckDbExtensionInstallProbe() {}

    public static void main(String[] args) {
        System.out.println("HOME=" + System.getenv("HOME"));
        try (Connection conn = DuckDbSession.open(); Statement st = conn.createStatement()) {
            st.execute("INSTALL postgres FROM '" + args[0] + "'");
            System.out.println("INSTALL: ok");
        } catch (SQLException e) {
            System.out.println("INSTALL: " + e.getMessage());
        }
    }
}
