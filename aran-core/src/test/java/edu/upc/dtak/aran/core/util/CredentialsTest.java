package edu.upc.dtak.aran.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Credentials.scrub — every libpq password spelling is masked, the diagnostic is kept")
class CredentialsTest {

    @Test
    @DisplayName("DuckDB's own refusal line loses the password and keeps host, database and cause")
    void duckDbsRefusalLine() {
        String line = "IO Error: Unable to connect to Postgres at \"dbname='srcdb' user='u'"
                + " password='hunter2' host='10.0.0.5' port=5432\": connection refused";

        assertThat(Credentials.scrub(line))
                .doesNotContain("hunter2")
                .contains("password=***")
                .contains("dbname='srcdb'", "host='10.0.0.5'", "connection refused");
    }

    // `tail` is what a whitespace-bounded scrub leaves behind: the rest of a quoted value after its
    // first space or an escaped quote. The value spellings are PostgresEngine.dsn's.
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "a space and an escaped quote | ... password='p w\\'d' host='h' ...      | w\\'d",
        "a trailing escaped backslash | ... password='p w\\\\' host='h' ...     | w\\\\",
        "a bare value, any case       | ... PASSWORD = bare-secret host=h ...    | bare-secret",
        "pwd                          | ... pwd=bare2 host=h ...                 | bare2",
        "passwd                       | ... passwd='a b' host=h ...              | b'",
        "an unterminated quoted value | ... host=h password='unterminated tail   | tail",
    })
    void everySpelling(String name, String text, String tail) {
        assertThat(Credentials.scrub(text))
                .doesNotContain(tail)
                .contains("***")
                .contains("host=");
    }

    @Test
    @DisplayName("the SQL-literal spelling an error can quote back, every quote doubled, is masked"
            + " whole")
    void sqlDoubledSpelling() {
        String line = "LINE 1: SELECT * FROM postgres_scan('dbname=''db'' password=''hunter2"
                + " x\\''y\\\\'' host=''10.0.0.5'' port=5432', 'public', 't')";

        assertThat(Credentials.scrub(line))
                .doesNotContain("hunter2")
                .doesNotContain("y\\\\")
                .contains("password=***")
                .contains("host=''10.0.0.5''");
    }

    @Test
    @DisplayName("text without a credential is returned unchanged, $ and backslashes included")
    void noCredential() {
        String text = "Binder Error: column \"a$1\" not found in C:\\x — passwords are not here";
        assertThat(Credentials.scrub(text)).isEqualTo(text);
    }
}
