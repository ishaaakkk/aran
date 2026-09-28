package edu.upc.dtak.aran.operators.queryengine.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Read-or-rewrite a committed golden file.
 *
 * <p>Two corpora use this, and they deliberately keep <em>separate</em> update flags rather than
 * sharing one. {@code -Daran.plan.goldens.update=true} rewrites whatever corpus it is pointed at,
 * so a single flag would mean that accepting one intended plan change silently rewrites the other
 * corpus in the same act — and {@code PlanCorpusTest}'s javadoc calls that diff "the review
 * artifact". Two flags, two deliberate acts.
 */
final class Goldens {

    private Goldens() {}

    /**
     * Assert {@code actual} equals the golden at {@code dir/name.txt}, or rewrite it when the
     * named system property is set.
     *
     * @param updateProperty e.g. {@code aran.plan.goldens.update}; the root build forwards any
     *                       {@code -Daran.*} into the test JVM, which is what makes this work
     *                       from the command line at all
     */
    static void assertOrUpdate(Path dir, String name, String actual, String updateProperty)
            throws IOException {
        Path golden = dir.resolve(name + ".txt");
        if (Boolean.getBoolean(updateProperty)) {
            Files.createDirectories(dir);
            Files.writeString(golden, actual);
            return;
        }
        assertThat(golden)
                .as("golden exists — run once with -D%s=true", updateProperty)
                .exists();
        assertThat(actual).isEqualTo(Files.readString(golden));
    }
}
