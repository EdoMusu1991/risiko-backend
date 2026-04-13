package com.risiko.dto;

public record RispostaDto(
        boolean corretta,
        Integer obiettivoCorretto,
        String nomeObiettivoCorretto,
        String spiegazione,
        int punteggioAggiornato,
        boolean nessunObiettivo    // true se la risposta corretta era "nessun obiettivo"
) {}
