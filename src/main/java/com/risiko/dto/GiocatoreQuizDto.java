package com.risiko.dto;

import java.util.List;

/** Dati quiz per un singolo giocatore nella plancia multi-player */
public record GiocatoreQuizDto(
    String colore,              // "blu", "rosso", "verde", "giallo"
    String sessionId,           // per validare la risposta
    List<String> territori,     // territori del giocatore sulla mappa
    List<ObiettivoDto> opzioni, // 4 opzioni tra cui scegliere
    int fuoriObj                // quanti territori NON sono nell'obiettivo
) {}
