package com.risiko.model;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "sfida_giornaliera")
public class SfidaGiornaliera {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private LocalDate giorno;

    // Seed per generare la stessa plancia per tutti
    @Column(nullable = false)
    private long seed;

    // Difficoltà del giorno (rotazione: 1,2,3,1,2,3...)
    @Column(nullable = false)
    private int difficolta;

    public SfidaGiornaliera() {}

    public SfidaGiornaliera(LocalDate giorno, long seed, int difficolta) {
        this.giorno = giorno;
        this.seed = seed;
        this.difficolta = difficolta;
    }

    public Long getId() { return id; }
    public LocalDate getGiorno() { return giorno; }
    public long getSeed() { return seed; }
    public int getDifficolta() { return difficolta; }
    public void setGiorno(LocalDate giorno) { this.giorno = giorno; }
    public void setSeed(long seed) { this.seed = seed; }
    public void setDifficolta(int difficolta) { this.difficolta = difficolta; }
}
