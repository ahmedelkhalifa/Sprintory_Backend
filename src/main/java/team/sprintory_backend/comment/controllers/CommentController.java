package team.sprintory_backend.comment.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import team.sprintory_backend.comment.dto.CommentRequest;
import team.sprintory_backend.comment.dto.CommentResponse;
import team.sprintory_backend.comment.services.CommentService;
import team.sprintory_backend.common.Pageables;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.security.userdetails.AppUserDetails;

/** PMP-133: comments on a task (or a subtask). Contract: docs/comments-docs.md. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{taskId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    /** Oldest first, paged; {@code ?sort=} is ignored. */
    @GetMapping
    public PageResponse<CommentResponse> list(@AuthenticationPrincipal AppUserDetails principal,
                                              @PathVariable Long projectId, @PathVariable Long taskId,
                                              Pageable pageable) {
        return commentService.list(principal.getUser(), projectId, taskId, Pageables.withoutSort(pageable));
    }

    @PostMapping
    public ResponseEntity<CommentResponse> create(@AuthenticationPrincipal AppUserDetails principal,
                                                  @PathVariable Long projectId, @PathVariable Long taskId,
                                                  @Valid @RequestBody CommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commentService.create(principal.getUser(), projectId, taskId, request));
    }

    @PatchMapping("/{commentId}")
    public CommentResponse update(@AuthenticationPrincipal AppUserDetails principal,
                                  @PathVariable Long projectId, @PathVariable Long taskId,
                                  @PathVariable Long commentId,
                                  @Valid @RequestBody CommentRequest request) {
        return commentService.update(principal.getUser(), projectId, taskId, commentId, request);
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppUserDetails principal,
                                       @PathVariable Long projectId, @PathVariable Long taskId,
                                       @PathVariable Long commentId) {
        commentService.delete(principal.getUser(), projectId, taskId, commentId);
        return ResponseEntity.noContent().build();
    }
}