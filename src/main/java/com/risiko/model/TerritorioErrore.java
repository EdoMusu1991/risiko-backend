package com.risiko.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "territorio_errori")
public class TerritorioErrore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "utente_id", nullable = false)
    private Utente utente;

    @Column(nullable = false)
    private String territorioId; // es. "alaska", "brasile"

    @Column(nullable = false)
    private int errori = 0;

    @Column(nullable = false)
    private int totaleApparizioni = 0;

    @Column
    private LocalDateTime ultimoAggiornamento;

    public TerritorioErrore() {}

    public TerritorioErrore(Utente utente, String territorioId) {
        this.utente = utente;
        this.territorioId = territorioId;
        this.errori = 0;
        this.totaleApparizioni = 0;
        this.ultimoAggiornamento = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Utente getUtente() { return utente; }
    public String getTerritorioId() { return territorioId; }
    public int getErrori() { return errori; }
    public int getTotaleApparizioni() { return totaleApparizioni; }
    public LocalDateTime getUltimoAggiornamento() { return ultimoAggiornamento; }

    public void setUtente(Utente utente) { this.utente = utente; }
    public void setTerritorioId(String territorioId) { this.territorioId = territorioId; }
    public void setErrori(int errori) { this.errori = errori; }
    public void setTotaleApparizioni(int totaleApparizioni) { this.totaleApparizioni = totaleApparizioni; }
    public void setUltimoAggiornamento(LocalDateTime ultimoAggiornamento) { this.ultimoAggiornamento = ultimoAggiornamento; }

    public void incrementaErrori() {
        this.errori++;
        this.totaleApparizioni++;
        this.ultimoAggiornamento = LocalDateTime.now();
    }

    public void incrementaApparizione() {
        this.totaleApparizioni++;
        this.ultimoAggiornamento = LocalDateTime.now();
    }

    public double getPercentualeErrore() {
        if (totaleApparizioni == 0) return 0;
        return (double) errori / totaleApparizioni * 100;
    }
}
