--  V17__add_federated_to_data_repository.sql  —  Add federated column to data_repository
ALTER TABLE data_repository ADD COLUMN federated BOOLEAN NOT NULL DEFAULT false;
