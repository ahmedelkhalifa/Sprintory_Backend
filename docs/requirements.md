# Sprintory — System Requirements

**Project:** AI-Enhanced Integrated Project Management and Team Collaboration Platform
**Course:** SFWE343 Software Analysis and Design (Fall 2026-27)
**Source:** `System_Requirement_Specifications_Breakdown.docx` — Project Requirements Breakdown
**Status:** Transcription of the approved SRS breakdown. This file mirrors the source document; if a requirement changes, change the source first and then update this file.
**Related:** [API and Git Conventions](./api-conventions.md)

This document lists all 41 requirements (R01-R41) grouped into the ten sections of the source document, followed by the six-sprint forecast. Requirement statements and descriptions are transcribed verbatim. Use the requirement IDs in Jira tickets, branch names, and pull request descriptions.

---

## Requirements index

| ID | Title | Section | Depends on | Sprint |
| --- | --- | --- | --- | --- |
| [R01] | Registration, login, and password reset | 1. Identity and Access | None | 1 |
| [R02] | Workspaces, teams, and project membership | 1. Identity and Access | [R01] | 1 |
| [R03] | Roles and permissions | 1. Identity and Access | [R02] | 1 |
| [R04] | Access control at workspace and project level | 1. Identity and Access | [R02], [R03] | 1 |
| [R05] | Audit log of key actions | 1. Identity and Access | [R01], [R04] | 2 |
| [R06] | Project creation and configuration | 2. Initiation | [R02], [R04] | 1 |
| [R07] | Project goals, scope, and stakeholders | 2. Initiation | [R06] | 3 |
| [R08] | Project charter / brief | 2. Initiation | [R06], [R07] | 3 |
| [R09] | Tasks/issues | 3. Task Management | [R06] | 1 |
| [R10] | Subtasks, comments, and per-task activity history | 3. Task Management | [R09] | 2 |
| [R11] | Task dependencies with cycle prevention | 3. Task Management | [R09] | 4 |
| [R12] | Milestones | 3. Task Management | [R09] | 4 |
| [R13] | Product backlog with ordering and prioritization | 4. Agile Tooling | [R06], [R09] | 3 |
| [R14] | Sprint planning | 4. Agile Tooling | [R13] | 3 |
| [R15] | Scrum board with sprint lifecycle | 4. Agile Tooling | [R14] | 3 |
| [R16] | Kanban board with configurable columns and WIP limits | 4. Agile Tooling | [R06], [R09] | 2 |
| [R17] | Burndown chart and velocity | 4. Agile Tooling | [R09], [R15] | 3 |
| [R18] | Gantt chart | 5. Scheduling and Time | [R09], [R11] | 4 |
| [R19] | Time tracking | 5. Scheduling and Time | [R09] | 4 |
| [R20] | Resource allocation | 6. Resources and Reporting | [R02], [R09] | 4 |
| [R21] | Workload monitoring | 6. Resources and Reporting | [R09], [R19], [R20] | 4 |
| [R22] | Reports: progress, sprint, time, and workload | 6. Resources and Reporting | [R09], [R15], [R16], [R19], [R21] | 5 |
| [R23] | Report export | 6. Resources and Reporting | [R22] | 5 |
| [R24] | Dashboards | 6. Resources and Reporting | [R22] | 5 |
| [R25] | File attachments and sharing | 7. Collaboration | [R04], [R09] | 2 |
| [R26] | Communication: comments, mentions, project chat | 7. Collaboration | [R02], [R10] | 2 |
| [R27] | Notifications with user preferences | 7. Collaboration | [R09], [R26] | 2 |
| [R28] | External integrations (GitHub and calendar) | 7. Collaboration | [R04], [R09] | 5 |
| [R29] | Releases / deliverables tracking | 8. Delivery and Closure | [R06], [R09] | 4 |
| [R30] | Project completion, archiving, and handover | 8. Delivery and Closure | [R04], [R06] | 6 |
| [R31] | Sprint and project retrospective | 8. Delivery and Closure | [R15] | 3 |
| [R32] | AI-assisted task prioritization | 9. AI Assistant | [R09], [R11] | 5 |
| [R33] | Risk prediction: likely delays | 9. AI Assistant | [R09], [R11], [R19], [R21] | 6 |
| [R34] | Workload bottleneck detection | 9. AI Assistant | [R09], [R21] | 6 |
| [R35] | Corrective action suggestions | 9. AI Assistant | [R33], [R34] | 6 |
| [R36] | Progress summaries in natural language | 9. AI Assistant | [R09], [R22] | 6 |
| [R37] | Explainability of risks and recommendations | 9. AI Assistant | [R32], [R33], [R34], [R35] | 6 |
| [R38] | Security: roles, data isolation, secure files | 10. Non-Functional | [R03], [R04] | 1 |
| [R39] | Performance and responsiveness with large boards | 10. Non-Functional | [R15], [R16] | 6 |
| [R40] | Usability and responsive UI | 10. Non-Functional | None | 2 |
| [R41] | Reliability: backups and data integrity | 10. Non-Functional | None | 5 |

