package com.risiko.dto;

import java.util.List;

/** Conferma simultanea di tutti e 4 i giocatori */
public record ConfermaPlanciaRequest(
    String userId,
    List<RispostaGiocatoreDto> risposte  // una per ogni colore
) {}
