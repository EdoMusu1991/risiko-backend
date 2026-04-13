package com.risiko.dto;

/** Impostazioni utente */
public record ImpostazioniDto(
    String userId,
    int difficolta,
    String tema,
    boolean suggerimentiAttivi,
    boolean highlightMappa
) {}