---

## 1. Identity and Access

### R01 — Registration, login, and password reset

| Field | Value |
| --- | --- |
| **Requirement** | The system shall allow users to register an account, log in, and reset a forgotten password. |
| **Depends on** | None |
| **Sprint** | 1 |

**Description.** Users create an account, authenticate to reach the platform, and recover access when they forget their password. This is the entry point to every other function.

**User story.** As a new team member, I want to register, log in, and reset my password, so that I can access my projects securely.

### R02 — Workspaces, teams, and project membership

| Field | Value |
| --- | --- |
| **Requirement** | The system shall provide workspaces that contain teams and projects, and shall allow users to be added to teams and projects as members. |
| **Depends on** | [R01] |
| **Sprint** | 1 |

**Description.** A workspace is the top-level container for an organization. Users belong to a workspace, can be grouped into teams, and join individual projects. A user can be a member of some projects of a workspace without being a member of all of them.

**User story.** As a workspace admin, I want to create a workspace, invite people, group them into teams, and add them to projects, so that each team works in its own space.

### R03 — Roles and permissions (e.g., Admin, Project Manager, Product Owner, Scrum Master, Member, Viewer)

| Field | Value |
| --- | --- |
| **Requirement** | The system shall assign each member a role (e.g., Admin, Project Manager, Product Owner, Scrum Master, Member, Viewer) that defines the actions the member is permitted to perform. |
| **Depends on** | [R02] |
| **Sprint** | 1 |

**Description.** Roles decide who can manage members, configure projects, run sprints, edit tasks, or only view information. A user may hold a different role in each project.

**User story.** As a workspace admin, I want to assign roles to members, so that each person has only the permissions their job requires.

### R04 — Access control at workspace and project level

| Field | Value |
| --- | --- |
| **Requirement** | The system shall enforce access control at workspace level and at project level, so that a user can access only the workspaces and projects they belong to, according to their role. |
| **Depends on** | [R02], [R03] |
| **Sprint** | 1 |

**Description.** Every action is checked against the user's membership and role. Access to a project requires membership in that project, and access to a workspace requires membership in that workspace.

**User story.** As a project manager, I want only the members of my project to see its data, so that confidential work stays private.

### R05 — Audit log of key actions

| Field | Value |
| --- | --- |
| **Requirement** | The system shall record key actions in an audit log that shows who performed each action and when. |
| **Depends on** | [R01], [R04] |
| **Sprint** | 2 |

**Description.** Important events, such as role changes and deletions, are recorded in a log that authorized users can review. The log cannot be edited by regular users.

**User story.** As a workspace admin, I want to review a log of key actions, so that I can trace who changed what and when.

---

## 2. Project Lifecycle: Initiation

### R06 — Project creation and configuration (Scrum or Kanban template)

| Field | Value |
| --- | --- |
| **Requirement** | The system shall allow authorized users to create and configure a project using a Scrum template or a Kanban template. |
| **Depends on** | [R02], [R04] |
| **Sprint** | 1 |

**Description.** Choosing a template prepares the project's board and default columns and enables the features that belong to the chosen methodology, such as sprints for Scrum and work-in-progress limits for Kanban. Configuration covers the project name and basic settings.

**User story.** As a project manager, I want to create a project from a Scrum or Kanban template, so that it starts with a suitable board and workflow.

### R07 — Project goals, scope, and stakeholders

| Field | Value |
| --- | --- |
| **Requirement** | The system shall allow authorized users to record the goals, scope, and stakeholders of a project. |
| **Depends on** | [R06] |
| **Sprint** | 3 |

**Description.** This information describes what the project is meant to achieve, what is included and excluded, and who has an interest in it. It is visible to project members.

**User story.** As a project manager, I want to record the goals, scope, and stakeholders of my project, so that everyone shares the same understanding of what we are delivering.

### R08 — Project charter / brief

| Field | Value |
| --- | --- |
| **Requirement** | The system shall allow authorized users to create a project charter (brief) and make it available to the project members. |
| **Depends on** | [R06], [R07] |
| **Sprint** | 3 |

