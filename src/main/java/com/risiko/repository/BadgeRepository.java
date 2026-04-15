package com.risiko.repository;

import com.risiko.model.Badge;
import com.risiko.model.Utente;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BadgeRepository extends JpaRepository<Badge, Long> {
    List<Badge> findByUtente(Utente utente);
    boolean existsByUtenteAndCodice(Utente utente, String codice);
}
