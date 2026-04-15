package com.risiko.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "risultato_giornaliero",
       uniqueConstraints = @UniqueConstraint(columnNames = {"utente_id", "giorno"}))
public class RisultatoGiornaliero {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "utente_id", nullable = false)
    private Utente utente;

    @Column(nullable = false)
    private LocalDate giorno;

    @Column(nullable = false)
    private int punteggio;

    @Column(nullable = false)
    private int corrette; // quanti colori indovinati su 4

    @Column(nullable = false)
    private boolean tuttiCorretti;

    @Column(nullable = false)
    private int tempoSecondi; // tempo impiegato in secondi

    @Column
    private LocalDateTime completatoAlle;

    public RisultatoGiornaliero() {}

    public RisultatoGiornaliero(Utente utente, LocalDate giorno, int punteggio,
                                 int corrette, boolean tuttiCorretti, int tempoSecondi) {
        this.utente = utente;
        this.giorno = giorno;
        this.punteggio = punteggio;
        this.corrette = corrette;
        this.tuttiCorretti = tuttiCorretti;
        this.tempoSecondi = tempoSecondi;
        this.completatoAlle = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Utente getUtente() { return utente; }
    public LocalDate getGiorno() { return giorno; }
    public int getPunteggio() { return punteggio; }
    public int getCorrette() { return corrette; }
    public boolean isTuttiCorretti() { return tuttiCorretti; }
    public int getTempoSecondi() { return tempoSecondi; }
    public LocalDateTime getCompletatoAlle() { return completatoAlle; }

    public void setUtente(Utente u) { this.utente = u; }
    public void setGiorno(LocalDate g) { this.giorno = g; }
    public void setPunteggio(int p) { this.punteggio = p; }
    public void setCorrette(int c) { this.corrette = c; }
    public void setTuttiCorretti(boolean t) { this.tuttiCorretti = t; }
    public void setTempoSecondi(int t) { this.tempoSecondi = t; }
    public void setCompletatoAlle(LocalDateTime d) { this.completatoAlle = d; }
}
