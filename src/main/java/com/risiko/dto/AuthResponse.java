package com.risiko.dto;

/** Risposta dopo login/registrazione */
public record AuthResponse(
    String token,
    String username,
    String avatar,
    Long userId
) {}
