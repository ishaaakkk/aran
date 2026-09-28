package edu.upc.dtak.aran.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.sql.DriverManager;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hexagon, as a failing test rather than a sentence in CONTRIBUTING.
 *
 * <p>Two kinds of rule (D10). <b>Hard</b> rules hold today with zero violations and must keep
 * holding: they are the boundaries the module layout promises, which until now only the jars a
 * module happened to have kept — one dependency line in a build file, and the next
 * {@code import jakarta.persistence} in core compiles. <b>Frozen</b> rules describe
 * debt that is real today and registered (H-5/M-10: Jena, JDBC, Calcite and HTTP in core; ten
 * hand-rolled HTTP clients, S15-14; direct {@code DriverManager} use). Their current
 * violations are recorded in {@code src/test/resources/archunit-store}, which is committed and
 * which an ordinary run may neither create nor write ({@code archunit.properties}). So a new
 * violation fails the build; so does a new frozen rule or an edit to a frozen rule's text, which
 * ArchUnit would otherwise record under the new text, violations and all, and pass; and so does a
 * fixed violation, whose stored line is now obsolete. Each is resolved by moving the store on
 * purpose:
 *
 * <pre>
 * ./gradlew --offline :aran-application:test --tests '*.ArchitectureTest' \
 *     -Darchunit.freeze.store.default.allowStoreUpdate=true
 * </pre>
 *
 * <p>then reading the store's diff and committing it — that diff is the ratchet moving, and a
 * line added to it is debt someone chose to accept. An edited rule's old text keeps its line in
 * {@code stored.rules} and its violation file; delete both by hand.
 *
 * <p>It lives in aran-application because only this module's test classpath holds every other
 * module (operators and adapters arrive {@code runtimeOnly}); a rule about how modules depend on
 * each other has to see both ends. The DuckDB-URL and {@code DriverManager.getConnection} rules
 * stay the source scans in aran-core ({@code DuckDbSessionArchitectureTest},
 * {@code SourceDatabaseConnectionArchitectureTest}): a string literal is not a bytecode
 * dependency, so ArchUnit cannot see the first.
 */
class ArchitectureTest {

    private static final String CORE = "edu.upc.dtak.aran.core..";
    private static final String APP = "edu.upc.dtak.aran.app..";
    private static final String ADAPTERS = "edu.upc.dtak.aran.adapter..";
    private static final String OPERATORS = "edu.upc.dtak.aran.operators..";

    /** One package per Gradle module, so an import that silently misses a module fails loudly. */
    private static final List<String> MODULE_PACKAGES = List.of(
            "edu.upc.dtak.aran.core",
            "edu.upc.dtak.aran.app",
            "edu.upc.dtak.aran.adapter.persistence",
            "edu.upc.dtak.aran.adapter.iceberg",
            "edu.upc.dtak.aran.adapter.license",
            "edu.upc.dtak.aran.operators.bootstrapping",
            "edu.upc.dtak.aran.operators.profiling",
            "edu.upc.dtak.aran.operators.joindiscovery",
            "edu.upc.dtak.aran.operators.schemaintegration",
            "edu.upc.dtak.aran.operators.constraintdiscovery",
            "edu.upc.dtak.aran.operators.queryengine");

    private static JavaClasses classes;

