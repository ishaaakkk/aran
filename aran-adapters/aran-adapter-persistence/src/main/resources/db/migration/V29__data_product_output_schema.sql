-- The JDBC `products` schema describes a data product's columns from the registry, without
-- reading the lake: a schema browser expanding forty tables must not become forty downloads.
-- Written at materialization from the result's own metadata (name, DuckDB type, JDBC type code);
-- NULL for a product last materialized before this migration, which leaves its table listed and
-- queryable but undescribed until it is materialized again.
ALTER TABLE data_product ADD COLUMN output_schema TEXT;
