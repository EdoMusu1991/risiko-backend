package com.risiko.dto;

/** Profilo utente con statistiche aggregate */
public record ProfiloDto(
    Long id,
    String username,
    String email,
    String avatar,
    String creatoIl,
    long totPartite,
    long corrette,
    long punti,
    long percentuale
) {}
