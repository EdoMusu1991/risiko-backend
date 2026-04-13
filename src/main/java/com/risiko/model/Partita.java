package com.risiko.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * Registra ogni partita giocata da un utente.
 * Punteggio: difficolta * 10 se corretta, 0 se sbagliata.
 */
@Entity
@Table(name = "partite")
@Data
@NoArgsConstructor
public class Partita {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "utente_id", nullable = false)
    private Utente utente;

    /** 1=facile, 2=medio, 3=difficile */
    @Column(nullable = false)
    private int difficolta;

    @Column(nullable = false)
    private boolean corretta;

    @Column(nullable = false)
    private int punteggio;

    /** ID dell'obiettivo corretto (per statistiche dettagliate) */
    private Integer obiettivoId;

    @Column(name = "giocata_il")
    private LocalDateTime giocataIl = LocalDateTime.now();

    public Partita(Utente utente, int difficolta, boolean corretta, int punteggio, Integer obiettivoId) {
        this.utente = utente;
        this.difficolta = difficolta;
        this.corretta = corretta;
        this.punteggio = punteggio;
        this.obiettivoId = obiettivoId;
    }
}
