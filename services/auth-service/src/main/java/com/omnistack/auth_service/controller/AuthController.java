package com.omnistack.auth_service.controller;


import com.omnistack.auth_service.dto.LoginRequest;
import com.omnistack.auth_service.dto.RegisterUser;
import com.omnistack.auth_service.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final AuthService authService;


    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterUser req) throws Exception {
        return ResponseEntity.ok(authService.register(req));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) throws Exception {
        // forward credentials to Keycloak token endpoint (resource owner password grant)
        return ResponseEntity.ok(authService.login(req));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader("Authorization") String authHeader) throws Exception {
        return ResponseEntity.ok(authService.validate(authHeader));
    }
    }
