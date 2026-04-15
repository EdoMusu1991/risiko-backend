package com.risiko.model;

import jakarta.persistence.*;

@Entity
@Table(name = "hint_usato",
       uniqueConstraints = @UniqueConstraint(columnNames = {"simulazione_id", "colore"}))
public class HintUsato {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "simulazione_id")
    private SimulazionePartita simulazione;

    private String colore;

    @Column(name = "testo_hint", length = 500)
    private String testoHint;

    public Long getId()                              { return id; }
    public SimulazionePartita getSimulazione()       { return simulazione; }
    public void setSimulazione(SimulazionePartita s) { this.simulazione = s; }
    public String getColore()                        { return colore; }
    public void setColore(String c)                  { this.colore = c; }
    public String getTestoHint()                     { return testoHint; }
    public void setTestoHint(String t)               { this.testoHint = t; }
}
