package team.sprintory_backend.dependency;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import team.sprintory_backend.dependency.DependencyDtos.*;
import team.sprintory_backend.security.userdetails.AppUserDetails;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DependencyController {
    private final DependencyService service;
    @GetMapping("/tasks/{id}/dependencies")
    public Response get(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id) {
        return service.get(principal.getUser(),id);
    }
    @PostMapping("/tasks/{id}/dependencies")
    @ResponseStatus(HttpStatus.CREATED)
    public Edge add(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id, @Valid @RequestBody CreateRequest request) {
        return service.add(principal.getUser(),id,request);
    }
    @DeleteMapping("/dependencies/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal AppUserDetails principal,@PathVariable Long id) {
        service.remove(principal.getUser(),id);
    }
}
