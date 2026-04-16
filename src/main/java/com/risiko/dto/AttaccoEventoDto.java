package com.risiko.dto;

public record AttaccoEventoDto(
        String  da,
        String  verso,
        String  coloreAttaccante,
        String  coloreDifensore,
        boolean conquistato
) {}
