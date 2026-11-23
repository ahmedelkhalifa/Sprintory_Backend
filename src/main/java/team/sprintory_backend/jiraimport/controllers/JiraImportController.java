package team.sprintory_backend.jiraimport.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.jiraimport.dto.JiraImportPreview;
import team.sprintory_backend.jiraimport.dto.JiraImportResult;
import team.sprintory_backend.jiraimport.dto.JiraIssueMappingResponse;
import java.util.List;
import team.sprintory_backend.jiraimport.services.JiraImportService;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import java.io.IOException;

/** Privileged one-time Jira migration; disabled unless explicitly enabled on the server. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/jira-import")
@RequiredArgsConstructor
public class JiraImportController {
    private final JiraImportService service;

    @GetMapping("/mappings")
    public List<JiraIssueMappingResponse> mappings(@AuthenticationPrincipal AppUserDetails principal,
                                                  @PathVariable Long projectId) {
        return service.mappings(principal.getUser(), projectId);
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JiraImportPreview preview(@AuthenticationPrincipal AppUserDetails principal,
                                     @PathVariable Long projectId,
                                     @RequestParam("csv") MultipartFile csv,
                                     @RequestParam(value = "sprints", required = false) MultipartFile sprints)
            throws IOException {
        validateUpload(csv);
        return service.preview(principal.getUser(), projectId, csv.getBytes(),
                sprints == null ? null : sprints.getBytes());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JiraImportResult> execute(@AuthenticationPrincipal AppUserDetails principal,
                                                    @PathVariable Long projectId,
                                                    @RequestParam("csv") MultipartFile csv,
                                                    @RequestParam(value = "sprints", required = false) MultipartFile sprints,
                                                    @RequestParam(value = "userMappings", required = false) String userMappings,
                                                    @RequestParam("confirmation") String confirmation)
            throws IOException {
        validateUpload(csv);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.execute(principal.getUser(),
                projectId, csv.getBytes(), sprints == null ? null : sprints.getBytes(),
                userMappings, confirmation));
    }

    private static void validateUpload(MultipartFile csv) {
        if (csv == null || csv.isEmpty() || csv.getSize() > 10_000_000) {
            throw new BadRequestException("A Jira CSV file (up to 10 MB) is required.");
        }
    }
}