**Description.** The charter is a short document written at initiation that summarizes why the project exists, what it will deliver, and who is involved. It can reuse the goals, scope, and stakeholders already recorded.

**User story.** As a project manager, I want to write a project charter, so that the team and stakeholders have an agreed starting reference.

---

## 3. Task Management

### R09 — Tasks/issues with type, priority, assignee, status, estimate, labels, and description

| Field | Value |
| --- | --- |
| **Requirement** | The system shall allow members to create, view, edit, and organize tasks (issues) that have a type, priority, assignee, status, estimate, labels, and description. |
| **Depends on** | [R06] |
| **Sprint** | 1 |

**Description.** A task is the basic unit of work. Its status shows where it stands in the workflow, its assignee shows who is responsible, and its estimate supports planning and reporting.

**User story.** As a team member, I want to create and update tasks with their details, so that all work is tracked in one place.

### R10 — Subtasks, comments, and per-task activity history

| Field | Value |
| --- | --- |
| **Requirement** | The system shall allow a task to be divided into subtasks, shall allow members to comment on a task, and shall keep a history of the changes made to each task. |
| **Depends on** | [R09] |
| **Sprint** | 2 |

**Description.** Subtasks break larger work into smaller steps, comments hold the discussion about a task, and the activity history lists what changed, who changed it, and when.

**User story.** As a team member, I want to split a task into subtasks, discuss it in comments, and see its history, so that I know what has been done and why.

### R11 — Task dependencies (blocks / blocked-by) with cycle prevention

| Field | Value |
| --- | --- |
| **Requirement** | The system shall allow a task to be defined as blocking or blocked by another task, and shall reject any dependency that would create a cycle. |
| **Depends on** | [R09] |
| **Sprint** | 4 |

**Description.** A dependency states that one task cannot proceed until another is done. A cycle, such as A depends on B, B on C, and C on A, can never be completed, so the system must refuse it and tell the user which tasks are involved.

**User story.** As a project manager, I want to define which tasks must finish before others can proceed, so that the team works in the right order and I can see what is blocked.

### R12 — Milestones

| Field | Value |
| --- | --- |
| **Requirement** | The system shall allow authorized users to define milestones with a date and to associate tasks with them. |
| **Depends on** | [R09] |
| **Sprint** | 4 |

**Description.** A milestone is a checkpoint in the project timeline with no duration. It is reached when the tasks associated with it are complete, which lets the team see progress toward key dates.

**User story.** As a project manager, I want to define milestones and link tasks to them, so that I can track progress toward key dates.

---

## 4. Agile Tooling

### R13 — Product backlog with ordering and prioritization

| Field | Value |
| --- | --- |
| **Requirement** | The system shall provide a product backlog for Scrum projects in which tasks can be ordered and prioritized. |
| **Depends on** | [R06], [R09] |
| **Sprint** | 3 |

**Description.** The backlog holds the tasks that are not yet planned into a sprint. Its order expresses priority, so the most important tasks are at the top.

**User story.** As a product owner, I want to order the backlog by priority, so that the team always works on the most valuable items first.

### R14 — Sprint planning (create sprint, set goal, pull items, capacity)

| Field | Value |
| --- | --- |
| **Requirement** | The system shall allow authorized users to plan a sprint by creating it, setting its goal, selecting items from the backlog, and defining its capacity. |
| **Depends on** | [R13] |
| **Sprint** | 3 |

**Description.** Sprint planning creates a time-boxed iteration with a goal and dates, moves chosen backlog items into it, and records the capacity so the team can see whether the planned work is realistic.

**User story.** As a Scrum Master, I want to plan a sprint with a goal, selected backlog items, and a capacity, so that the team commits to a realistic amount of work.

### R15 — Scrum board with sprint lifecycle (start, close, carry over unfinished work)

| Field | Value |
| --- | --- |
| **Requirement** | The system shall provide a Scrum board that shows the tasks of the active sprint, and shall allow authorized users to start a sprint, close it, and carry over unfinished work. |
| **Depends on** | [R14] |
| **Sprint** | 3 |

**Description.** The board shows the active sprint's tasks by workflow column. Starting a sprint makes it active, and closing it ends it. Tasks that are not finished are carried over to the backlog or to the next sprint.

**User stories.**

- As a team member, I want a board of the current sprint's tasks, so that I can see and update progress.
- As a Scrum Master, I want to start and close sprints and move unfinished work forward, so that each sprint has a clear beginning and end.

### R16 — Kanban board with configurable columns and optional WIP limits

