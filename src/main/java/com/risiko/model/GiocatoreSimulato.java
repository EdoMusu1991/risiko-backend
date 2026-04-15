package com.risiko.model;

import jakarta.persistence.*;

@Entity
@Table(name = "giocatore_simulato")
public class GiocatoreSimulato {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "simulazione_id")
    private SimulazionePartita simulazione;

    private String colore;

    @Column(name = "obiettivo_id")
    private int obiettivoId;

    public Long getId()                              { return id; }
    public void setId(Long id)                       { this.id = id; }
    public SimulazionePartita getSimulazione()       { return simulazione; }
    public void setSimulazione(SimulazionePartita s) { this.simulazione = s; }
    public String getColore()                        { return colore; }
    public void setColore(String colore)             { this.colore = colore; }
    public int getObiettivoId()                      { return obiettivoId; }
    public void setObiettivoId(int obiettivoId)      { this.obiettivoId = obiettivoId; }
}
