package com.risiko.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Statistiche di un utente nel quiz.
 */
@Entity
@Table(name = "statistiche_utente")
@Data
@NoArgsConstructor
public class StatisticheUtente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Identificativo utente (username o UUID generato dal frontend) */
    @Column(nullable = false, unique = true)
    private String userId;

    private int totaleRisposte;
    private int risposteCorrette;

    /** Difficoltà preferita: 1=facile, 2=media, 3=difficile */
    private int difficolta = 1;

    public StatisticheUtente(String userId) {
        this.userId = userId;
    }

    public double getPercentualeCorrette() {
        if (totaleRisposte == 0) return 0.0;
        return (double) risposteCorrette / totaleRisposte * 100.0;
    }
}