| Field | Value |
| --- | --- |
| **Requirement** | The system shall provide a Kanban board with configurable columns, and shall allow an optional work-in-progress (WIP) limit to be set for each column. |
| **Depends on** | [R06], [R09] |
| **Sprint** | 2 |

**Description.** The board shows all open tasks in columns that represent the workflow. Authorized users can add, rename, and reorder columns. A WIP limit caps how many tasks can be in a column at the same time.

**User story.** As a project manager, I want to configure columns and limit work in progress, so that the team finishes work before starting more.

### R17 — Burndown chart and velocity

| Field | Value |
| --- | --- |
| **Requirement** | The system shall display a burndown chart for each sprint and the velocity of the team across completed sprints. |
| **Depends on** | [R09], [R15] |
| **Sprint** | 3 |

**Description.** The burndown chart shows the work remaining during a sprint. Velocity is the amount of work completed per sprint and helps the team plan future sprints.

**User story.** As a Scrum Master, I want a burndown chart and the team's velocity, so that I can see whether the sprint is on track and plan future sprints realistically.

---

## 5. Scheduling and Time

### R18 — Gantt chart (timeline with dependencies)

| Field | Value |
| --- | --- |
| **Requirement** | The system shall display a Gantt chart that shows tasks on a timeline together with their dependencies. |
| **Depends on** | [R09], [R11] |
| **Sprint** | 4 |

**Description.** Tasks appear as bars over time and dependencies appear as links between them, so the schedule and its critical relationships can be seen in one view.

**User story.** As a project manager, I want a timeline of tasks and their dependencies, so that I can plan the schedule and spot conflicts.

### R19 — Time tracking (manual entries, timer, per-task and per-user logs)

| Field | Value |
| --- | --- |
| **Requirement** | The system shall allow users to record the time they spend on tasks, either as manual entries or with a timer, and shall provide time logs per task and per user. |
| **Depends on** | [R09] |
| **Sprint** | 4 |

**Description.** Members log effort against the tasks they work on. The logs can be viewed for one task or for one person, and they feed time reports and workload figures.

**User stories.**

- As a team member, I want to log the time I spend on tasks, so that my effort is recorded accurately.
- As a project manager, I want time logs per task and per user, so that I can see where effort is going.

---

## 6. Resources and Reporting

### R20 — Resource allocation (people assigned to projects or tasks with a time share)

| Field | Value |
| --- | --- |
| **Requirement** | The system shall allow authorized users to assign people to projects or tasks with a share of their time. |
| **Depends on** | [R02], [R09] |
| **Sprint** | 4 |

**Description.** An allocation states how much of a person's time is dedicated to a project or task, so that capacity can be planned deliberately.

**User story.** As a project manager, I want to allocate people to projects and tasks with a time share, so that capacity is planned deliberately.

### R21 — Workload monitoring (load per person, overallocation flags)

| Field | Value |
| --- | --- |
| **Requirement** | The system shall show the workload of each person and shall flag a person who is overallocated. |
| **Depends on** | [R09], [R19], [R20] |
| **Sprint** | 4 |

**Description.** Workload compares what is assigned to a person with their available time, using allocations, estimates, and logged time. A flag appears when the load exceeds what the person can reasonably do.

**User story.** As a project manager, I want to see each person's workload and be warned about overallocation, so that I can rebalance work before people are overloaded.

### R22 — Reports: progress, sprint, time, and workload

| Field | Value |
| --- | --- |
| **Requirement** | The system shall provide progress reports, sprint reports, time reports, and workload reports. |
| **Depends on** | [R09], [R15], [R16], [R19], [R21] |
| **Sprint** | 5 |

**Description.** Reports summarize the state of the work: how far the project has progressed, how each sprint went, how much time was spent, and how the load is spread over the team.

**User story.** As a project manager, I want reports on progress, sprints, time, and workload, so that I can inform stakeholders and make decisions.

### R23 — Report export

| Field | Value |
| --- | --- |
| **Requirement** | The system shall allow reports to be exported. |
| **Depends on** | [R22] |
| **Sprint** | 5 |

**Description.** Reports can be exported to a file that can be shared or printed outside the platform. The file formats will be chosen by the team.

**User story.** As a project manager, I want to export reports, so that I can share them with people who do not use the platform.

### R24 — Dashboards

| Field | Value |
| --- | --- |
| **Requirement** | The system shall provide dashboards that summarize the status of a project at a glance. |
| **Depends on** | [R22] |
| **Sprint** | 5 |

**Description.** A dashboard gathers the most important figures from the reports on one screen, such as progress, upcoming milestones, and workload.

**User story.** As a project manager, I want a dashboard that summarizes project status, so that I can see the health of the project at a glance.

