package com.risiko.dto;

import java.util.List;

/** Domanda generata per il quiz */
public record DomandaDto(
    String sessionId,           // ID univoco della domanda (per validare la risposta)
    List<String> territoriMostrati,   // Territori mostrati sulla mappa (obiettivo - quelli nascosti)
    List<ObiettivoDto> opzioni,       // 4 obiettivi tra cui scegliere
    int numeroTerritori,              // Totale territori dell'obiettivo segreto
    int territoriNascosti             // Quanti territori sono stati nascosti (in base alla difficoltà)
) {}
