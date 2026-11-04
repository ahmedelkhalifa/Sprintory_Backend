package team.sprintory_backend.notification.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import team.sprintory_backend.project.models.ProjectRole;

class NotificationMessagesTest {

    @Test
    void anExcerptCollapsesWhitespace() {
        assertEquals("one two three", NotificationMessages.excerpt("  one\n\ntwo\t three  "));
    }

    @Test
    void blankTextHasNoExcerpt() {
        assertNull(NotificationMessages.excerpt(null));
        assertNull(NotificationMessages.excerpt("   \n "));
    }

    @Test
    void aLongExcerptIsCutTo200CharactersEndingInAnEllipsis() {
        String excerpt = NotificationMessages.excerpt("a".repeat(500));

        assertEquals(200, excerpt.length());
        assertTrue(excerpt.endsWith("…"));
    }

    @Test
    void anExcerptOfExactly200IsKept() {
        String text = "b".repeat(200);
        assertEquals(text, NotificationMessages.excerpt(text));
    }

    @Test
    void rolesAreLabelledInDeclarationOrder() {
        assertEquals("Project Manager, Product Owner, Member", NotificationMessages.roles(
                Set.of(ProjectRole.MEMBER, ProjectRole.PRODUCT_OWNER, ProjectRole.PROJECT_MANAGER)));
        assertEquals("Viewer", NotificationMessages.roles(Set.of(ProjectRole.VIEWER)));
    }
}