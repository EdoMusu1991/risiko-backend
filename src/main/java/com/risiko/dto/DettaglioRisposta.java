package com.risiko.dto;

public record DettaglioRisposta(
        String  colore,
        int     risposta,
        int     obiettivoReale,
        String  nomeObiettivoReale,
        boolean corretta,
        int     puntiInObiettivo,    // ← NUOVO
        int     puntiFuoriObiettivo  // ← NUOVO
) {}