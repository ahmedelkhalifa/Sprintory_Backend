package team.sprintory_backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import team.sprintory_backend.attachment.services.AttachmentCleanupJob;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.models.ProjectMember;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.security.auth.AuthCookieFactory;
import team.sprintory_backend.security.jwt.JwtService;
import team.sprintory_backend.task.api.TaskHistoryOrder;
import team.sprintory_backend.task.dto.CreateTaskRequest;
import team.sprintory_backend.task.dto.TaskHistoryEntryResponse;
import team.sprintory_backend.task.models.TaskHistoryType;
import team.sprintory_backend.task.services.TaskApiService;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The PMP-30 acceptance criteria over real HTTP: a running server with its real Tomcat multipart
 * limits, security filters and response headers, the auth cookie, and a disposable PostgreSQL.
 * Docker must be running. Nothing is rolled back, so every test builds its own workspace.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class AttachmentHttpIntegrationTest {

    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EXE = "MZ\u0090\u0000\u0003 This program cannot be run in DOS mode"
            .getBytes(StandardCharsets.ISO_8859_1);

    @Value("${local.server.port}") private int port;
    @Value("${app.attachments.dir}") private String attachmentsDir;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository workspaceMembers;
    @Autowired private ProjectMemberRepository projectMembers;
    @Autowired private ProjectRepository projects;
    @Autowired private ProjectService projectService;
    @Autowired private TaskApiService tasks;
    @Autowired private JwtService jwt;

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private RestClient http;
    /** Created the project: its Project Manager. */
    private User owner;
    private User grace;
    private User mobeen;
    private User viewer;
    /** In the workspace, not in the project. */
    private User outsider;
    /** Workspace admin, not in the project. */
    private User admin;
    /** In another workspace altogether. */
    private User stranger;
    private Long projectId;
    private Long taskId;

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port).build();
        String marker = UUID.randomUUID().toString().substring(0, 8);
        owner = user("Owner", marker);
        grace = user("Grace", marker);
        mobeen = user("Mobeen", marker);
        viewer = user("Vera", marker);
        outsider = user("Otto", marker);
        admin = user("Adam", marker);
        stranger = user("Stan", marker);
        Workspace workspace = workspaces.save(Workspace.builder().name("Attachments").slug("att-" + marker).build());
        for (User u : List.of(owner, grace, mobeen, viewer, outsider)) {
            workspaceMembers.save(WorkspaceMember.builder().workspace(workspace).user(u).role(Role.WORKSPACE_MEMBER).build());
        }
        workspaceMembers.save(WorkspaceMember.builder().workspace(workspace).user(admin).role(Role.WORKSPACE_ADMIN).build());

        projectId = projectService.create(owner, workspace.getId(),
                new CreateProjectRequest("Attachments", "ATT", ProjectTemplate.KANBAN, null)).id();
        member(grace, ProjectRole.MEMBER);
        member(mobeen, ProjectRole.MEMBER);
        member(viewer, ProjectRole.VIEWER);
        taskId = tasks.create(owner, projectId,
                new CreateTaskRequest("Fix login", null, null, null, null, null, null)).id();
    }

    // --- upload, list, download, preview ---

    @Test
    void aMemberUploadsAPdfAndAnImageAndEveryoneInTheProjectCanOpenThem() throws IOException {
        Response uploaded = upload(grace, part("Requirements v2.pdf", PDF), part("diagram.png", png(900, 600)));

        assertEquals(201, uploaded.status(), uploaded.text());
        JsonNode pdf = byName(uploaded.json(), "Requirements v2.pdf");
        JsonNode image = byName(uploaded.json(), "diagram.webp");
        assertEquals("application/pdf", pdf.get("contentType").asString());
        assertEquals(PDF.length, pdf.get("sizeBytes").asLong());
        assertEquals("Grace " + "Test", pdf.get("uploader").get("name").asString());
        assertTrue(pdf.get("uploadedAt").isString());
        assertEquals("image/webp", image.get("contentType").asString(), "the PNG was converted");
        assertTrue(image.get("image").asBoolean());

        JsonNode listed = get(viewer, path(projectId, taskId)).json();
        assertEquals(2, listed.size());
        assertFalse(byName(listed, "Requirements v2.pdf").get("canDelete").asBoolean(), "a Viewer cannot delete");

        Response download = get(viewer, pdf.get("downloadUrl").asString());
        assertEquals(200, download.status());
        assertArrayEquals(PDF, download.body());
        assertEquals("application/pdf", download.headers().getContentType().toString());
        String disposition = download.headers().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.startsWith("attachment;"), disposition);
        assertTrue(disposition.contains("Requirements"), disposition);
        assertEquals(List.of("nosniff"), download.headers().get("X-Content-Type-Options"));
        assertEquals("default-src 'none'; sandbox", download.headers().getFirst("Content-Security-Policy"));
        assertEquals("no-store", download.headers().getCacheControl());

        Response preview = get(viewer, image.get("previewUrl").asString());
        assertEquals(200, preview.status());
        assertEquals("image/webp", preview.headers().getContentType().toString());
        assertTrue(preview.headers().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("inline;"));
        assertTrue(preview.headers().getCacheControl().contains("max-age=300"));
        assertTrue(preview.headers().getCacheControl().contains("private"));
        assertEquals("RIFF", new String(preview.body(), 0, 4, StandardCharsets.US_ASCII));

        Response pdfPreview = get(viewer, pdf.get("downloadUrl").asString() + "/preview");
        assertError(pdfPreview, 404, "RESOURCE_NOT_FOUND", "Attachment not found.");
    }

    @Test
    void aFileNameWithAPathIsShownWithoutItAndStoredUnderARandomKey() throws IOException {
        Response uploaded = upload(grace, part("../../x.pdf", PDF));

        assertEquals(201, uploaded.status(), uploaded.text());
        assertEquals("x.pdf", uploaded.json().get(0).get("fileName").asString());
        try (Stream<Path> files = Files.walk(Path.of(attachmentsDir))) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().equals("x.pdf")));
        }
    }

    // --- refusals ---

    @Test
    void disguisedAndForbiddenFilesAreRefusedAndNothingIsSaved() {
        assertError(upload(grace, part("invoice.pdf", EXE)), 415, "FILE_TYPE_NOT_ALLOWED",
                "invoice.pdf: the file's content does not match its extension.");
        assertError(upload(grace, part("setup.exe", EXE)), 415, "FILE_TYPE_NOT_ALLOWED",
                "setup.exe: this file type is not allowed.");
        assertError(upload(grace, part("page.html", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8))),
                415, "FILE_TYPE_NOT_ALLOWED", "page.html: this file type is not allowed.");
        assertError(upload(grace, part("ok.pdf", PDF), part("evil.pdf", EXE)), 415, "FILE_TYPE_NOT_ALLOWED",
                "evil.pdf: the file's content does not match its extension.");

        assertEquals(0, get(grace, path(projectId, taskId)).json().size(), "all or nothing");
    }

    @Test
    void aFileOverTwentyMegabytesIsRefusedByTheServer() {
        byte[] big = new byte[20 * 1024 * 1024 + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);

        assertError(upload(grace, part("big.pdf", big)), 413, "FILE_TOO_LARGE", "File too large (max 20 MB).");
        assertEquals(0, get(grace, path(projectId, taskId)).json().size());
    }

    @Test
    void moreThanFiveFilesOrNoneAreRefused() {
        Object[] six = Stream.generate(() -> part("a.pdf", PDF)).limit(6).toArray();

        assertError(upload(grace, six), 400, "TOO_MANY_FILES", "You can upload at most 5 files at once.");
        assertError(send(grace, HttpMethod.POST, path(projectId, taskId), null), 400, "BAD_REQUEST",
                "Choose at least one file to upload.");
    }

    // --- who may do what ---

    @Test
    void aViewerCannotUploadOrDeleteAndOnlyTheUploaderOrThePmDeletes() {
        assertError(upload(viewer, part("a.pdf", PDF)), 403, "ACCESS_DENIED", null);
        String first = upload(grace, part("first.pdf", PDF)).json().get(0).get("downloadUrl").asString();
        String second = upload(grace, part("second.pdf", PDF)).json().get(0).get("downloadUrl").asString();

        assertError(send(viewer, HttpMethod.DELETE, first, null), 403, "ACCESS_DENIED", null);
        assertError(send(mobeen, HttpMethod.DELETE, first, null), 403, "ACCESS_DENIED", null);
        assertEquals(204, send(grace, HttpMethod.DELETE, first, null).status(), "the uploader");
        assertEquals(204, send(owner, HttpMethod.DELETE, second, null).status(), "the Project Manager");
        assertError(send(owner, HttpMethod.DELETE, second, null), 404, "RESOURCE_NOT_FOUND", "Attachment not found.");

        assertEquals(0, get(grace, path(projectId, taskId)).json().size(), "both disappeared from the task");
        assertError(get(grace, first), 404, "RESOURCE_NOT_FOUND", "Attachment not found.");

        List<TaskHistoryEntryResponse> history = tasks.history(owner, projectId, taskId, TaskHistoryOrder.OLDEST,
                PageRequest.of(0, 20)).content();
        List<String> attachmentLines = new ArrayList<>();
        for (TaskHistoryEntryResponse entry : history) {
            if (entry.type() == TaskHistoryType.ATTACHMENT_ADDED) attachmentLines.add("added " + entry.newValue());
            if (entry.type() == TaskHistoryType.ATTACHMENT_REMOVED) attachmentLines.add("removed " + entry.oldValue());
        }
        assertEquals(List.of("added first.pdf", "added second.pdf", "removed first.pdf", "removed second.pdf"),
                attachmentLines);
    }

    @Test
    void peopleOutsideTheProjectGetNotFoundAndAnAdminGetsForbidden() {
        String url = upload(grace, part("secret.pdf", PDF)).json().get(0).get("downloadUrl").asString();

        for (User nonMember : List.of(stranger, outsider)) {
            assertError(get(nonMember, url), 404, "RESOURCE_NOT_FOUND", "Attachment not found.");
            assertError(get(nonMember, url + "/preview"), 404, "RESOURCE_NOT_FOUND", "Attachment not found.");
            assertError(send(nonMember, HttpMethod.DELETE, url, null), 404, "RESOURCE_NOT_FOUND", "Attachment not found.");
            assertError(get(nonMember, path(projectId, taskId)), 404, "RESOURCE_NOT_FOUND", "Project not found.");
        }
        assertError(get(stranger, "/api/v1/attachments/999999999"), 404, "RESOURCE_NOT_FOUND", "Attachment not found.");
        assertError(get(admin, url), 403, "ACCESS_DENIED", null);
        int anonymous = http.get().uri(url).exchange((req, res) -> res.getStatusCode().value());
        assertEquals(401, anonymous, "not logged in");
    }

    @Test
    void aDeletedTasksAttachmentsCannotBeDownloadedUntilItIsRestored() {
        String url = upload(grace, part("spec.pdf", PDF)).json().get(0).get("downloadUrl").asString();

        tasks.delete(owner, projectId, taskId);
        assertError(get(grace, url), 404, "RESOURCE_NOT_FOUND", "Attachment not found.");
        assertError(get(grace, path(projectId, taskId)), 404, "RESOURCE_NOT_FOUND", "Task not found.");

        tasks.restore(owner, projectId, taskId);
        Response download = get(grace, url);
        assertEquals(200, download.status());
        assertArrayEquals(PDF, download.body());
    }

    // --- nightly clean-up ---

    @Test
    void theNightlyCleanUpRemovesAttachmentsDeletedLongAgoAndKeepsTheRest() {
        JsonNode uploaded = upload(grace, part("old.pdf", PDF), part("recent.pdf", PDF), part("live.pdf", PDF)).json();
        long oldId = byName(uploaded, "old.pdf").get("id").asLong();
        long recentId = byName(uploaded, "recent.pdf").get("id").asLong();
        long liveId = byName(uploaded, "live.pdf").get("id").asLong();
        assertEquals(204, send(grace, HttpMethod.DELETE, "/api/v1/attachments/" + oldId, null).status());
        assertEquals(204, send(grace, HttpMethod.DELETE, "/api/v1/attachments/" + recentId, null).status());
        jdbc.update("update task_attachments set deleted_at = now() - interval '31 days' where id = ?", oldId);
        String oldKey = storageKey(oldId);
        // Old enough on disk too, as it would be after 31 days.
        backdate(oldKey);

        AttachmentCleanupJob.Result result = cleanup.run();

        assertTrue(result.rows() >= 1);
        assertEquals(0, jdbc.queryForObject("select count(*) from task_attachments where id = ?", Integer.class, oldId));
        assertFalse(Files.exists(filePath(oldKey)), "the file of the expired attachment is gone");
        assertTrue(Files.exists(filePath(storageKey(recentId))), "deleted 0 days ago: kept for now");
        assertEquals(200, get(grace, "/api/v1/attachments/" + liveId).status());
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AttachmentCleanupJob cleanup;

    private String storageKey(long attachmentId) {
        return jdbc.queryForObject("select storage_key from task_attachments where id = ?", String.class, attachmentId);
    }

    private Path filePath(String key) {
        return Path.of(attachmentsDir).toAbsolutePath().normalize()
                .resolve(key.substring(0, 2)).resolve(key.substring(2, 4)).resolve(key);
    }

    private void backdate(String key) {
        try {
            Files.setLastModifiedTime(filePath(key),
                    java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(31))));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- helpers ---

    private record Response(int status, HttpHeaders headers, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        JsonNode json() {
            return JSON.readTree(body);
        }
    }

    private Response upload(User as, Object... files) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        for (Object file : files) {
            parts.add("files", file);
        }
        return send(as, HttpMethod.POST, path(projectId, taskId), parts);
    }

    private Response get(User as, String uri) {
        return send(as, HttpMethod.GET, uri, null);
    }

    private Response send(User as, HttpMethod method, String uri, MultiValueMap<String, Object> multipart) {
        RestClient.RequestBodySpec request = http.method(method).uri(uri)
                .header(HttpHeaders.COOKIE, AuthCookieFactory.ACCESS_COOKIE + "=" + jwt.generateAccessToken(as.getEmail()));
        if (multipart != null) {
            request.contentType(MediaType.MULTIPART_FORM_DATA).body(multipart);
        }
        return request.exchange((req, res) -> new Response(res.getStatusCode().value(), res.getHeaders(),
                res.getBody().readAllBytes()));
    }

    private static void assertError(Response response, int status, String code, String message) {
        assertEquals(status, response.status(), response.text());
        JsonNode body = response.json();
        assertEquals(code, body.get("code").asString());
        if (message != null) {
            assertEquals(message, body.get("message").asString());
        }
    }

    private static JsonNode byName(JsonNode list, String fileName) {
        for (JsonNode item : list) {
            if (item.get("fileName").asString().equals(fileName)) return item;
        }
        throw new AssertionError("No attachment named " + fileName + " in " + list);
    }

    private static String path(Long projectId, Long taskId) {
        return "/api/v1/projects/" + projectId + "/tasks/" + taskId + "/attachments";
    }

    private static ByteArrayResource part(String fileName, byte[] content) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    private User user(String firstName, String marker) {
        return users.save(User.builder().firstName(firstName).lastName("Test")
                .email(firstName.toLowerCase() + "-att-" + marker + "@example.com").enabled(true).build());
    }

    private void member(User user, ProjectRole role) {
        projectMembers.save(ProjectMember.builder().project(projects.getReferenceById(projectId))
                .user(user).roles(Set.of(role)).build());
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(3);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, ((x * 255 / width) << 16) | ((y * 255 / height) << 8) | random.nextInt(32));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}