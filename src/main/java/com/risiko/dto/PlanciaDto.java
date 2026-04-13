package com.risiko.dto;

import java.util.List;

/** Plancia completa con 4 giocatori */
public record PlanciaDto(
    GiocatoreQuizDto blu,
    GiocatoreQuizDto rosso,
    GiocatoreQuizDto verde,
    GiocatoreQuizDto giallo
) {}
