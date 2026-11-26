package team.sprintory_backend.dependency.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import team.sprintory_backend.dependency.dto.DependencyCreateRequest;
import team.sprintory_backend.dependency.dto.DependencyResponse;
import team.sprintory_backend.dependency.dto.TaskDependenciesResponse;
import team.sprintory_backend.dependency.services.DependencyService;
import team.sprintory_backend.security.userdetails.AppUserDetails;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DependencyController {
    private final DependencyService service;
    @GetMapping("/tasks/{id}/dependencies")
    public TaskDependenciesResponse get(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id) {
        return service.get(principal.getUser(),id);
    }
    @PostMapping("/tasks/{id}/dependencies")
    @ResponseStatus(HttpStatus.CREATED)
    public DependencyResponse add(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id, @Valid @RequestBody DependencyCreateRequest request) {
        return service.add(principal.getUser(),id,request);
    }
    @DeleteMapping("/dependencies/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal AppUserDetails principal,@PathVariable Long id) {
        service.remove(principal.getUser(),id);
    }
}
