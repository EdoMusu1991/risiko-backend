package com.risiko.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "badges")
public class Badge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "utente_id", nullable = false)
    private Utente utente;

    @Column(nullable = false)
    private String codice; // es. "PARTITE_10", "STREAK_5", ecc.

    @Column(nullable = false)
    private LocalDateTime dataOttenuto;

    public Badge() {}

    public Badge(Utente utente, String codice) {
        this.utente = utente;
        this.codice = codice;
        this.dataOttenuto = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Utente getUtente() { return utente; }
    public String getCodice() { return codice; }
    public LocalDateTime getDataOttenuto() { return dataOttenuto; }
    public void setUtente(Utente utente) { this.utente = utente; }
    public void setCodice(String codice) { this.codice = codice; }
    public void setDataOttenuto(LocalDateTime dataOttenuto) { this.dataOttenuto = dataOttenuto; }
}
