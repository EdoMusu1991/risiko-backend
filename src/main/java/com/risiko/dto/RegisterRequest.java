package com.risiko.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank(message = "Username obbligatorio")
    @Size(min = 3, max = 50, message = "Username tra 3 e 50 caratteri")
    String username,

    @NotBlank(message = "Password obbligatoria")
    @Size(min = 6, message = "Password minimo 6 caratteri")
    String password,

    String email,
    String avatar
) {}
