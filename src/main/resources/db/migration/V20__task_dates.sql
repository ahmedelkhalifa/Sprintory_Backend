-- PMP-48 / PMP-153: inclusive calendar dates; old tasks remain unscheduled.
alter table tasks add column start_date date;
alter table tasks add column due_date date;
alter table tasks add constraint ck_tasks_date_pair check (
    (start_date is null) = (due_date is null)
    and (due_date is null or due_date >= start_date)
);
create index idx_tasks_project_start_date on tasks(project_id, start_date);

-- Keep existing history fields, including any added by earlier migrations.
do $$
declare old_expr text;
begin
    select pg_get_expr(conbin, conrelid) into strict old_expr from pg_constraint
        where conrelid='task_history'::regclass and conname='ck_task_history_field';
    alter table task_history drop constraint ck_task_history_field;
    execute format('alter table task_history add constraint ck_task_history_field check ((%s) or field in (''START_DATE'',''DUE_DATE''))', old_expr);
end $$;
