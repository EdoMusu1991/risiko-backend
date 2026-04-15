package com.risiko.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "simulazione_risultato")
public class SimulazioneRisultato {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private String userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "simulazione_id")
    private SimulazionePartita simulazione;

    private String difficolta;
    private int corretti;
    private int punteggio;

    @Column(name = "hint_usati")
    private int hintUsati;

    @Column(name = "giocata_il")
    private LocalDateTime giocataIl;

    public Long getId()                              { return id; }
    public String getUserId()                        { return userId; }
    public void setUserId(String userId)             { this.userId = userId; }
    public SimulazionePartita getSimulazione()       { return simulazione; }
    public void setSimulazione(SimulazionePartita s) { this.simulazione = s; }
    public String getDifficolta()                    { return difficolta; }
    public void setDifficolta(String d)              { this.difficolta = d; }
    public int getCorretti()                         { return corretti; }
    public void setCorretti(int c)                   { this.corretti = c; }
    public int getPunteggio()                        { return punteggio; }
    public void setPunteggio(int p)                  { this.punteggio = p; }
    public int getHintUsati()                        { return hintUsati; }
    public void setHintUsati(int h)                  { this.hintUsati = h; }
    public LocalDateTime getGiocataIl()              { return giocataIl; }
    public void setGiocataIl(LocalDateTime t)        { this.giocataIl = t; }
}
