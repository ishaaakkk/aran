--  V10__create_column_profile_table.sql  —  Relational Table for Dataset Column Profiles
--  Used to cache computed FREYJA profiling metrics with proper data types for Calcite query optimizer and join discovery

CREATE TABLE column_profile (
    id UUID PRIMARY KEY,
    dataset_id UUID NOT NULL REFERENCES dataset(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL,
    column_name VARCHAR(255) NOT NULL,
    column_index INT NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    
    cardinality BIGINT,
    entropy DOUBLE PRECISION,
    uniqueness DOUBLE PRECISION,
    incompleteness DOUBLE PRECISION,
    
    frequency_avg DOUBLE PRECISION,
    frequency_min BIGINT,
    frequency_max BIGINT,
    frequency_sd DOUBLE PRECISION,
    frequency_iqr DOUBLE PRECISION,
    
    val_pct_min DOUBLE PRECISION,
    val_pct_max DOUBLE PRECISION,
    val_pct_std DOUBLE PRECISION,
    constancy DOUBLE PRECISION,
    
    len_max_word INT,
    len_min_word INT,
    len_avg_word DOUBLE PRECISION,
    
    number_words BIGINT,
    words_cnt_max INT,
    words_cnt_min INT,
    words_cnt_avg DOUBLE PRECISION,
    words_cnt_sd DOUBLE PRECISION,
    
    first_word VARCHAR(255),
    last_word VARCHAR(255),
    
    is_empty BOOLEAN,
    is_binary BOOLEAN,
    
    common_values TEXT, -- stores comma-separated or JSON list of top-10 strings
    common_values_soundex TEXT, -- stores comma-separated or JSON list of Soundex hashes
    
    CONSTRAINT uq_column_profile_dataset_name UNIQUE (dataset_id, column_name)
);

CREATE INDEX idx_column_profile_dataset ON column_profile(dataset_id);
CREATE INDEX idx_column_profile_tenant ON column_profile(tenant_id);