    @BeforeAll
    static void importMainClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("edu.upc.dtak.aran");
    }

    @Test
    @DisplayName("the import sees every module's main classes, and no test classes")
    void importCoversEveryModule() {
        Set<String> packages = classes.stream()
                .map(JavaClass::getPackageName)
                .collect(Collectors.toSet());
        // A classpath or import-option slip that drops a module would make every rule below pass
        // over nothing for it — the one way an architecture test fails silently.
        for (String module : MODULE_PACKAGES) {
            assertThat(packages)
                    .as("classes imported from module package " + module)
                    .anyMatch(p -> p.equals(module) || p.startsWith(module + "."));
        }
        assertThat(classes.stream().map(JavaClass::getName))
                .as("test classes must not be analysed as production code")
                .noneMatch(name -> name.equals(ArchitectureTest.class.getName()));
    }

    @Test
    @DisplayName("the committed archunit.properties keeps the frozen store read-only")
    void frozenStoreIsReadOnlyAsCommitted() throws IOException {
        // Read from the file, not from ArchConfiguration: the deliberate re-freeze overrides the
        // key with a system property, and that run must not fail here. What this pins is that
        // nobody re-freezes by flipping the file and then commits the flip.
        Properties committed = new Properties();
        try (InputStream in = ArchitectureTest.class.getResourceAsStream("/archunit.properties")) {
            assertThat(in).as("archunit.properties on the test classpath").isNotNull();
            committed.load(in);
        }
        assertThat(committed.getProperty("freeze.store.default.allowStoreCreation")).isEqualTo("false");
        assertThat(committed.getProperty("freeze.store.default.allowStoreUpdate")).isEqualTo("false");
    }

    // ---- hard rules: zero violations today, and it stays that way ---------------------------

    @Test
    @DisplayName("aran-core imports no Spring Web, JPA, Hibernate, Spring Security, Spring Data or servlet type")
    void coreIsTransportAndPersistenceAgnostic() {
        noClasses().that().resideInAPackage(CORE)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "org.springframework.data..",
                        "org.springframework.security..",
                        "jakarta.servlet..")
                .because("core is the domain and its ports; HTTP and persistence are adapters")
                .check(classes);
    }

    @Test
    @DisplayName("aran-core imports no org.duckdb type (H-6)")
    void coreNamesNoDuckDbType() {
        // Core still opens DuckDB — through java.sql and DuckDbSession, which the java.sql freeze
        // below counts — but it names no DuckDB class, and a new one would make H-6 bigger.
        coreMustNotUse("org.duckdb..").check(classes);
    }

    @Test
    @DisplayName("aran-core depends on no adapter, operator or application class")
    void coreDependsOnNothingOutward() {
        noClasses().that().resideInAPackage(CORE)
                .should().dependOnClassesThat().resideInAnyPackage(ADAPTERS, OPERATORS, APP)
                .because("adapters and operators plug into core's ports, never the reverse")
                .check(classes);
    }

    @Test
    @DisplayName("no operator module depends on another operator module")
    void operatorsAreIndependent() {
        slices().matching("edu.upc.dtak.aran.operators.(*)..")
                .should().notDependOnEachOther()
                .because("operators meet only through core's operator SPI and orchestrator")
                .check(classes);
    }

    @Test
    @DisplayName("adapters depend on no operator and on no application class")
    void adaptersStayAdapters() {
        noClasses().that().resideInAPackage(ADAPTERS)
                .should().dependOnClassesThat().resideInAnyPackage(OPERATORS, APP)
                .check(classes);
    }

    @Test
    @DisplayName("operators depend on no adapter and on no application class")
    void operatorsSeeOnlyCore() {
        noClasses().that().resideInAPackage(OPERATORS)
                .should().dependOnClassesThat().resideInAnyPackage(ADAPTERS, APP)
                .check(classes);
    }

    // ---- frozen rules: registered debt that may shrink and must not grow --------------------

    @Test
    @DisplayName("frozen: aran-core's Jena dependencies do not grow (H-5)")
    void coreJenaIsFrozen() {
        freeze(coreMustNotUse("org.apache.jena..")).check(classes);
    }

    @Test
    @DisplayName("frozen: aran-core's java.sql dependencies do not grow (M-10)")
    void coreJdbcIsFrozen() {
        freeze(coreMustNotUse("java.sql..")).check(classes);
    }

    @Test
    @DisplayName("frozen: aran-core's Calcite dependencies do not grow")
    void coreCalciteIsFrozen() {
        freeze(coreMustNotUse("org.apache.calcite..")).check(classes);
    }

    @Test
    @DisplayName("frozen: aran-core's java.net.http dependencies do not grow (M-10)")
    void coreHttpIsFrozen() {
        freeze(coreMustNotUse("java.net.http..")).check(classes);
    }

    @Test
    @DisplayName("frozen: no new hand-rolled java.net.http.HttpClient (S15-14)")
    void httpClientConstructionIsFrozen() {
        freeze(noClasses()
                .should().callMethod(HttpClient.class, "newBuilder")
                .orShould().callMethod(HttpClient.class, "newHttpClient")
                .because("each site picks its own timeouts and redirect policy; the boundaries"
                        + " sweep replaces them with one bounded helper"))
                .check(classes);
    }

    @Test
    @DisplayName("frozen: no new direct use of java.sql.DriverManager")
    void driverManagerIsFrozen() {
        freeze(noClasses()
                .should().dependOnClassesThat().belongToAnyOf(DriverManager.class)
                .because("source connections come from DriverDownloader and DuckDB sessions from"
                        + " DuckDbSession; DriverManager's registry and login timeout are JVM-global"))
                .check(classes);
    }

    private static ArchRule coreMustNotUse(String packageIdentifier) {
        return noClasses().that().resideInAPackage(CORE)
                .should().dependOnClassesThat().resideInAPackage(packageIdentifier);
    }
}
