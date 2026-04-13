package com.risiko.dto;

public record RispostaRequest(
        String sessionId,
        Integer obiettivoScelto,   // null se nessunObiettivo = true
        boolean nessunObiettivo,   // true = il giocatore pensa che nessun obiettivo corrisponda
        String userId
) {}
