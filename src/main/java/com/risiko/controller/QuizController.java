package com.risiko.controller;

import com.risiko.dto.*;
import com.risiko.service.QuizService;
import com.risiko.service.StatisticheService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/quiz")
@CrossOrigin(origins = "*")
public class QuizController {

    private final QuizService quizService;
    private final StatisticheService statService;

    public QuizController(QuizService quizService, StatisticheService statService) {
        this.quizService = quizService;
        this.statService = statService;
    }

    /** GET /api/quiz/plancia?difficolta=1 — genera plancia 4 giocatori */
    @GetMapping("/plancia")
    public ResponseEntity<PlanciaDto> nuovaPlancia(
            @RequestParam(defaultValue = "1") int difficolta) {
        if (difficolta < 1 || difficolta > 3) difficolta = 1;
        return ResponseEntity.ok(quizService.generaPlancia(difficolta));
    }

    /** POST /api/quiz/conferma — conferma TUTTI e 4 i giocatori simultaneamente */
    @PostMapping("/conferma")
    public ResponseEntity<?> confermaPlancia(@RequestBody ConfermaPlanciaRequest req) {
        try {
            return ResponseEntity.ok(quizService.confermaPlancia(req));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /** GET /api/quiz/domanda — legacy solo blu */
    @GetMapping("/domanda")
    public ResponseEntity<DomandaDto> nuovaDomanda(
            @RequestParam(defaultValue = "anonimo") String userId,
            @RequestParam(defaultValue = "1") int difficolta) {
        if (difficolta < 1 || difficolta > 3) difficolta = 1;
        return ResponseEntity.ok(quizService.generaDomanda(userId, difficolta));
    }

    /** POST /api/quiz/risposta — legacy */
    @PostMapping("/risposta")
    public ResponseEntity<?> valutaRisposta(@RequestBody RispostaRequest req) {
        try {
            return ResponseEntity.ok(quizService.valutaRisposta(req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/stats/{userId}")
    public ResponseEntity<StatisticheDto> getStats(@PathVariable String userId) {
        return ResponseEntity.ok(statService.getStatistiche(userId));
    }

    @DeleteMapping("/stats/{userId}")
    public ResponseEntity<StatisticheDto> resetStats(@PathVariable String userId) {
        return ResponseEntity.ok(statService.resetStatistiche(userId));
    }
}
