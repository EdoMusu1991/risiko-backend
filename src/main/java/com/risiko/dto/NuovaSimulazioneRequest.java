package com.risiko.dto;

public record NuovaSimulazioneRequest(String difficolta) {
    public NuovaSimulazioneRequest {
        if (difficolta == null || difficolta.isBlank()) difficolta = "MEDIO";
    }
    public int turni() {
        return switch (difficolta.toUpperCase()) {
            case "FACILE"    -> 10;
            case "DIFFICILE" -> 5;
            default          -> 7;
        };
    }
}