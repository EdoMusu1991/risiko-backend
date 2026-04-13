package com.risiko.repository;

import com.risiko.model.Partita;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PartitaRepository extends JpaRepository<Partita, Long> {

    List<Partita> findByUtenteIdOrderByGiocataIlDesc(Long utenteId);

    /** Classifica: utenti ordinati per punteggio totale decrescente */
    @Query("""
        SELECT p.utente.id, p.utente.username, p.utente.avatar,
               SUM(p.punteggio)     AS totPunti,
               COUNT(p)             AS totPartite,
               SUM(CASE WHEN p.corretta = true THEN 1 ELSE 0 END) AS totCorrette
        FROM Partita p
        GROUP BY p.utente.id, p.utente.username, p.utente.avatar
        ORDER BY totPunti DESC
    """)
    List<Object[]> getClassifica();

    /** Statistiche aggregate per un singolo utente */
    @Query("""
        SELECT COUNT(p),
               SUM(CASE WHEN p.corretta = true THEN 1 ELSE 0 END),
               SUM(p.punteggio)
        FROM Partita p WHERE p.utente.id = :utenteId
    """)
    Object[] getStatsByUtenteId(Long utenteId);
}
