package com.risiko.dto;

/** Riga della classifica */
public record ClassificaDto(
    int posizione,
    Long userId,
    String username,
    String avatar,
    long punti,
    long partite,
    long corrette
) {}