---

## 7. Collaboration

### R25 — File attachments and sharing

| Field | Value |
| --- | --- |
| **Requirement** | The system shall allow files to be attached to tasks and shared with the members who have access. |
| **Depends on** | [R04], [R09] |
| **Sprint** | 2 |

**Description.** Documents and other files can be attached to a task, so they are kept with the work they relate to. Only members with access to the project can open them.

**User story.** As a team member, I want to attach files to tasks, so that related documents are kept with the work.

### R26 — Communication: comments, @mentions, optional project chat

| Field | Value |
| --- | --- |
| **Requirement** | The system shall support communication between members through comments, @mentions, and an optional project chat. |
| **Depends on** | [R02], [R10] |
| **Sprint** | 2 |

**Description.** Members discuss work in comments, draw a colleague's attention with an @mention, and may use a project chat for general conversation.

**User story.** As a team member, I want to comment, mention colleagues, and chat within the project, so that I can get answers without leaving the platform.

### R27 — Notifications (in-app and email) with user preferences

| Field | Value |
| --- | --- |
| **Requirement** | The system shall notify users of relevant events in the application and by email, and shall allow each user to set their notification preferences. |
| **Depends on** | [R09], [R26] |
| **Sprint** | 2 |

**Description.** Users are informed when something concerns them, for example an assignment, a mention, or a status change. Each user chooses which notifications to receive and through which channel.

**User story.** As a team member, I want to receive notifications about what concerns me and choose how I receive them, so that I stay informed without being overwhelmed.

### R28 — External integrations (GitHub and calendar)

| Field | Value |
| --- | --- |
| **Requirement** | The system shall integrate with GitHub and with a calendar service. |
| **Depends on** | [R04], [R09] |
| **Sprint** | 5 |

**Description.** A project can be linked to a GitHub repository, so that work in the repository is reflected on the platform. A calendar integration lets users see the dates of the project, such as sprints, milestones, and due dates, in their own calendar. The team has chosen GitHub and a calendar as the integrations to build.

**User story.** As a developer, I want to connect my GitHub repository and my calendar to the project, so that updates in those tools are reflected on the platform.

---

## 8. Project Lifecycle: Delivery and Closure

### R29 — Releases / deliverables tracking

| Field | Value |
| --- | --- |
| **Requirement** | The system shall allow authorized users to define releases or deliverables and to track their status. |
| **Depends on** | [R06], [R09] |
| **Sprint** | 4 |

**Description.** A release or deliverable is something the project hands over. Tracking shows which have been delivered and which are still pending.

**User story.** As a product owner, I want to track releases and deliverables, so that I can see what has been delivered and what is pending.

### R30 — Project completion, archiving, and handover

| Field | Value |
| --- | --- |
| **Requirement** | The system shall allow authorized users to mark a project as complete, archive it, and hand it over. |
| **Depends on** | [R04], [R06] |
| **Sprint** | 6 |

**Description.** A completed project is archived and becomes read-only while remaining available for reference. Handover passes the project's ownership and information to the people who will take it over.

**User story.** As a project manager, I want to complete and archive a project and hand it over, so that the work is formally closed and preserved.

### R31 — Sprint and project retrospective

| Field | Value |
| --- | --- |
| **Requirement** | The system shall allow the team to record a retrospective for a sprint and for a project. |
| **Depends on** | [R15] |
| **Sprint** | 3 |

**Description.** A retrospective captures what went well, what did not, and what to improve, so that lessons learned are kept for the next sprint or project.

**User story.** As a Scrum Master, I want to record retrospectives, so that the team captures lessons learned and improvements.

---

## 9. AI Assistant (Advanced Feature)

### R32 — AI-assisted task prioritization

| Field | Value |
| --- | --- |
| **Requirement** | The system shall suggest a priority order for tasks based on the project's data. |
| **Depends on** | [R09], [R11] |
| **Sprint** | 5 |

**Description.** The assistant analyzes information such as task details and dependencies and proposes which tasks should be done first. Users decide whether to follow the suggestion.

**User story.** As a product owner, I want suggested task priorities, so that the team focuses on what matters most.

### R33 — Risk prediction: likely delays

| Field | Value |
| --- | --- |
| **Requirement** | The system shall identify tasks, sprints, or projects that are likely to be delayed. |
| **Depends on** | [R09], [R11], [R19], [R21] |
| **Sprint** | 6 |

**Description.** The assistant looks at progress, dependencies, time logged, and workload to point out work that is at risk of missing its dates.

**User story.** As a project manager, I want early warnings of likely delays, so that I can act before deadlines are missed.

