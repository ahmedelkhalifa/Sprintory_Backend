-- R03 (story 6): the project template and project roles.
-- Never edit this file once merged; add V4__... for every later change.

-- The template is chosen at creation and never changes. Projects created before this
-- migration had none; they become Scrum, which offers every role.
alter table projects add column template varchar(255);
update projects set template = 'SCRUM';
alter table projects alter column template set not null;
alter table projects add constraint ck_projects_template check (template in ('SCRUM', 'KANBAN'));

-- One row per role a project member holds; a member holds one or more.
create table project_member_roles (
    project_member_id  bigint       not null,
    role               varchar(255) not null,
    constraint pk_project_member_roles primary key (project_member_id, role),
    constraint fk_project_member_roles_member foreign key (project_member_id)
        references project_members (id) on delete cascade,
    constraint ck_project_member_roles_role check (role in
        ('PROJECT_MANAGER', 'PRODUCT_OWNER', 'SCRUM_MASTER', 'MEMBER', 'VIEWER'))
);

-- Existing members: the project's creator (created_by holds their email) becomes its Project
-- Manager, everyone else a Member. A project whose creator already left has no Project Manager
-- until a workspace admin assigns one.
insert into project_member_roles (project_member_id, role)
select pm.id, 'PROJECT_MANAGER'
from project_members pm
join projects p on p.id = pm.project_id
join users u on u.id = pm.user_id
where u.email = p.created_by;

insert into project_member_roles (project_member_id, role)
select pm.id, 'MEMBER'
from project_members pm
where not exists (select 1 from project_member_roles r where r.project_member_id = pm.id);