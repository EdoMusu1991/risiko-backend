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
    private int puntiInObiettivo = 0;
    private int puntiFuoriObiettivo = 0;
    // + getter e setter

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
    public int getPuntiInObiettivo()                   { return puntiInObiettivo; }
    public void setPuntiInObiettivo(int p)             { this.puntiInObiettivo = p; }
    public int getPuntiFuoriObiettivo()                { return puntiFuoriObiettivo; }
    public void setPuntiFuoriObiettivo(int p){ this.puntiFuoriObiettivo = p; }
}