### R34 — Workload bottleneck detection

| Field | Value |
| --- | --- |
| **Requirement** | The system shall detect workload bottlenecks, such as overloaded people or stages where work accumulates. |
| **Depends on** | [R09], [R21] |
| **Sprint** | 6 |

**Description.** The assistant finds places where work is piling up or where one person carries too much, based on workload and task data.

**User story.** As a project manager, I want to be told where workload bottlenecks occur, so that I can relieve them.

### R35 — Corrective action suggestions (reassign, reschedule, reduce scope)

| Field | Value |
| --- | --- |
| **Requirement** | The system shall suggest corrective actions, such as reassigning work, rescheduling, or reducing scope, in response to detected risks and bottlenecks. |
| **Depends on** | [R33], [R34] |
| **Sprint** | 6 |

**Description.** For each detected delay or bottleneck, the assistant proposes ways to resolve it. Users remain in control and decide which action, if any, to take.

**User story.** As a project manager, I want suggestions on how to fix delays and bottlenecks, so that I can decide quickly what to change.

### R36 — Progress summaries (sprint or project, in natural language)

| Field | Value |
| --- | --- |
| **Requirement** | The system shall generate progress summaries, written in natural language, for a sprint or for a project. |
| **Depends on** | [R09], [R22] |
| **Sprint** | 6 |

**Description.** The assistant turns the project's data into a short readable summary of what has been done, what is in progress, and what is at risk.

**User story.** As a stakeholder, I want a plain-language summary of progress, so that I can understand the status without reading detailed reports.

### R37 — Explainability: show why a risk or recommendation was flagged

| Field | Value |
| --- | --- |
| **Requirement** | The system shall show the reason why each risk or recommendation was flagged. |
| **Depends on** | [R32], [R33], [R34], [R35] |
| **Sprint** | 6 |

**Description.** Every warning or suggestion from the assistant is accompanied by the data it is based on, so users can check it and decide how much to trust it.

**User story.** As a project manager, I want to see why the assistant flagged something, so that I can trust and verify its advice.

---

## 10. Non-Functional Requirements

### R38 — Security: role enforcement, data isolation between workspaces, secure file handling

| Field | Value |
| --- | --- |
| **Requirement** | The system shall enforce role-based permissions, shall isolate the data of each workspace from all other workspaces, and shall handle uploaded files securely. |
| **Depends on** | [R03], [R04] |
| **Sprint** | 1 (applies to every sprint) |

**Description.** A user can never read or change data they are not permitted to access, and one workspace can never see another workspace's data. Uploaded files are checked and stored so they cannot be used to harm the system or its users; this part applies as soon as file attachments ([R25]) are built. **This requirement applies to every sprint.**

**User story.** As a workspace admin, I want my workspace's data protected from other workspaces and from unauthorized roles, so that our information stays confidential.

### R39 — Performance and responsiveness with large boards

| Field | Value |
| --- | --- |
| **Requirement** | The system shall remain responsive when boards contain a large number of tasks. |
| **Depends on** | [R15], [R16] |
| **Sprint** | 6 |

**Description.** Displaying a board and moving a task should feel immediate even when the board holds many tasks. The team will agree on a measurable target for this.

**User story.** As a team member, I want boards to load and update quickly even with many tasks, so that I can work without waiting.

### R40 — Usability and responsive UI

| Field | Value |
| --- | --- |
| **Requirement** | The system shall provide a usable interface that adapts to different screen sizes. |
| **Depends on** | None |
| **Sprint** | 2 (applies to every user-facing function) |

**Description.** The interface should be clear and consistent, and should work well on screens of different sizes. **This requirement applies to every user-facing function.**

**User story.** As a team member, I want an interface that is easy to use on different devices, so that I can work from wherever I am.

### R41 — Reliability: backups and data integrity

| Field | Value |
| --- | --- |
| **Requirement** | The system shall back up its data regularly and shall preserve the integrity of the stored data. |
| **Depends on** | None |
| **Sprint** | 5 |

**Description.** Data is copied regularly so it can be restored after a failure, and the stored data stays consistent and is not silently lost or corrupted.

**User story.** As a workspace admin, I want my data backed up and kept consistent, so that nothing is lost if something fails.

---

## Sprint forecast

