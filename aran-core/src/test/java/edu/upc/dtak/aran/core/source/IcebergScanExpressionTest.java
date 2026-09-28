package edu.upc.dtak.aran.core.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IcebergScanExpressionTest {

    @Test
    @DisplayName("emits iceberg_scan over the metadata location, SQL-escaped")
    void emitsScan() {
        assertThat(IcebergScanExpression.forMetadataLocation(
                "s3://lake/warehouse/db/t/metadata/00001.metadata.json"))
                .isEqualTo("iceberg_scan('s3://lake/warehouse/db/t/metadata/00001.metadata.json')");
        // A quote in a location must not be able to close the literal — same rule as every other
        // resolver-built read expression.
        assertThat(IcebergScanExpression.forMetadataLocation("s3://b/it's.json"))
                .isEqualTo("iceberg_scan('s3://b/it''s.json')");
    }

    @Test
    @DisplayName("a blank metadata location is refused, not turned into iceberg_scan('')")
    void blankLocationRefused() {
        assertThatThrownBy(() -> IcebergScanExpression.forMetadataLocation(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("s3Scope is the bucket prefix for s3/s3a locations and null for anything else")
    void s3Scope() {
        assertThat(IcebergScanExpression.s3Scope("s3://lake/db/t/metadata/m.json"))
                .isEqualTo("s3://lake");
        assertThat(IcebergScanExpression.s3Scope("s3a://lake/db/t/metadata/m.json"))
                .isEqualTo("s3://lake");
        // An http(s) location (another Aran's produce surface) has no S3 secret to scope.
        assertThat(IcebergScanExpression.s3Scope("https://aran.example/api/iceberg/files/x.json"))
                .isNull();
        assertThat(IcebergScanExpression.s3Scope("s3://")).isNull();
    }
}
