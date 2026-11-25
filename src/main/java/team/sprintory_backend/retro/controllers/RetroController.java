package team.sprintory_backend.retro.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import team.sprintory_backend.retro.dto.RetroActionRequest;
import team.sprintory_backend.retro.dto.RetroActionResponse;
import team.sprintory_backend.retro.dto.RetroItemRequest;
import team.sprintory_backend.retro.dto.RetroItemResponse;
import team.sprintory_backend.retro.dto.RetroResponse;
import team.sprintory_backend.retro.dto.RetroVoteResponse;
import team.sprintory_backend.retro.services.RetroService;
import team.sprintory_backend.security.userdetails.AppUserDetails;

/** Sprint retrospectives (R31), contract docs/retrospectives-docs.md. Rules live in {@link RetroService}. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RetroController {

    private final RetroService retros;

    /** §3.1: 201 when the retro was created, 200 when the sprint already had one. */
    @PostMapping("/sprints/{sprintId}/retro")
    public ResponseEntity<RetroResponse> open(@AuthenticationPrincipal AppUserDetails principal,
                                              @PathVariable Long sprintId) {
        RetroService.Opened opened = retros.open(principal.getUser(), sprintId);
        return ResponseEntity.status(opened.created() ? HttpStatus.CREATED : HttpStatus.OK).body(opened.retro());
    }

    /** §3.2. */
    @GetMapping("/retros/{retroId}")
    public RetroResponse get(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long retroId) {
        return retros.get(principal.getUser(), retroId);
    }

    /** §3.3. */
    @PostMapping("/retros/{retroId}/items")
    public ResponseEntity<RetroItemResponse> addItem(@AuthenticationPrincipal AppUserDetails principal,
                                                     @PathVariable Long retroId,
                                                     @Valid @RequestBody RetroItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(retros.addItem(principal.getUser(), retroId, request));
    }

    /** §3.4: the body only; the column cannot change. */
    @PatchMapping("/retro-items/{itemId}")
    public RetroItemResponse editItem(@AuthenticationPrincipal AppUserDetails principal,
                                      @PathVariable Long itemId, @Valid @RequestBody CommentRequest request) {
        return retros.editItem(principal.getUser(), itemId, request);
    }

    /** §3.5. */
    @DeleteMapping("/retro-items/{itemId}")
    public ResponseEntity<Void> deleteItem(@AuthenticationPrincipal AppUserDetails principal,
                                           @PathVariable Long itemId) {
        retros.deleteItem(principal.getUser(), itemId);
        return ResponseEntity.noContent().build();
    }

    /** §3.6. */
    @PostMapping("/retro-items/{itemId}/votes")
    public RetroVoteResponse vote(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long itemId) {
        return retros.vote(principal.getUser(), itemId);
    }

    /** §3.8: creates the task and links it to the retro. */
    @PostMapping("/retros/{retroId}/actions")
    public ResponseEntity<RetroActionResponse> createAction(@AuthenticationPrincipal AppUserDetails principal,
                                                            @PathVariable Long retroId,
                                                            @Valid @RequestBody RetroActionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(retros.createAction(principal.getUser(), retroId, request));
    }

    /** §3.7: 200 with the new state, also when the caller had no vote. */
    @DeleteMapping("/retro-items/{itemId}/votes")
    public RetroVoteResponse unvote(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long itemId) {
        return retros.unvote(principal.getUser(), itemId);
    }
}
