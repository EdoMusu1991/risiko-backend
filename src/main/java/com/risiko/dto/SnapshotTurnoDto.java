package com.risiko.dto;

import java.util.List;
import java.util.Map;

public record SnapshotTurnoDto(
        int turno,
        Map<String, TerritoryStateDto> mappa,
        List<String> logAzioni
) {}