| Sprint | Weeks | Goal | Reqs |
| --- | --- | --- | --- |
| 1 | Weeks 1-2 | Foundation: a user can sign in, create a workspace and a project, and add tasks. | 7 |
| 2 | Weeks 3-4 | Kanban and communication: a team runs its work on a Kanban board, discusses tasks, shares files, and gets notified. | 7 |
| 3 | Weeks 5-6 | Scrum workflow: plan, run, and close a sprint from the backlog, and describe the project at initiation. | 7 |
| 4 | Weeks 7-8 | Scheduling and effort: relate and schedule work with dependencies, milestones, and a Gantt chart, track time, plan resources, and track releases. | 7 |
| 5 | Weeks 9-10 | Reporting and integrations: reports, export, and dashboards, external integrations, backups, and the first AI feature (prioritization). | 6 |
| 6 | Weeks 11-12 | AI assistant and closure: risk and bottleneck detection, corrective actions, summaries, and explanations, project closure, and performance tuning. | 7 |

The order of requirements within each sprint below follows the source document, which is not numeric; it reflects the intended build sequence.

### Sprint 1 (Weeks 1-2)

**Goal:** Foundation: a user can sign in, create a workspace and a project, and add tasks.

| ID | Requirement |
| --- | --- |
| [R01] | Registration, login, and password reset |
| [R02] | Workspaces, teams, and project membership |
| [R03] | Roles and permissions (e.g., Admin, Project Manager, Product Owner, Scrum Master, Member, Viewer) |
| [R04] | Access control at workspace and project level |
| [R06] | Project creation and configuration (Scrum or Kanban template) |
| [R09] | Tasks/issues with type, priority, assignee, status, estimate, labels, and description |
| [R38] | Security: role enforcement, data isolation between workspaces, secure file handling |

### Sprint 2 (Weeks 3-4)

**Goal:** Kanban and communication: a team runs its work on a Kanban board, discusses tasks, shares files, and gets notified.

| ID | Requirement |
| --- | --- |
| [R16] | Kanban board with configurable columns and optional WIP limits |
| [R10] | Subtasks, comments, and per-task activity history |
| [R05] | Audit log of key actions |
| [R25] | File attachments and sharing |
| [R26] | Communication: comments, @mentions, optional project chat |
| [R27] | Notifications (in-app and email) with user preferences |
| [R40] | Usability and responsive UI |

### Sprint 3 (Weeks 5-6)

**Goal:** Scrum workflow: plan, run, and close a sprint from the backlog, and describe the project at initiation.

| ID | Requirement |
| --- | --- |
| [R13] | Product backlog with ordering and prioritization |
| [R14] | Sprint planning (create sprint, set goal, pull items, capacity) |
| [R15] | Scrum board with sprint lifecycle (start, close, carry over unfinished work) |
| [R07] | Project goals, scope, and stakeholders |
| [R08] | Project charter / brief |
| [R17] | Burndown chart and velocity |
| [R31] | Sprint and project retrospective |

### Sprint 4 (Weeks 7-8)

**Goal:** Scheduling and effort: relate and schedule work with dependencies, milestones, and a Gantt chart, track time, plan resources, and track releases.

| ID | Requirement |
| --- | --- |
| [R11] | Task dependencies (blocks / blocked-by) with cycle prevention |
| [R12] | Milestones |
| [R18] | Gantt chart (timeline with dependencies) |
| [R19] | Time tracking (manual entries, timer, per-task and per-user logs) |
| [R20] | Resource allocation (people assigned to projects or tasks with a time share) |
| [R21] | Workload monitoring (load per person, overallocation flags) |
| [R29] | Releases / deliverables tracking |

### Sprint 5 (Weeks 9-10)

**Goal:** Reporting and integrations: reports, export, and dashboards, external integrations, backups, and the first AI feature (prioritization).

| ID | Requirement |
| --- | --- |
| [R22] | Reports: progress, sprint, time, and workload |
| [R23] | Report export |
| [R24] | Dashboards |
| [R28] | External integrations (GitHub and calendar) |
| [R32] | AI-assisted task prioritization |
| [R41] | Reliability: backups and data integrity |

### Sprint 6 (Weeks 11-12)

**Goal:** AI assistant and closure: risk and bottleneck detection, corrective actions, summaries, and explanations, project closure, and performance tuning.

| ID | Requirement |
| --- | --- |
| [R33] | Risk prediction: likely delays |
| [R34] | Workload bottleneck detection |
| [R35] | Corrective action suggestions (reassign, reschedule, reduce scope) |
| [R36] | Progress summaries (sprint or project, in natural language) |
| [R37] | Explainability: show why a risk or recommendation was flagged |
| [R30] | Project completion, archiving, and handover |
| [R39] | Performance and responsiveness with large boards |

---

## Appendix A — Reverse dependency map

