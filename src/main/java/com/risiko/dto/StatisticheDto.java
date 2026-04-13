package com.risiko.dto;

/** Statistiche dell'utente */
public record StatisticheDto(
    String userId,
    int totaleRisposte,
    int risposteCorrette,
    double percentualeCorrette,
    int difficolta
) {}
