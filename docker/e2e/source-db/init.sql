-- The federated source for scenario A4 (M9 import.from_postgres) and the third
-- hop of the multi-hop join in A5/B3.
CREATE TABLE affiliations (
    aff_id      TEXT PRIMARY KEY,
    institution TEXT NOT NULL,
    country     TEXT NOT NULL
);

INSERT INTO affiliations (aff_id, institution, country) VALUES
    ('A-01', 'Universitat Politecnica de Catalunya', 'Spain'),
    ('A-02', 'Massachusetts Institute of Technology', 'USA');
