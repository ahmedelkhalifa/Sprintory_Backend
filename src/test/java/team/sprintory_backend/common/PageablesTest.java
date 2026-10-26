package team.sprintory_backend.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PageablesTest {

    @Test
    void likeWildcardsAndTheEscapeCharacterAreTakenLiterally() {
        assertEquals("50\\%", Pageables.escapeLike("50%"));
        assertEquals("snake\\_case", Pageables.escapeLike("snake_case"));
        assertEquals("a\\\\b", Pageables.escapeLike("a\\b"));
        assertEquals("mob-42", Pageables.escapeLike("mob-42"));
    }
}