**Derived, not part of the source document.** This table is a mechanical inversion of the "Depends on" fields above: it shows, for each requirement, which other requirements depend on it. Use it to judge the impact of changing or delaying a requirement.

| ID | Required by |
| --- | --- |
| [R01] | [R02], [R05] |
| [R02] | [R03], [R04], [R06], [R20], [R26] |
| [R03] | [R04], [R38] |
| [R04] | [R05], [R06], [R25], [R28], [R30], [R38] |
| [R05] | — |
| [R06] | [R07], [R08], [R09], [R13], [R16], [R29], [R30] |
| [R07] | [R08] |
| [R08] | — |
| [R09] | [R10], [R11], [R12], [R13], [R16], [R17], [R18], [R19], [R20], [R21], [R22], [R25], [R27], [R28], [R29], [R32], [R33], [R34], [R36] |
| [R10] | [R26] |
| [R11] | [R18], [R32], [R33] |
| [R12] | — |
| [R13] | [R14] |
| [R14] | [R15] |
| [R15] | [R17], [R22], [R31], [R39] |
| [R16] | [R22], [R39] |
| [R17] | — |
| [R18] | — |
| [R19] | [R21], [R22], [R33] |
| [R20] | [R21] |
| [R21] | [R22], [R33], [R34] |
| [R22] | [R23], [R24], [R36] |
| [R23] | — |
| [R24] | — |
| [R25] | — |
| [R26] | [R27] |
| [R27] | — |
| [R28] | — |
| [R29] | — |
| [R30] | — |
| [R31] | — |
| [R32] | [R37] |
| [R33] | [R35], [R37] |
| [R34] | [R35], [R37] |
| [R35] | [R37] |
| [R36] | — |
| [R37] | — |
| [R38] | — |
| [R39] | — |
| [R40] | — |
| [R41] | — |

[R01]: #r01--registration-login-and-password-reset
[R02]: #r02--workspaces-teams-and-project-membership
[R03]: #r03--roles-and-permissions-eg-admin-project-manager-product-owner-scrum-master-member-viewer
[R04]: #r04--access-control-at-workspace-and-project-level
[R05]: #r05--audit-log-of-key-actions
[R06]: #r06--project-creation-and-configuration-scrum-or-kanban-template
[R07]: #r07--project-goals-scope-and-stakeholders
[R08]: #r08--project-charter--brief
[R09]: #r09--tasksissues-with-type-priority-assignee-status-estimate-labels-and-description
[R10]: #r10--subtasks-comments-and-per-task-activity-history
[R11]: #r11--task-dependencies-blocks--blocked-by-with-cycle-prevention
[R12]: #r12--milestones
[R13]: #r13--product-backlog-with-ordering-and-prioritization
[R14]: #r14--sprint-planning-create-sprint-set-goal-pull-items-capacity
[R15]: #r15--scrum-board-with-sprint-lifecycle-start-close-carry-over-unfinished-work
[R16]: #r16--kanban-board-with-configurable-columns-and-optional-wip-limits
[R17]: #r17--burndown-chart-and-velocity
[R18]: #r18--gantt-chart-timeline-with-dependencies
[R19]: #r19--time-tracking-manual-entries-timer-per-task-and-per-user-logs
[R20]: #r20--resource-allocation-people-assigned-to-projects-or-tasks-with-a-time-share
[R21]: #r21--workload-monitoring-load-per-person-overallocation-flags
[R22]: #r22--reports-progress-sprint-time-and-workload
[R23]: #r23--report-export
[R24]: #r24--dashboards
[R25]: #r25--file-attachments-and-sharing
[R26]: #r26--communication-comments-mentions-optional-project-chat
[R27]: #r27--notifications-in-app-and-email-with-user-preferences
[R28]: #r28--external-integrations-github-and-calendar
[R29]: #r29--releases--deliverables-tracking
[R30]: #r30--project-completion-archiving-and-handover
[R31]: #r31--sprint-and-project-retrospective
[R32]: #r32--ai-assisted-task-prioritization
[R33]: #r33--risk-prediction-likely-delays
[R34]: #r34--workload-bottleneck-detection
[R35]: #r35--corrective-action-suggestions-reassign-reschedule-reduce-scope
[R36]: #r36--progress-summaries-sprint-or-project-in-natural-language
[R37]: #r37--explainability-show-why-a-risk-or-recommendation-was-flagged
[R38]: #r38--security-role-enforcement-data-isolation-between-workspaces-secure-file-handling
[R39]: #r39--performance-and-responsiveness-with-large-boards
[R40]: #r40--usability-and-responsive-ui
[R41]: #r41--reliability-backups-and-data-integrity