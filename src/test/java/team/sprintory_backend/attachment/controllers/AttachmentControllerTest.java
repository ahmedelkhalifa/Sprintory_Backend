package team.sprintory_backend.attachment.controllers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import team.sprintory_backend.attachment.dto.AttachmentFile;
import team.sprintory_backend.attachment.dto.AttachmentResponse;
import team.sprintory_backend.attachment.services.AttachmentService;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.user.models.User;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The controller passes the caller and ids on and sets the file headers; the rules are tested in the service. */
class AttachmentControllerTest {

    private AttachmentService service;
    private AttachmentController controller;
    private User user;
    private AppUserDetails principal;

    @BeforeEach
    void setUp() {
        service = mock(AttachmentService.class);
        controller = new AttachmentController(service);
        user = User.builder().id(42L).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();
        principal = new AppUserDetails(user);
    }

    @Test
    void listAsksTheService() {
        List<AttachmentResponse> list = List.of(attachment());
        when(service.list(user, 3L, 5L)).thenReturn(list);

        assertSame(list, controller.list(principal, 3L, 5L));
    }

    @Test
    void uploadAnswers201WithTheNewAttachments() {
        List<MultipartFile> files = List.of(new MockMultipartFile("files", "a.pdf", null, new byte[]{1}));
        List<AttachmentResponse> created = List.of(attachment());
        when(service.upload(user, 3L, 5L, files)).thenReturn(created);

        ResponseEntity<List<AttachmentResponse>> response = controller.upload(principal, 3L, 5L, files);

        assertEquals(201, response.getStatusCode().value());
        assertSame(created, response.getBody());
    }

    @Test
    void downloadIsAnUncachedAttachmentThatCannotRunInThePage() {
        Resource content = new ByteArrayResource(new byte[]{1, 2, 3});
        when(service.download(user, 91L)).thenReturn(new AttachmentFile("Görev planı.pdf", "application/pdf", 3, content));

        ResponseEntity<Resource> response = controller.download(principal, 91L);

        HttpHeaders headers = response.getHeaders();
        assertSame(content, response.getBody());
        assertEquals("application/pdf", headers.getContentType().toString());
        assertEquals(3, headers.getContentLength());
        String disposition = headers.getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.startsWith("attachment;"), disposition);
        assertTrue(disposition.contains("filename*=UTF-8''G%C3%B6rev%20plan%C4%B1.pdf"), disposition);
        assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"));
        assertEquals("default-src 'none'; sandbox", headers.getFirst("Content-Security-Policy"));
        assertEquals("no-store", headers.getCacheControl());
    }

    @Test
    void previewIsInlineAndCachedBrieflyByTheViewerOnly() {
        when(service.preview(user, 91L)).thenReturn(
                new AttachmentFile("shot.webp", "image/webp", 3, new ByteArrayResource(new byte[]{1, 2, 3})));

        HttpHeaders headers = controller.preview(principal, 91L).getHeaders();

        assertEquals("image/webp", headers.getContentType().toString());
        assertTrue(headers.getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("inline;"));
        assertEquals("max-age=300, private", headers.getCacheControl());
        assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"));
    }

    @Test
    void deleteAnswers204WithoutABody() {
        ResponseEntity<Void> response = controller.delete(principal, 91L);

        verify(service).delete(user, 91L);
        assertEquals(204, response.getStatusCode().value());
        assertNull(response.getBody());
    }

    private static AttachmentResponse attachment() {
        return new AttachmentResponse(91L, 5L, "a.pdf", "application/pdf", 1, false, null, null,
                "/api/v1/attachments/91", null, true);
    }
}