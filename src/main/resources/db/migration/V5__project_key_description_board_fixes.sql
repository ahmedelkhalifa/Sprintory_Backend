-- R06 follow-up to V4. V4 backfilled Scrum as To Do, In Progress, Done and Kanban as
-- To Do, In Progress, In Review, Done. Existing rows are changed in place so column ids stay
-- stable; only untouched defaults match, and no column endpoints or tasks exist yet.

-- Scrum: To Do, In Progress, In Review, Done. Move Done to 3, then insert In Review at 2.
update project_board_columns c
set sort_order = 3
from projects p
where p.id = c.project_id
  and p.template = 'SCRUM'
  and c.sort_order = 2
  and c.name = 'Done';

insert into project_board_columns (project_id, name, sort_order)
select c.project_id, 'In Review', 2
from project_board_columns c
join projects p on p.id = c.project_id
where p.template = 'SCRUM'
  and c.sort_order = 3
  and c.name = 'Done'
  and not exists (select 1 from project_board_columns o
                  where o.project_id = c.project_id and o.sort_order = 2);

-- Kanban: Backlog, To Do, In Progress, Done (Sprint 1 plan, story 8).
update project_board_columns c
set name = case c.sort_order
               when 0 then 'Backlog'
               when 1 then 'To Do'
               when 2 then 'In Progress'
           end
from projects p
where p.id = c.project_id
  and p.template = 'KANBAN'
  and (c.sort_order, c.name) in ((0, 'To Do'), (1, 'In Progress'), (2, 'In Review'));

-- Audit columns (BaseEntity), defined as in V2.
alter table project_boards
    add column created_at timestamp(6),
    add column updated_at timestamp(6),
    add column created_by varchar(255),
    add column updated_by varchar(255);

alter table project_board_columns
    add column created_at timestamp(6),
    add column updated_at timestamp(6),
    add column created_by varchar(255),
    add column updated_by varchar(255);

-- Project key: upper-case, unique within the workspace, never changed. Existing projects get
-- 'P' || id, which is unique and matches the key pattern (a letter, then letters or digits).
alter table projects add column project_key varchar(10);
update projects set project_key = 'P' || id;
alter table projects alter column project_key set not null;
alter table projects
    add constraint uk_projects_workspace_key unique (workspace_id, project_key);

-- Optional project description, editable in the project settings; no length limit.
alter table projects add column description text;