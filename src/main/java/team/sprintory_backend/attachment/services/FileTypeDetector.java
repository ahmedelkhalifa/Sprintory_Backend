package team.sprintory_backend.attachment.services;

import org.springframework.stereotype.Component;
import team.sprintory_backend.attachment.exceptions.FileTypeNotAllowedException;
import team.sprintory_backend.attachment.models.AttachmentType;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Decides an upload's real type from its content (R25, R38; docs/attachments-docs.md §3.1).
 * Neither the extension nor the browser's Content-Type is trusted: the content is recognized
 * first, and the extension must then belong to that type.
 *
 * <ul>
 *   <li>Binary types by their signature (magic bytes).</li>
 *   <li>Zips by the names of their entries, read from the central directory without extracting
 *       anything: the Office parts make a docx/xlsx/pptx, and a {@code vbaProject.bin} (macros)
 *       is refused.</li>
 *   <li>Anything without a signature is text only if it holds no binary bytes and does not start
 *       like HTML, SVG, XML or a script. Any 8-bit encoding is accepted.</li>
 * </ul>
 */
@Component
public class FileTypeDetector {

    private static final int HEADER_LENGTH = 16;
    /** How much of a text file is looked at for an HTML, SVG, XML or script start. */
    private static final int TEXT_START_LENGTH = 512;

    private static final byte[] PDF = ascii("%PDF-");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF87 = ascii("GIF87a");
    private static final byte[] GIF89 = ascii("GIF89a");
    private static final byte[] RIFF = ascii("RIFF");
    private static final byte[] WEBP = ascii("WEBP");
    private static final byte[] ZIP = {'P', 'K', 3, 4};
    private static final byte[] EMPTY_ZIP = {'P', 'K', 5, 6};
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** Text that starts like one of these is markup or a script, not plain text. Lower case. */
    private static final List<String> FORBIDDEN_TEXT_STARTS = List.of(
            "<!doctype", "<html", "<head", "<body", "<script", "<iframe", "<svg", "<?xml", "#!");

    private static final String CONTENT_TYPES_PART = "[Content_Types].xml";
    private static final String MACROS_PART = "vbaproject.bin";

    /**
     * The type of the file at {@code file}. {@code fileName} is the display name, used for the
     * extension and in the error message.
     *
     * @throws FileTypeNotAllowedException when the file must be refused
     */
    public AttachmentType detect(Path file, String fileName) {
        String extension = extensionOf(fileName);
        AttachmentType claimed = AttachmentType.fromExtension(extension)
                .orElseThrow(() -> notAllowed(fileName));
        try {
            byte[] header = readHeader(file);
            Optional<AttachmentType> signed = bySignature(header);
            if (signed.isPresent()) {
                AttachmentType type = signed.get() == AttachmentType.ZIP
                        ? zipType(file, fileName, claimed)
                        : signed.get();
                if (type != claimed) {
                    throw mismatch(fileName);
                }
                return type;
            }
            if (!isText(claimed)) {
                throw mismatch(fileName);
            }
            checkText(file, fileName);
            return claimed;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file.", e);
        }
    }

    // --- signatures ---

    private static Optional<AttachmentType> bySignature(byte[] header) {
        if (startsWith(header, PDF)) return Optional.of(AttachmentType.PDF);
        if (startsWith(header, PNG)) return Optional.of(AttachmentType.PNG);
        if (startsWith(header, JPEG)) return Optional.of(AttachmentType.JPEG);
        if (startsWith(header, GIF87) || startsWith(header, GIF89)) return Optional.of(AttachmentType.GIF);
        if (startsWith(header, RIFF) && header.length >= 12
                && Arrays.equals(header, 8, 12, WEBP, 0, 4)) return Optional.of(AttachmentType.WEBP);
        if (startsWith(header, ZIP) || startsWith(header, EMPTY_ZIP)) return Optional.of(AttachmentType.ZIP);
        return Optional.empty();
    }

