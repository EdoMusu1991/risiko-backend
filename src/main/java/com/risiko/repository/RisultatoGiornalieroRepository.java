package com.risiko.repository;

import com.risiko.model.RisultatoGiornaliero;
import com.risiko.model.Utente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RisultatoGiornalieroRepository extends JpaRepository<RisultatoGiornaliero, Long> {

    Optional<RisultatoGiornaliero> findByUtenteAndGiorno(Utente utente, LocalDate giorno);

    List<RisultatoGiornaliero> findByGiornoOrderByPunteggioDescTempoSecondiAsc(LocalDate giorno);

    boolean existsByUtenteAndGiorno(Utente utente, LocalDate giorno);

    // Classifica settimanale: punteggio totale degli ultimi 7 giorni
    @Query("SELECT r.utente, SUM(r.punteggio) as tot FROM RisultatoGiornaliero r " +
           "WHERE r.giorno >= :startDate GROUP BY r.utente ORDER BY tot DESC")
    List<Object[]> classificaSettimanale(LocalDate startDate);
}
