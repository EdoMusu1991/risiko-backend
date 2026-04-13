package com.risiko.dto;

/** Richiesta per salvare una partita giocata */
public record PartitaRequest(
    int difficolta,
    boolean corretta,
    int punteggio,
    Integer obiettivoId
) {}
