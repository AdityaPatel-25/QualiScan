package com.qualiscan.service;

import com.qualiscan.domain.AppUser;
import com.qualiscan.domain.Role;
import com.qualiscan.repo.AppUserRepository;
import com.qualiscan.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * Service handling authentication, token generation, and user management.
 */
@Service
@Transactional
public class AuthService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(AppUserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public record LoginResult(String token, String role, Instant expiresAt) {}

    /**
     * Authenticates a user by username and password, issuing a JWT on success.
     */
    @Transactional(readOnly = true)
    public LoginResult login(String username, String password) {
        AppUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }

        if (!user.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User account is disabled");
        }

        String token = jwtService.issue(user.getUsername(), user.getRole().name());
        return new LoginResult(token, user.getRole().name(), jwtService.expiry());
    }

    /**
     * Lists all application users.
     */
    @Transactional(readOnly = true)
    public List<AppUser> listUsers() {
        return userRepository.findAll();
    }

    /**
     * Creates a new user with a hashed password.
     */
    public AppUser createUser(String username, String rawPassword, Role role, boolean enabled) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        AppUser user = new AppUser(username, passwordEncoder.encode(rawPassword), role);
        user.setEnabled(enabled);
        return userRepository.save(user);
    }

    /**
     * Updates an existing user's role, enabled status, or password.
     */
    public AppUser updateUser(Long id, Role role, Boolean enabled, String newPassword) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (role != null) {
            user.setRole(role);
        }
        if (enabled != null) {
            user.setEnabled(enabled);
        }
        if (newPassword != null && !newPassword.isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(newPassword));
        }
        return userRepository.save(user);
    }

    /**
     * Deletes a user by ID.
     */
    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        userRepository.deleteById(id);
    }
}
