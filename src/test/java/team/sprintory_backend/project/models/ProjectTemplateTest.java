package team.sprintory_backend.project.models;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;
import static team.sprintory_backend.project.models.ProjectRole.*;

/** Which roles each template offers (story 6, acceptance criteria 4 and 5). */
class ProjectTemplateTest {

    @Test
    void scrumOffersAllFiveRoles() {
        assertEquals(EnumSet.of(PROJECT_MANAGER, PRODUCT_OWNER, SCRUM_MASTER, MEMBER, VIEWER),
                ProjectTemplate.SCRUM.roles());
    }

    @Test
    void kanbanOffersOnlyManagerMemberAndViewer() {
        assertEquals(EnumSet.of(PROJECT_MANAGER, MEMBER, VIEWER), ProjectTemplate.KANBAN.roles());
        assertFalse(ProjectTemplate.KANBAN.offers(PRODUCT_OWNER));
        assertFalse(ProjectTemplate.KANBAN.offers(SCRUM_MASTER));
    }

    @Test
    void everyTemplateOffersTheDefaultRoleAndAProjectManager() {
        for (ProjectTemplate template : ProjectTemplate.values()) {
            assertTrue(template.offers(MEMBER), template.name());
            assertTrue(template.offers(PROJECT_MANAGER), template.name());
        }
    }

    @Test
    void roleListsCannotBeChanged() {
        assertThrows(UnsupportedOperationException.class, () -> ProjectTemplate.KANBAN.roles().add(SCRUM_MASTER));
    }
}