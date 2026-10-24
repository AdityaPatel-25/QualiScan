package com.qualiscan.api;

import com.qualiscan.domain.AppUser;
import com.qualiscan.domain.Role;
import com.qualiscan.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller for administrative user management endpoints.
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AuthService authService;

    public AdminUserController(AuthService authService) {
        this.authService = authService;
    }

    public record UserRequest(
            @NotBlank String username,
            @NotBlank @Size(min = 8) String password,
            @NotNull Role role,
            boolean enabled
    ) {}

    public record UpdateRequest(
            Role role,
            Boolean enabled,
            String password
    ) {}

    @GetMapping
    public List<Map<String, Object>> list() {
        return authService.listUsers().stream().map(this::dto).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody UserRequest req) {
        AppUser user = authService.createUser(req.username(), req.password(), req.role(), req.enabled());
        return dto(user);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody UpdateRequest req) {
        AppUser user = authService.updateUser(id, req.role(), req.enabled(), req.password());
        return dto(user);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        authService.deleteUser(id);
    }

    private Map<String, Object> dto(AppUser u) {
        return Map.of(
                "id", u.getId(),
                "username", u.getUsername(),
                "role", u.getRole(),
                "enabled", u.isEnabled()
        );
    }
}
