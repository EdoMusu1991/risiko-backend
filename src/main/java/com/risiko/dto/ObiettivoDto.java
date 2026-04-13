package com.risiko.dto;

import java.util.List;

/** DTO per un obiettivo (usato sia in Teoria che in Quiz) */
public record ObiettivoDto(
    int id,
    String nome,
    List<String> territori,
    String immagine,
    int numerTerritori
) {}
