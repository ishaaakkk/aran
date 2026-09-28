-- S2-1: integration_model, data_product and cube_definition each carried a project_id with no
-- foreign key (V16, V18, V41), while every other project-scoped table has one (project_member V1,
-- data_repository V6, dataset_refresh V28). The gap was load-bearing: the model's getOrCreate
-- minted a row for ANY UUID a request named, and the products/cubes create paths accepted any
-- project id, so a typo or a probe left rows pointing at nothing. The visibility gate now refuses
-- those requests before they reach the database; this is the backstop that makes the rows
-- impossible rather than merely unlikely.
--
-- Repair first: a row that already points at no project would make the constraint fail to apply.
-- Such a row is unreachable by any path that checks the project, and its children go with it
-- (model_source/model_mapping/model_constraint cascade from integration_model). A deleted
-- data_product may leave a lake snapshot behind; the admin "unreferenced snapshots" report is the
-- existing place that finds those.
DELETE FROM cube_definition   WHERE project_id NOT IN (SELECT id FROM project);
DELETE FROM data_product      WHERE project_id NOT IN (SELECT id FROM project);
DELETE FROM integration_model WHERE project_id NOT IN (SELECT id FROM project);

-- ON DELETE CASCADE, as the existing project foreign keys are: there is no project-delete path
-- today, and when one arrives a project's model, products and cubes are its own.
ALTER TABLE integration_model
    ADD CONSTRAINT fk_integration_model_project
    FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE;

ALTER TABLE data_product
    ADD CONSTRAINT fk_data_product_project
    FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE;

ALTER TABLE cube_definition
    ADD CONSTRAINT fk_cube_definition_project
    FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE;
