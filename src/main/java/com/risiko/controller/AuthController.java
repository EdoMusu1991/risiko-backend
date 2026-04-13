package com.risiko.controller;

import com.risiko.dto.AuthResponse;
import com.risiko.dto.LoginRequest;
import com.risiko.dto.RegisterRequest;
import com.risiko.model.Utente;
import com.risiko.security.JwtUtil;
import com.risiko.service.UtenteService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UtenteService utenteService;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authManager;

    public AuthController(UtenteService utenteService, JwtUtil jwtUtil,
                          AuthenticationManager authManager) {
        this.utenteService = utenteService;
        this.jwtUtil = jwtUtil;
        this.authManager = authManager;
    }

    /**
     * POST /api/auth/register
     * Registra un nuovo utente e restituisce il token JWT.
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {
        try {
            Utente u = utenteService.registra(
                    req.username(), req.password(), req.email(), req.avatar());
            String token = jwtUtil.generateToken(u.getUsername());
            return ResponseEntity.ok(new AuthResponse(token, u.getUsername(), u.getAvatar(), u.getId()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("errore", e.getMessage()));
        }
    }

    /**
     * POST /api/auth/login
     * Autentica l'utente e restituisce il token JWT.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        try {
            authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.username(), req.password()));
            Utente u = utenteService.findByUsername(req.username());
            String token = jwtUtil.generateToken(u.getUsername());
            return ResponseEntity.ok(new AuthResponse(token, u.getUsername(), u.getAvatar(), u.getId()));
        } catch (AuthenticationException e) {
            return ResponseEntity.status(401).body(Map.of("errore", "Credenziali non valide"));
        }
    }
}
