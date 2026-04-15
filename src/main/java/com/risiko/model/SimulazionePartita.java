package com.risiko.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "simulazione_partita")
public class SimulazionePartita {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "creata_il")
    private LocalDateTime creataIl;

    @OneToMany(mappedBy = "simulazione", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<GiocatoreSimulato> giocatori;

    public Long getId()                                 { return id; }
    public void setId(Long id)                          { this.id = id; }
    public LocalDateTime getCreataIl()                  { return creataIl; }
    public void setCreataIl(LocalDateTime creataIl)     { this.creataIl = creataIl; }
    public List<GiocatoreSimulato> getGiocatori()       { return giocatori; }
    public void setGiocatori(List<GiocatoreSimulato> g) { this.giocatori = g; }
}
