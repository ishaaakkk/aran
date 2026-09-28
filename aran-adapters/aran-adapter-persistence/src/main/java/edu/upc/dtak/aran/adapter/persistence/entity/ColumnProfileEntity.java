package edu.upc.dtak.aran.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity mapping FREYJA profiling metrics for a single column.
 */
@Entity
@Table(
        name = "column_profile",
        // Mirrors Flyway V36, which owns the real definition. The NULLS NOT DISTINCT clause that
        // makes the key bite for SNAPSHOT/HARVESTED rows (both leave class_iri NULL) has no JPA
        // spelling, so this annotation is necessarily an approximation — see V36 for why it
        // matters (ddl-auto: validate never checks unique constraints either way).
        uniqueConstraints = @UniqueConstraint(
                name = "uq_column_profile_identity",
                columnNames = {"dataset_id", "subject", "class_iri", "column_name"}
        )
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ColumnProfileEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dataset_id", nullable = false)
    private DatasetEntity dataset;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "column_name", nullable = false)
    private String columnName;

    @Column(name = "column_index", nullable = false)
    private int columnIndex;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    private long cardinality;
    
    private Double entropy;
    
    private double uniqueness;
    
    private double incompleteness;

    @Column(name = "frequency_avg")
    private double frequencyAvg;

    @Column(name = "frequency_min")
    private long frequencyMin;

    @Column(name = "frequency_max")
    private long frequencyMax;

    @Column(name = "frequency_sd")
    private double frequencySd;

    @Column(name = "frequency_iqr")
    private double frequencyIqr;

    @Column(name = "val_pct_min")
    private double valPctMin;

    @Column(name = "val_pct_max")
    private double valPctMax;

    @Column(name = "val_pct_std")
    private double valPctStd;

    private double constancy;

    @Column(name = "len_max_word")
    private int lenMaxWord;

    @Column(name = "len_min_word")
    private int lenMinWord;

    @Column(name = "len_avg_word")
    private double lenAvgWord;

    @Column(name = "number_words")
    private long numberWords;

    @Column(name = "words_cnt_max")
    private int wordsCntMax;

    @Column(name = "words_cnt_min")
    private int wordsCntMin;

    @Column(name = "words_cnt_avg")
    private double wordsCntAvg;

    @Column(name = "words_cnt_sd")
    private double wordsCntSd;

    @Column(name = "first_word")
    private String firstWord;

    @Column(name = "last_word")
    private String lastWord;

    @Column(name = "is_empty")
    private boolean isEmpty;

    @Column(name = "is_binary")
    private boolean isBinary;

    @Column(name = "common_values", columnDefinition = "TEXT")
    private String commonValues;

    @Column(name = "common_values_soundex", columnDefinition = "TEXT")
    private String commonValuesSoundex;

    // V31 (Phase 3B): what this row profiled — "SNAPSHOT" or "WRAPPER_OUTPUT". NOT NULL with a
    // DEFAULT 'SNAPSHOT' at the DB level (every pre-3B row profiled a snapshot); see
    // ColumnProfile's javadoc for the full discriminator contract.
    @Column(name = "subject", nullable = false, length = 24)
    private String subject;

    // The source class this row profiles when subject = "WRAPPER_OUTPUT"; null for SNAPSHOT rows.
    @Column(name = "class_iri", length = 512)
    private String classIri;

    // V45: freyja's bottom-k value-hash sample, comma-joined like common_values (hex never holds a
    // comma). NULL on a row profiled before it existed — see ColumnProfile.valueHashSample.
    @Column(name = "value_hash_sample", columnDefinition = "TEXT")
    private String valueHashSample;
}
