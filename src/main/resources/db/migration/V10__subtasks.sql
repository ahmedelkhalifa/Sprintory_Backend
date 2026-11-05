-- PMP-131/PMP-132: one-level subtasks and deletion provenance.
-- No change to V1-V8: deployments already running V8 can migrate safely.
ALTER TABLE tasks
    ADD COLUMN parent_task_id bigint,
    ADD COLUMN estimate_minutes integer,
    ADD COLUMN deleted_with_parent_id bigint;

ALTER TABLE tasks ADD CONSTRAINT fk_tasks_parent
    FOREIGN KEY (parent_task_id) REFERENCES tasks (id) ON DELETE CASCADE;
ALTER TABLE tasks ADD CONSTRAINT ck_tasks_estimate_minutes
    CHECK (estimate_minutes IS NULL OR estimate_minutes > 0);
ALTER TABLE tasks ADD CONSTRAINT ck_tasks_not_self_parent
    CHECK (parent_task_id IS NULL OR parent_task_id <> id);
ALTER TABLE tasks ADD CONSTRAINT ck_tasks_deletion_origin
    CHECK (deleted_with_parent_id IS NULL OR
           (parent_task_id = deleted_with_parent_id AND deleted_at IS NOT NULL));
CREATE INDEX idx_tasks_parent_live
    ON tasks (parent_task_id, deleted_at) WHERE parent_task_id IS NOT NULL;
