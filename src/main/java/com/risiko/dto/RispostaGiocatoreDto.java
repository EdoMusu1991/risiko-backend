package com.risiko.dto;

import java.util.List;

/** Risposta di un singolo giocatore — può selezionare più obiettivi */
public record RispostaGiocatoreDto(
    String colore,
    String sessionId,
    List<Integer> obiettiviScelti,   // lista IDs — vuota se nessunObiettivo
    boolean nessunObiettivo
) {}
