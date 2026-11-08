package team.sprintory_backend.attachment.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentFileNamesTest {

    @Test
    void keepsOnlyTheLastPathSegment() {
        assertEquals("x.pdf", AttachmentFileNames.displayName("../../x.pdf"));
        assertEquals("passwd", AttachmentFileNames.displayName("/etc/passwd"));
        assertEquals("win.ini", AttachmentFileNames.displayName("..\\..\\windows\\win.ini"));
        assertEquals("report.pdf", AttachmentFileNames.displayName("C:\\Users\\ada\\Desktop\\report.pdf"));
    }

    @Test
    void removesControlAndInvisibleFormatCharacters() {
        assertEquals("ab.pdf", AttachmentFileNames.displayName("a\r\nb.pdf"));
        assertEquals("tab.pdf", AttachmentFileNames.displayName("\ttab.pdf "));
        // U+202E would show "invoice\u202Efdp.exe" as "invoiceexe.pdf"
        assertEquals("invoicefdp.exe", AttachmentFileNames.displayName("invoice\u202Efdp.exe"));
    }

    @Test
    void keepsOrdinaryUnicodeNames() {
        assertEquals("Görev planı – şubat.xlsx", AttachmentFileNames.displayName("Görev planı – şubat.xlsx"));
    }

    @Test
    void fallsBackWhenNothingIsLeft() {
        assertEquals(AttachmentFileNames.FALLBACK, AttachmentFileNames.displayName(null));
        assertEquals(AttachmentFileNames.FALLBACK, AttachmentFileNames.displayName(""));
        assertEquals(AttachmentFileNames.FALLBACK, AttachmentFileNames.displayName("../"));
        assertEquals(AttachmentFileNames.FALLBACK, AttachmentFileNames.displayName(" \u0000 "));
    }

    @Test
    void cutsLongNamesKeepingTheExtension() {
        String name = AttachmentFileNames.displayName("a".repeat(400) + ".pdf");

        assertEquals(AttachmentFileNames.MAX_LENGTH, name.length());
        assertTrue(name.endsWith("a.pdf"));
    }

    @Test
    void neverSplitsAnEmojiWhenCutting() {
        String name = AttachmentFileNames.displayName("a".repeat(250) + "😀😀😀.pdf");

        assertTrue(name.length() <= AttachmentFileNames.MAX_LENGTH);
        assertTrue(name.endsWith(".pdf"));
        assertTrue(name.codePoints().noneMatch(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE),
                "no half emoji");
    }

    @Test
    void replacesTheExtension() {
        assertEquals("diagram.webp", AttachmentFileNames.withExtension("diagram.png", "webp"));
        assertEquals("photo.final.webp", AttachmentFileNames.withExtension("photo.final.JPG", "webp"));
        assertEquals("noext.webp", AttachmentFileNames.withExtension("noext", "webp"));
    }
}