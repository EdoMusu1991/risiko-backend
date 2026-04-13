package com.risiko.service;

import com.risiko.dto.ImpostazioniDto;
import com.risiko.model.Impostazioni;
import com.risiko.repository.ImpostazioniRepository;
import org.springframework.stereotype.Service;

@Service
public class ImpostazioniService {

    private final ImpostazioniRepository repo;

    public ImpostazioniService(ImpostazioniRepository repo) {
        this.repo = repo;
    }

    /** Recupera le impostazioni (crea defaults se non esistono) */
    public ImpostazioniDto getImpostazioni(String userId) {
        Impostazioni imp = repo.findByUserId(userId)
                .orElseGet(() -> repo.save(new Impostazioni(userId)));
        return toDto(imp);
    }

    /** Salva/aggiorna le impostazioni dell'utente */
    public ImpostazioniDto salvaImpostazioni(String userId, ImpostazioniDto dto) {
        Impostazioni imp = repo.findByUserId(userId)
                .orElseGet(() -> new Impostazioni(userId));

        // Valida difficoltà (solo 1, 2 o 3)
        int diff = dto.difficolta();
        if (diff < 1 || diff > 3) diff = 1;

        imp.setDifficolta(diff);
        imp.setTema(dto.tema() != null ? dto.tema() : "dark");
        imp.setSuggerimentiAttivi(dto.suggerimentiAttivi());
        imp.setHighlightMappa(dto.highlightMappa());

        return toDto(repo.save(imp));
    }

    private ImpostazioniDto toDto(Impostazioni i) {
        return new ImpostazioniDto(
                i.getUserId(),
                i.getDifficolta(),
                i.getTema(),
                i.isSuggerimentiAttivi(),
                i.isHighlightMappa()
        );
    }
}
