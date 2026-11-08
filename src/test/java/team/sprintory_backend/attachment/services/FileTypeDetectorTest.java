package team.sprintory_backend.attachment.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import team.sprintory_backend.attachment.exceptions.FileTypeNotAllowedException;
import team.sprintory_backend.attachment.models.AttachmentType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileTypeDetectorTest {

    private static final byte[] PDF = ascii("%PDF-1.7\n%âãÏÓ\n1 0 obj\n");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};
    private static final byte[] GIF = ascii("GIF89a\u0001\u0000\u0001\u0000");
    private static final byte[] WEBP = ascii("RIFF$\u0000\u0000\u0000WEBPVP8 ");
    private static final byte[] EXE = ascii("MZ\u0090\u0000\u0003\u0000\u0000\u0000This program cannot be run in DOS mode");

    private static final String NOT_ALLOWED = ": this file type is not allowed.";
    private static final String MISMATCH = ": the file's content does not match its extension.";

    private final FileTypeDetector detector = new FileTypeDetector();

    @TempDir
    Path dir;

    // --- accepted ---

    @Test
    void recognizesEachBinaryTypeByItsSignature() throws IOException {
        assertEquals(AttachmentType.PDF, detect("Requirements v2.pdf", PDF));
        assertEquals(AttachmentType.PNG, detect("diagram.png", PNG));
        assertEquals(AttachmentType.JPEG, detect("photo.jpg", JPEG));
        assertEquals(AttachmentType.JPEG, detect("photo.jpeg", JPEG));
        assertEquals(AttachmentType.GIF, detect("loading.gif", GIF));
        assertEquals(AttachmentType.WEBP, detect("shot.webp", WEBP));
    }

    @Test
    void extensionsAreComparedIgnoringCase() throws IOException {
        assertEquals(AttachmentType.PDF, detect("REPORT.PDF", PDF));
        assertEquals(AttachmentType.JPEG, detect("IMG_0001.JPG", JPEG));
    }

    @Test
    void recognizesOfficeFilesByTheirParts() throws IOException {
        assertEquals(AttachmentType.DOCX, detect("spec.docx", zip("[Content_Types].xml", "word/document.xml")));
        assertEquals(AttachmentType.XLSX, detect("budget.xlsx", zip("[Content_Types].xml", "xl/workbook.xml")));
        assertEquals(AttachmentType.PPTX, detect("demo.pptx", zip("[Content_Types].xml", "ppt/presentation.xml")));
    }

    @Test
    void acceptsPlainZipsIncludingEmptyOnesAndOfficeFilesNamedZip() throws IOException {
        assertEquals(AttachmentType.ZIP, detect("source.zip", zip("src/Main.java", "setup.exe")));
        assertEquals(AttachmentType.ZIP, detect("empty.zip", zip()));
        assertEquals(AttachmentType.ZIP, detect("spec.zip",
                zip("[Content_Types].xml", "word/document.xml", "word/vbaProject.bin")));
    }

    @Test
    void acceptsTextFilesInAnyEightBitEncoding() throws IOException {
        assertEquals(AttachmentType.TXT, detect("notes.txt", ascii("Line one\r\nLine two\twith a tab\n")));
        assertEquals(AttachmentType.MD, detect("README.md", "# Başlık\n\n- madde\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(AttachmentType.CSV, detect("export.csv",
                "ad;şehir\nIşıl;İzmir\n".getBytes(Charset.forName("windows-1254"))));
        byte[] withBom = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, ascii("a,b\n1,2\n"));
        assertEquals(AttachmentType.CSV, detect("bom.csv", withBom));
    }

    @Test
    void markupFurtherInsideATextFileIsFine() throws IOException {
        assertEquals(AttachmentType.MD, detect("guide.md", ascii("# Embedding\n\nUse <html> and <svg> tags.\n")));
    }

    // --- refused: type not allowed ---

    @ParameterizedTest
    @ValueSource(strings = {"setup.exe", "run.sh", "build.bat", "page.html", "page.htm", "logo.svg",
            "old.doc", "macro.docm", "archive.tar.gz", "README", "trailing-dot."})
    void refusesNamesOutsideTheAllowList(String fileName) {
        FileTypeNotAllowedException e = assertThrows(FileTypeNotAllowedException.class,
                () -> detect(fileName, ascii("anything")));
        assertEquals(fileName + NOT_ALLOWED, e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<!DOCTYPE html><html><body>hi</body></html>",
            "  \n<html><script>alert(1)</script></html>",
            "<script>alert(1)</script>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>",
            "<?xml version=\"1.0\"?><svg/>",
            "#!/bin/sh\nrm -rf /\n"})
    void refusesMarkupAndScriptsHiddenInATextFile(String content) {
        FileTypeNotAllowedException e = assertThrows(FileTypeNotAllowedException.class,
                () -> detect("notes.txt", ascii(content)));
        assertEquals("notes.txt" + NOT_ALLOWED, e.getMessage());
    }

    @Test
    void refusesMarkupAfterAByteOrderMark() {
        byte[] content = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, ascii("<HTML>"));
        assertThrows(FileTypeNotAllowedException.class, () -> detect("notes.md", content));
    }

    @Test
    void refusesOfficeFilesWithMacros() {
        FileTypeNotAllowedException e = assertThrows(FileTypeNotAllowedException.class,
                () -> detect("budget.xlsx", zip("[Content_Types].xml", "xl/workbook.xml", "xl/vbaProject.bin")));
        assertEquals("budget.xlsx: files with macros are not allowed.", e.getMessage());
    }

    // --- refused: content does not match the extension ---

    @Test
    void refusesAnExecutableRenamedToAnAllowedExtension() {
        for (String fileName : new String[]{"invoice.pdf", "photo.png", "notes.txt", "data.zip"}) {
            FileTypeNotAllowedException e = assertThrows(FileTypeNotAllowedException.class,
                    () -> detect(fileName, EXE));
            assertEquals(fileName + MISMATCH, e.getMessage());
        }
    }

    @Test
    void refusesARealFileWithAnotherAllowedTypesExtension() {
        assertThrows(FileTypeNotAllowedException.class, () -> detect("report.docx", PDF));
        assertThrows(FileTypeNotAllowedException.class, () -> detect("photo.png", JPEG));
        assertThrows(FileTypeNotAllowedException.class, () -> detect("notes.txt", PDF));
        assertThrows(FileTypeNotAllowedException.class, () -> detect("image.webp", ascii("RIFF$\u0000\u0000\u0000WAVEfmt ")));
    }

    @Test
    void refusesAPlainZipOrABrokenZipNamedAsAnOfficeFile() {
        assertThrows(FileTypeNotAllowedException.class, () -> detect("spec.docx", zip("readme.txt")));
        assertThrows(FileTypeNotAllowedException.class,
                () -> detect("spec.docx", zip("[Content_Types].xml", "xl/workbook.xml")));
        byte[] broken = concat(new byte[]{'P', 'K', 3, 4}, ascii("not really a zip at all"));
        FileTypeNotAllowedException e = assertThrows(FileTypeNotAllowedException.class,
                () -> detect("spec.docx", broken));
        assertEquals("spec.docx" + MISMATCH, e.getMessage());
    }

    @Test
    void refusesBinaryBytesInATextFileWhereverTheyAre() {
        byte[] lateNul = concat(ascii("a".repeat(200_000)), new byte[]{0});
        assertThrows(FileTypeNotAllowedException.class, () -> detect("log.txt", lateNul));
        byte[] utf16 = "hello".getBytes(StandardCharsets.UTF_16LE);
        FileTypeNotAllowedException e = assertThrows(FileTypeNotAllowedException.class,
                () -> detect("utf16.txt", utf16));
        assertEquals("utf16.txt" + MISMATCH, e.getMessage());
    }

    @Test
    void refusesUnrecognizedBinaryContentForABinaryExtension() {
        assertThrows(FileTypeNotAllowedException.class, () -> detect("scan.pdf", ascii("just some text")));
    }

    // --- helpers ---

    private AttachmentType detect(String fileName, byte[] content) throws IOException {
        Path file = Files.createTempFile(dir, "upload", ".tmp");
        Files.write(file, content);
        return detector.detect(file, fileName);
    }

    private static byte[] zip(String... entries) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream out = new ZipOutputStream(bytes)) {
                for (String entry : entries) {
                    out.putNextEntry(new ZipEntry(entry));
                    out.write(ascii("<xml/>"));
                    out.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}