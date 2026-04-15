package com.risiko.dto;

import java.util.List;

public record NuovaSimulazioneDto(
        Long simulazioneId,
        int turniTotali,
        String difficolta,
        List<SnapshotTurnoDto> snapshots
) {}