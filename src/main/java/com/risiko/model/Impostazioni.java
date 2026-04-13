package com.risiko.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Preferenze/impostazioni dell'utente.
 */
@Entity
@Table(name = "impostazioni")
@Data
@NoArgsConstructor
public class Impostazioni {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String userId;

    /** Difficoltà quiz: 1=facile, 2=media, 3=difficile */
    private int difficolta = 1;

    /** Tema UI: "dark" o "light" */
    private String tema = "dark";

    /** Mostra suggerimenti durante il quiz */
    private boolean suggerimentiAttivi = true;

    /** Mostra highlight mappa dopo risposta */
    private boolean highlightMappa = true;

    public Impostazioni(String userId) {
        this.userId = userId;
    }
}
