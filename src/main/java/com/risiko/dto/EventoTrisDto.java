package com.risiko.dto;

public record EventoTrisDto(
        String colore,
        int    bonus,
        String tipo   // "DIVERSI" | "UGUALI" | "JOLLY"
) {}
