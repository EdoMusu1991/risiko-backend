package com.risiko.service;

import com.risiko.dto.ObiettivoDto;
import com.risiko.model.Obiettivo;
import com.risiko.repository.ObiettivoRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class TeoriaService {

    private final ObiettivoRepository repo;

    public TeoriaService(ObiettivoRepository repo) {
        this.repo = repo;
    }

    /** Restituisce tutti i 16 obiettivi */
    public List<ObiettivoDto> getTutti() {
        return repo.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    /** Restituisce un singolo obiettivo per ID */
    public Optional<ObiettivoDto> getById(int id) {
        return repo.findById(id).map(this::toDto);
    }

    public ObiettivoDto toDto(Obiettivo o) {
        return new ObiettivoDto(
                o.getId(),
                o.getNome(),
                o.getTerritori(),
                o.getImmagine(),
                o.getTerritori().size()
        );
    }
}
