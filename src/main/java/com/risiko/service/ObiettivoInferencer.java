package com.risiko.service;

import com.risiko.service.RisikoBoardData.ObiettivoTarget;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ObiettivoInferencer — Inferenza bayesiana degli obiettivi avversari.
 *
 * Strategia:
 * - Prior uniforme su tutti gli obiettivi (ogni obiettivo è equiprobabile all'inizio)
 * - Ad ogni turno osserva: conquiste, concentrazione continentale, territori difesi
 * - Aggiorna le probabilità con un approccio bayesiano moltiplicativo
 * - Normalizza dopo ogni aggiornamento
 *
 * Un avversario che conquista molti territori in Asia probabilmente
 * ha un obiettivo che include l'Asia → P(obiettivi con Asia) aumenta.
 *
 * Usato da AIPlayerService per:
 * 1. Stimare il punteggio reale degli avversari (non più somma cieca)
 * 2. Decidere chi è più vicino alla vittoria → priorità difensiva
 * 3. Scegliere il partner ideale per la cartina (chi è meno minaccioso)
 */
public class ObiettivoInferencer {

    // colore → (obiettivoId → probabilità) — invariante: somma = 1.0 per ogni colore
    private final Map<String, Map<Integer, Double>> probabilita = new HashMap<>();

    // Snapshot proprietà turno precedente: territorio → colore
    // Usato per rilevare le conquiste di ciascun turno
    private final Map<String, String> proprietaPrecedente = new HashMap<>();

    // ── Pesi del modello bayesiano ────────────────────────────────────────────
    /** Boost se il territorio conquistato è in quell'obiettivo */
    private static final double PESO_CONQUISTA_MATCH    = 1.8;
    /** Penalità se il territorio conquistato NON è in quell'obiettivo */
    private static final double PESO_CONQUISTA_NO_MATCH = 0.75;
    /** Boost se il giocatore ha ≥60% di un continente che è nell'obiettivo */
    private static final double PESO_CONCENTRAZIONE     = 1.25;
    /** Boost moderato per rinforzi concentrati in un continente obiettivo */
    private static final double PESO_RINFORZO           = 1.15;

    // ═══════════════════════════════════════════════════════════════════════════
    //  INIZIALIZZAZIONE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Chiama questo metodo una volta prima della prima partita.
     * Imposta la distribuzione uniforme su tutti gli obiettivi per ciascun colore.
     *
     * @param colori  insieme dei colori avversari da tracciare (escludi il colore AI)
     */
    public void inizializza(Set<String> colori, Map<String, TerritoryState> mappaIniziale) {
        int n = RisikoBoardData.OBIETTIVI.size();
        if (n == 0) return;
        double base = 1.0 / n;

        for (String c : colori) {
            Map<Integer, Double> m = new LinkedHashMap<>();
            RisikoBoardData.OBIETTIVI.keySet().forEach(id -> m.put(id, base));
            probabilita.put(c, m);
        }

        // Snapshot iniziale
        mappaIniziale.forEach((t, st) -> proprietaPrecedente.put(t, st.getColore()));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  AGGIORNAMENTO AD OGNI TURNO
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Chiamato alla fine di ogni turno (dopo che tutti hanno agito).
     * Osserva i cambiamenti nella mappa e aggiorna le probabilità di ciascun giocatore.
     *
     * @param mappaAttuale  stato attuale completo della mappa
     */
    public void aggiornaTurno(Map<String, TerritoryState> mappaAttuale) {
        // 1. Rileva conquiste per ogni colore confrontando con lo snapshot precedente
        Map<String, List<String>> conquistePerColore = new HashMap<>();
        for (Map.Entry<String, TerritoryState> e : mappaAttuale.entrySet()) {
            String territorio   = e.getKey();
            String coloreOra    = e.getValue().getColore();
            String colorePrima  = proprietaPrecedente.getOrDefault(territorio, coloreOra);

            if (!coloreOra.equals(colorePrima) && probabilita.containsKey(coloreOra)) {
                conquistePerColore
                        .computeIfAbsent(coloreOra, k -> new ArrayList<>())
                        .add(territorio);
            }
        }

        // 2. Update bayesiano per conquiste
        for (Map.Entry<String, List<String>> ce : conquistePerColore.entrySet()) {
            String colore = ce.getKey();
            for (String t : ce.getValue()) {
                aggiornaPerConquista(colore, t);
            }
            normalizza(colore);
        }

        // 3. Update per concentrazione continentale (tutti i giocatori tracciati)
        for (String colore : probabilita.keySet()) {
            aggiornaPerConcentrazione(colore, mappaAttuale);
        }

        // 4. Aggiorna snapshot
        mappaAttuale.forEach((t, st) -> proprietaPrecedente.put(t, st.getColore()));
    }

    /**
     * Aggiornamento opzionale più granulare: chiamalo quando osservi dove un
     * avversario ha piazzato i rinforzi (se il dato è disponibile).
     *
     * @param colore      colore dell'avversario
     * @param territori   territori in cui ha piazzato rinforzi questo turno
     */
    public void aggiornaPerRinforzi(String colore, List<String> territori) {
        if (!probabilita.containsKey(colore)) return;

        for (String t : territori) {
            Map<Integer, Double> probs = probabilita.get(colore);
            for (Map.Entry<Integer, Double> e : probs.entrySet()) {
                ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(e.getKey());
                if (obj == null) continue;
                if (isInObiettivo(t, obj)) {
                    e.setValue(e.getValue() * PESO_RINFORZO);
                }
            }
        }
        normalizza(colore);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  QUERY — usate da AIPlayerService
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Stima il punteggio attuale di un avversario.
     *
     * Formula: Σ_i [ P(obiettivo_i) × punteggio(colore, obiettivo_i) ]
     *
     * Dove punteggio(c, obj) = somma dei confinanti di tutti i territori
     * di colore c che sono in obiettivo obj.
     *
     * Questo è molto più preciso della vecchia somma-cieca di tutti i confinanti.
     */
    public int stimaPunteggio(String colore, Map<String, TerritoryState> mappa) {
        Map<Integer, Double> probs = probabilita.getOrDefault(colore, Map.of());
        if (probs.isEmpty()) return stimaPunteggioFallback(colore, mappa);

        List<String> suoi = getTerritoriGiocatore(colore, mappa);
        double stimaPesata = 0.0;

        for (Map.Entry<Integer, Double> e : probs.entrySet()) {
            if (e.getValue() < 0.005) continue; // salta obiettivi quasi impossibili

            ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(e.getKey());
            if (obj == null) continue;

            int punti = suoi.stream()
                    .filter(t -> isInObiettivo(t, obj))
                    .mapToInt(t -> RisikoBoardData.ADIACENZE
                            .getOrDefault(t, List.of()).size())
                    .sum();

            stimaPesata += e.getValue() * punti;
        }
        return (int) Math.round(stimaPesata);
    }

    /**
     * Restituisce l'obiettivo più probabile per un avversario.
     * Utile per dedurre dove attaccherà / cosa difenderà.
     */
    public int getObiettivoPiuProbabile(String colore) {
        return probabilita.getOrDefault(colore, Map.of()).entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(-1);
    }

    /**
     * Ritorna i continenti che l'avversario probabilmente sta cercando di completare.
     * (Da quelli con probabilità > soglia nell'obiettivo più probabile)
     */
    public List<String> getContinentiProbabili(String colore) {
        int objId = getObiettivoPiuProbabile(colore);
        if (objId < 0) return List.of();

        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(objId);
        if (obj == null || obj.continentiTarget() == null) return List.of();

        return obj.continentiTarget();
    }

    /**
     * Indica se un avversario è considerato "pericoloso" (alta probabilità di
     * essere vicino alla vittoria). Utile per decidere priorità difensive.
     *
     * @param sogliaStima  punteggio stimato sopra il quale è considerato pericoloso
     */
    public boolean isPericoloso(String colore, Map<String, TerritoryState> mappa,
                                int sogliaStima) {
        return stimaPunteggio(colore, mappa) >= sogliaStima;
    }

    /**
     * Distribuzione di probabilità completa per debug/log.
     */
    public Map<Integer, Double> getProbabilita(String colore) {
        return Collections.unmodifiableMap(
                probabilita.getOrDefault(colore, Map.of()));
    }

    /**
     * Restituisce una stringa leggibile delle top-3 probabilità per debug.
     */
    public String toStringDebug(String colore) {
        Map<Integer, Double> probs = probabilita.getOrDefault(colore, Map.of());
        return probs.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .limit(3)
                .map(e -> String.format("Obj#%d=%.1f%%", e.getKey(), e.getValue() * 100))
                .collect(Collectors.joining(", "));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  LOGICA INTERNA
    // ═══════════════════════════════════════════════════════════════════════════

    private void aggiornaPerConquista(String colore, String territorio) {
        Map<Integer, Double> probs = probabilita.get(colore);
        if (probs == null) return;

        for (Map.Entry<Integer, Double> e : probs.entrySet()) {
            ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(e.getKey());
            if (obj == null) continue;

            double fattore = isInObiettivo(territorio, obj)
                    ? PESO_CONQUISTA_MATCH
                    : PESO_CONQUISTA_NO_MATCH;
            e.setValue(e.getValue() * fattore);
        }
    }

    private void aggiornaPerConcentrazione(String colore,
                                           Map<String, TerritoryState> mappa) {
        Map<Integer, Double> probs = probabilita.get(colore);
        if (probs == null) return;

        List<String> suoi = getTerritoriGiocatore(colore, mappa);
        boolean aggiornato = false;

        for (Map.Entry<String, List<String>> ce : RisikoBoardData.CONTINENTI.entrySet()) {
            String cont      = ce.getKey();
            List<String> tc  = ce.getValue();
            long n           = suoi.stream().filter(tc::contains).count();
            double perc      = (double) n / tc.size();

            // Boost significativo se ≥60% di un continente
            if (perc >= 0.60) {
                for (Map.Entry<Integer, Double> e : probs.entrySet()) {
                    ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(e.getKey());
                    if (obj == null || obj.continentiTarget() == null) continue;
                    if (obj.continentiTarget().contains(cont)) {
                        e.setValue(e.getValue() * PESO_CONCENTRAZIONE);
                        aggiornato = true;
                    }
                }
            }
        }
        if (aggiornato) normalizza(colore);
    }

    private void normalizza(String colore) {
        Map<Integer, Double> probs = probabilita.get(colore);
        if (probs == null || probs.isEmpty()) return;

        double somma = probs.values().stream().mapToDouble(Double::doubleValue).sum();
        if (somma > 0) probs.replaceAll((k, v) -> v / somma);
    }

    /**
     * Fallback se il colore non è tracciato: usa la vecchia stima pessimistica
     * (metà dei territori × confinanti medi) come approssimazione conservativa.
     */
    private int stimaPunteggioFallback(String colore, Map<String, TerritoryState> mappa) {
        return getTerritoriGiocatore(colore, mappa).stream()
                .mapToInt(t -> RisikoBoardData.ADIACENZE.getOrDefault(t, List.of()).size())
                .sum() / 2;
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private boolean isInObiettivo(String territorio, ObiettivoTarget obj) {
        if (obj == null) return false;
        if (obj.territoriSpecifici() != null
                && obj.territoriSpecifici().contains(territorio)) return true;
        if (obj.continentiTarget() != null) {
            for (String cont : obj.continentiTarget()) {
                List<String> tc = RisikoBoardData.CONTINENTI.get(cont);
                if (tc != null && tc.contains(territorio)) return true;
            }
        }
        return false;
    }

    private List<String> getTerritoriGiocatore(String colore,
                                               Map<String, TerritoryState> mappa) {
        return mappa.entrySet().stream()
                .filter(e -> colore.equals(e.getValue().getColore()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
}
