package team.sprintory_backend.workspace.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import team.sprintory_backend.workspace.repositories.WorkspaceRepository;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pure tests: the repository is mocked, so no database or Spring context. */
class SlugGeneratorTest {

    private WorkspaceRepository workspaceRepository;
    private SlugGenerator generator;

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(WorkspaceRepository.class);
        generator = new SlugGenerator(workspaceRepository);
        when(workspaceRepository.existsBySlug(anyString())).thenReturn(false);
    }

    // --- toSlug ---

    @Test
    void lowerCasesAndJoinsWordsWithHyphens() {
        assertEquals("my-team", SlugGenerator.toSlug("My Team"));
    }

    @Test
    void removesAccents() {
        assertEquals("my-team-u", SlugGenerator.toSlug("My Team Ü"));
        assertEquals("calisma-alani", SlugGenerator.toSlug("Çalışma Alanı"));
    }

    @Test
    void mapsDotlessIToI() {
        assertEquals("isik", SlugGenerator.toSlug("Işık"));
    }

    @Test
    void ignoresATurkishDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("istanbul", SlugGenerator.toSlug("ISTANBUL"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void collapsesRunsOfSymbolsIntoOneHyphen() {
        assertEquals("r-d-ops", SlugGenerator.toSlug("R&D / Ops"));
    }

    @Test
    void trimsHyphensAtBothEnds() {
        assertEquals("team", SlugGenerator.toSlug("--Team--"));
    }

    @Test
    void fallsBackWhenNothingIsLeft() {
        assertEquals("workspace", SlugGenerator.toSlug("!!!"));
    }

    @Test
    void cutsLongNamesToTheMaximumLength() {
        assertEquals(SlugGenerator.MAX_LENGTH, SlugGenerator.toSlug("a".repeat(150)).length());
    }

    @Test
    void doesNotEndWithAHyphenAfterCutting() {
        String slug = SlugGenerator.toSlug("a".repeat(SlugGenerator.MAX_LENGTH - 1) + " bcd");

        assertEquals("a".repeat(SlugGenerator.MAX_LENGTH - 1), slug);
    }

    // --- generate ---

    @Test
    void returnsTheBaseSlugWhenItIsFree() {
        assertEquals("acme", generator.generate("Acme"));
    }

    @Test
    void appendsTheFirstFreeNumberWhenTaken() {
        when(workspaceRepository.existsBySlug("acme")).thenReturn(true);
        when(workspaceRepository.existsBySlug("acme-2")).thenReturn(true);

        assertEquals("acme-3", generator.generate("Acme"));
    }

    @Test
    void keepsSuffixedSlugsWithinTheMaximumLength() {
        String base = "a".repeat(SlugGenerator.MAX_LENGTH);
        when(workspaceRepository.existsBySlug(base)).thenReturn(true);

        String slug = generator.generate(base);

        assertEquals(SlugGenerator.MAX_LENGTH, slug.length());
        assertTrue(slug.endsWith("-2"));
        assertFalse(slug.contains("--"));
    }
}