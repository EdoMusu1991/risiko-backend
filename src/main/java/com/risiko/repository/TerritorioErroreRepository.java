package com.risiko.repository;

import com.risiko.model.TerritorioErrore;
import com.risiko.model.Utente;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TerritorioErroreRepository extends JpaRepository<TerritorioErrore, Long> {
    List<TerritorioErrore> findByUtenteOrderByErroriDesc(Utente utente);
    Optional<TerritorioErrore> findByUtenteAndTerritorioId(Utente utente, String territorioId);
}
