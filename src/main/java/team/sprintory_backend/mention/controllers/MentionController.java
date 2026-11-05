package team.sprintory_backend.mention.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import team.sprintory_backend.mention.dto.MentionablesResponse;
import team.sprintory_backend.mention.services.MentionService;
import team.sprintory_backend.security.userdetails.AppUserDetails;

/** PMP-136: the @ suggestion list. Contract: docs/mentions-docs.md §3. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/mentionables")
@RequiredArgsConstructor
public class MentionController {

    private final MentionService mentionService;

    @GetMapping
    public MentionablesResponse mentionables(@AuthenticationPrincipal AppUserDetails principal,
                                             @PathVariable Long projectId,
                                             @RequestParam(required = false) String q) {
        return mentionService.mentionables(principal.getUser(), projectId, q);
    }
}