    // --- zips and Office files ---

    /**
     * An Office file when its parts say so and the name claims that Office type; a plain zip when
     * the name ends in {@code .zip} (even an Office file inside a .zip name is only downloaded).
     * Anything else is a mismatch.
     */
    private static AttachmentType zipType(Path file, String fileName, AttachmentType claimed) throws IOException {
        List<String> names = entryNames(file, fileName);
        AttachmentType office = officeType(names);
        if (office != null && office == claimed) {
            boolean macros = names.stream()
                    .anyMatch(name -> name.toLowerCase(Locale.ROOT).endsWith(MACROS_PART));
            if (macros) {
                throw new FileTypeNotAllowedException(fileName + ": files with macros are not allowed.");
            }
            return office;
        }
        return claimed == AttachmentType.ZIP ? AttachmentType.ZIP : null;
    }

    /** Reads only the central directory; nothing is inflated. A broken zip is a mismatch. */
    private static List<String> entryNames(Path file, String fileName) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            List<String> names = new ArrayList<>();
            zip.stream().map(ZipEntry::getName).forEach(names::add);
            return names;
        } catch (ZipException e) {
            throw mismatch(fileName);
        }
    }

    private static AttachmentType officeType(List<String> names) {
        if (!names.contains(CONTENT_TYPES_PART)) {
            return null;
        }
        if (names.stream().anyMatch(name -> name.startsWith("word/"))) return AttachmentType.DOCX;
        if (names.stream().anyMatch(name -> name.startsWith("xl/"))) return AttachmentType.XLSX;
        if (names.stream().anyMatch(name -> name.startsWith("ppt/"))) return AttachmentType.PPTX;
        return null;
    }

    // --- text ---

    private static boolean isText(AttachmentType type) {
        return type == AttachmentType.TXT || type == AttachmentType.MD || type == AttachmentType.CSV;
    }

    /** Refuses binary bytes anywhere in the file, and markup or a script at its start. */
    private static void checkText(Path file, String fileName) throws IOException {
        byte[] start = new byte[TEXT_START_LENGTH];
        int startLength = 0;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                for (int i = 0; i < read; i++) {
                    if (isBinary(buffer[i])) {
                        throw mismatch(fileName);
                    }
                }
                int copy = Math.min(read, TEXT_START_LENGTH - startLength);
                System.arraycopy(buffer, 0, start, startLength, copy);
                startLength += copy;
            }
        }
        String beginning = leadingText(start, startLength);
        if (FORBIDDEN_TEXT_STARTS.stream().anyMatch(beginning::startsWith)) {
            throw notAllowed(fileName);
        }
    }

    /** Control bytes other than tab, line feed, form feed and carriage return. */
    private static boolean isBinary(byte b) {
        int value = b & 0xFF;
        return value < 0x20 && value != '\t' && value != '\n' && value != '\f' && value != '\r';
    }

    /** The start of the text, without a UTF-8 byte order mark or leading white space, lower case. */
    private static String leadingText(byte[] start, int length) {
        int offset = startsWith(Arrays.copyOf(start, length), UTF8_BOM) ? UTF8_BOM.length : 0;
        // ISO-8859-1 maps every byte to one char, so any 8-bit encoding reads safely here.
        return new String(start, offset, length - offset, StandardCharsets.ISO_8859_1)
                .stripLeading()
                .toLowerCase(Locale.ROOT);
    }

    // --- helpers ---

    /** The part after the last dot, or null when there is none. */
    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? null : fileName.substring(dot + 1);
    }

    private static byte[] readHeader(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(HEADER_LENGTH);
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static FileTypeNotAllowedException notAllowed(String fileName) {
        return new FileTypeNotAllowedException(fileName + ": this file type is not allowed.");
    }

    private static FileTypeNotAllowedException mismatch(String fileName) {
        return new FileTypeNotAllowedException(fileName + ": the file's content does not match its extension.");
    }
}