-- V37: model_source rows die with their dataset.
--
-- V16 created model_source.dataset_id as a bare UUID with no FK, and deleteDataset deleted only
-- the dataset row — the enrolment survived, and the next compile pushed its wrapper into the
-- schema graph as a class over a dataset that no longer exists; queries over that class failed
-- at snapshot resolution with no hint why (audit MIGRATIONS-3). The service-level cleanup
-- (mapping rejection, rebuild flag, source-graph clear — IntegrationModelService.detachDataset)
-- handles the semantics; this FK makes the row half unforgettable at the database, whatever
-- future code path deletes a dataset.
--
-- Orphans that predate the FK cannot satisfy it: delete them first. They are exactly the rows
-- the defect produced, and exactly the rows nothing can ever read again (every consumer joins
-- through the dataset).
DELETE FROM model_source ms
WHERE NOT EXISTS (SELECT 1 FROM dataset d WHERE d.id = ms.dataset_id);

ALTER TABLE model_source
    ADD CONSTRAINT fk_model_source_dataset
    FOREIGN KEY (dataset_id) REFERENCES dataset (id) ON DELETE CASCADE;
