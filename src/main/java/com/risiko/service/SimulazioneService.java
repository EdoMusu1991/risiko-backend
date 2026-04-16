package com.risiko.service;

import com.risiko.dto.*;
import com.risiko.model.*;
import com.risiko.repository.*;
import com.risiko.service.RisikoBoardData.ObiettivoTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SimulazioneService {

    private static final Logger log = LoggerFactory.getLogger(SimulazioneService.class);

    @Autowired private SimulazionePartitaRepository   simRepo;
    @Autowired private GiocatoreSimulatoRepository    giocatoreRepo;
    @Autowired private SimulazioneRisultatoRepository risultatoRepo;
    @Autowired private HintUsatoRepository            hintRepo;
    @Autowired private AIPlayerService                aiService;

    // ⚠️  Aggiungere @Autowired per StatisticheUtenteRepository
    // se si vuole aggiornare le statistiche globali dell'utente (opzionale)
    // @Autowired private StatisticheUtenteRepository statRepo;

    private static final List<Integer> OBIETTIVI_DISPONIBILI =
        List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 15, 16);

    private static final String[] COLORI = {"BLU", "ROSSO", "VERDE", "GIALLO"};
    private static final Random   RANDOM = new Random();
    public  static final int      COSTO_HINT = 10;
    private int turno = 1;


    // ── GENERA SIMULAZIONE ────────────────────────────────────────────────────
// ── MODIFICA A generaSimulazione() ─────────────────────────────────────────
// Sostituisci l'intero metodo generaSimulazione con questo:

    public NuovaSimulazioneDto generaSimulazione(NuovaSimulazioneRequest req) {
        String diff  = req.difficolta().toUpperCase();
        int    turni = 45;

        // 1. Assegna obiettivi
        Map<String, Integer> obiettiviPerColore = assegnaObiettivi();

        // 2. Inizializza mappa CON la fase di piazzamento
        Map.Entry<Map<String, TerritoryState>, List<String>> piazzamento =
                inizializzaMappaConPiazzamento(obiettiviPerColore);
        Map<String, TerritoryState> mappa        = piazzamento.getKey();
        List<String>                logPiazz     = piazzamento.getValue();

        // 3. Salva partita e giocatori
        SimulazionePartita sim = new SimulazionePartita();
        sim.setCreataIl(LocalDateTime.now());
        sim = simRepo.save(sim);

        for (String colore : COLORI) {
            GiocatoreSimulato g = new GiocatoreSimulato();
            g.setSimulazione(sim);
            g.setColore(colore);
            g.setObiettivoId(obiettiviPerColore.get(colore));
            giocatoreRepo.save(g);
        }

        // 4. Snapshot turno 0 = stato dopo il piazzamento
        List<SnapshotTurnoDto> snapshots = new ArrayList<>();
        snapshots.add(creaSnapshot(0, mappa, logPiazz, List.of(), Map.of(), List.of(), List.of(), "PIAZZAMENTO", null));


        Set<String> attivi      = new LinkedHashSet<>(Arrays.asList(COLORI));
        boolean     partitaFinita = false;

        for (int t = 1; t <= turni && !partitaFinita; t++) {

            List<String> logTurnoCompleto = new ArrayList<>();
            logTurnoCompleto.add("══════ TURNO " + t + " / " + turni + " ══════");

            // Ordine mosse: BLU, ROSSO, VERDE, GIALLO (ordine di mano)
            // Ordine sdadata: GIALLO, VERDE, ROSSO, BLU (ordine inverso, 4° inizia primo)
            String[] ordineSdadata = {"GIALLO", "VERDE", "ROSSO", "BLU"};

            for (String colore : List.copyOf(attivi)) {

                if (aiService.getTerritoriGiocatore(colore, mappa).isEmpty()) {
                    attivi.remove(colore);
                    logTurnoCompleto.add("💀 " + lbl(colore) + " è stato eliminato!");
                    continue;
                }

                List<AIPlayerService.FaseResult> fasi =
                        aiService.eseguiFasi(colore, obiettiviPerColore.get(colore), t, mappa);

                for (AIPlayerService.FaseResult fase : fasi) {

                    // ← AGGIUNGI QUESTE DICHIARAZIONI
                    List<AttaccoEventoDto> attacchiF = fase.attacchi().stream()
                            .map(e -> new AttaccoEventoDto(e.da(), e.verso(),
                                    e.coloreAttaccante(), e.coloreDifensore(), e.conquistato()))
                            .collect(Collectors.toList());

                    Map<String, StatoCarteDto> carteF = new LinkedHashMap<>();
                    fase.statoCarte().forEach((c, s) -> carteF.put(c,
                            new StatoCarteDto(s.fanti(), s.cannoni(), s.cavalli(), s.jolly(), s.totale())));

                    List<EventoCartinaDto> cartineF = fase.cartine().stream()
                            .map(c -> new EventoCartinaDto(c.coloreOffre(), c.coloreRiceve(), c.territorio()))
                            .collect(Collectors.toList());

                    List<EventoTrisDto> trisF = fase.tris().stream()
                            .map(tr -> new EventoTrisDto(tr.colore(), tr.bonus(), tr.tipo()))
                            .collect(Collectors.toList());

                    // ... poi la riga che già hai:
                    snapshots.add(creaSnapshot(t, mappa, fase.log(),
                            attacchiF, carteF, cartineF, trisF, fase.fase(), fase.colore()));
                }

                // Controlla eliminazioni
                for (String altro : List.copyOf(attivi)) {
                    if (!altro.equals(colore) &&
                            aiService.getTerritoriGiocatore(altro, mappa).isEmpty()) {
                        attivi.remove(altro);
                    }
                }
            }

            // ── FASE SDADATA (dal turno 35, ordine inverso di mano) ──────────────
            if (t >= 35 && !partitaFinita) {
                for (String colore : ordineSdadata) {
                    if (!attivi.contains(colore)) continue;

                    AIPlayerService.SdadataResult sd =
                            aiService.valutaSdadata(colore, obiettiviPerColore.get(colore), t, mappa);

                    List<String> logSd = new ArrayList<>();
                    if (sd != null && sd.tentato()) {
                        if (sd.riuscita()) {
                            logSd.add("🎲 " + lbl(colore) + " SDADATA! "
                                    + sd.dado1() + "+" + sd.dado2()
                                    + "=" + sd.totale() + " ≤ " + sd.soglia());
                            logSd.add("⏱ La partita è terminata.");
                            snapshots.add(creaSnapshot(t, mappa, logSd,
                                    List.of(), Map.of(), List.of(), List.of(), "SDADATA", colore));
                            partitaFinita = true;
                            break;
                        } else {
                            logSd.add("🎲 " + lbl(colore) + " tenta sdadata: "
                                    + sd.dado1() + "+" + sd.dado2()
                                    + "=" + sd.totale() + " > " + sd.soglia() + " (fallita)");
                            snapshots.add(creaSnapshot(t, mappa, logSd,
                                    List.of(), Map.of(), List.of(), List.of(), "SDADATA", colore));
                        }
                    }
                }
            }

            // ── FINE TURNO ───────────────────────────────────────────────────────
            if (!partitaFinita) {
                List<String> logRiepilogo = new ArrayList<>();
                aggiungiRiepilogoContinenti(logRiepilogo, mappa);
                if (!logRiepilogo.isEmpty()) {
                    snapshots.add(creaSnapshot(t, mappa, logRiepilogo,
                            List.of(), Map.of(), List.of(), List.of(), "RIEPILOGO", null));
                }

                if (attivi.size() == 1) {
                    String vincitore = attivi.iterator().next();
                    snapshots.add(creaSnapshot(t + 1, mappa,
                            List.of("🏆 " + lbl(vincitore) + " ha conquistato il mondo!"),
                            List.of(), Map.of(), List.of(), List.of(), "FINE", null));
                    partitaFinita = true;
                }

                // Turno 45 = fine obbligatoria
                if (t == 45 && !partitaFinita) {
                    snapshots.add(creaSnapshot(t, mappa,
                            List.of("⏱ Turno limite raggiunto. Simulazione terminata."),
                            List.of(), Map.of(), List.of(), List.of(), "FINE", null));
                    partitaFinita = true;
                }
            }
        }



        return new NuovaSimulazioneDto(sim.getId(), turni, diff, snapshots);
    }

// ── NUOVI METODI PRIVATI da aggiungere in SimulazioneService ───────────────

    /**
     * Crea la mappa iniziale con 1 armata per territorio
     * e poi esegue il piazzamento dei 30 carri per ogni giocatore.
     */
    private Map.Entry<Map<String, TerritoryState>, List<String>>
    inizializzaMappaConPiazzamento(Map<String, Integer> obiettiviPerColore) {

        List<String> tutti = new ArrayList<>(RisikoBoardData.TUTTI_TERRITORI);
        Collections.shuffle(tutti, RANDOM);

        // Quote casualizzate: 2 giocatori con 10, 2 con 11
        int[] quote = {10, 10, 11, 11};

        // Assegna territori con 1 armata
        Map<String, TerritoryState> mappa = new LinkedHashMap<>();
        Map<String, List<String>>   territoriPerColore = new LinkedHashMap<>();
        int idx = 0;
        for (int p = 0; p < COLORI.length; p++) {
            String colore = COLORI[p];
            List<String> miei = new ArrayList<>();
            for (int t = 0; t < quote[p]; t++) {
                String terr = tutti.get(idx++);
                mappa.put(terr, new TerritoryState(colore, 1));
                miei.add(terr);
            }
            territoriPerColore.put(colore, miei);
        }

        List<String> logPiazz = new ArrayList<>();
        logPiazz.add("⚑ PIAZZAMENTO INIZIALE — ogni giocatore distribuisce 30 carri");
        logPiazz.add("");

        // Piazza i 30 carri per ogni giocatore
        for (int p = 0; p < COLORI.length; p++) {
            String colore     = COLORI[p];
            int    obiettivoId = obiettiviPerColore.get(colore);
            List<String> miei  = territoriPerColore.get(colore);
            int nTerr          = miei.size();

            // Ordina: i più importanti in cima (ricevono 3 carri)
            List<String> ordinati = ordinaPiazzamento(colore, obiettivoId, miei, mappa);

            if (nTerr == 10) {
                // 10 territori × 3 = 30 ✓ — tutti a 3
                ordinati.forEach(t -> mappa.get(t).setArmate(3));
                logPiazz.add("🪖 " + lbl(colore) + " (10 territori) → tutti a 3 carri");
            } else {
                // 11 territori: 9×3 + 1×2 + 1×1 = 30 ✓
                for (int i = 0; i < ordinati.size(); i++) {
                    mappa.get(ordinati.get(i)).setArmate(i < 9 ? 3 : i == 9 ? 2 : 1);
                }
                logPiazz.add("🪖 " + lbl(colore) + " (11 territori) → 9 a 3, "
                        + nomLegg(ordinati.get(9))  + " a 2, "
                        + nomLegg(ordinati.get(10)) + " a 1");
            }
        }

        logPiazz.add("");
        for (String colore : COLORI) {
            long nT  = mappa.values().stream().filter(ts -> colore.equals(ts.getColore())).count();
            int  tot = mappa.values().stream().filter(ts -> colore.equals(ts.getColore()))
                    .mapToInt(TerritoryState::getArmate).sum();
            logPiazz.add("  " + lbl(colore) + ": " + nT + " territori, " + tot + " carri totali");
        }
        logPiazz.add("");
        logPiazz.add("🎯 Osserva le mosse e indovina l'obiettivo di ogni giocatore!");

        return Map.entry(mappa, logPiazz);
    }

    /**
     * Ordina i territori dal più importante (riceve 3) al meno importante (riceve 1).
     *
     * Score alto = territorio da difendere con 3 carri.
     * Score basso = territorio sacrificabile (2 o 1 carro).
     *
     * Criteri per ricevere MENO carri (score basso):
     *  1. Fuori obiettivo                     ← taglio prioritario
     *  2. Non blocca continente avversario
     *  3. Crea nicchia (tutti adiacenti miei) ← meno urgente difenderlo
     *  4. Non è ponte verso continente vuoto
     *  5. Pochi punti (poche adiacenze)
     */
    private List<String> ordinaPiazzamento(String colore, int obiettivoId,
                                           List<String> miei,
                                           Map<String, TerritoryState> mappa) {
        RisikoBoardData.ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);

        return miei.stream()
                .sorted(Comparator.comparingInt((String t) ->
                        scoreImportanzaIniziale(t, colore, miei, mappa, obj)).reversed())
                .collect(Collectors.toList());
    }

    private int scoreImportanzaIniziale(String t, String colore,
                                        List<String> miei,
                                        Map<String, TerritoryState> mappa,
                                        RisikoBoardData.ObiettivoTarget obj) {
        int score = 0;

        // 1. In obiettivo: importantissimo, non si taglia mai
        if (isInObiettivo(t, obj)) score += 1000;

        // 2. Territorio critico per bloccare un continente avversario
        for (Map.Entry<String, List<String>> ce : RisikoBoardData.CONTINENTI.entrySet()) {
            List<String> tc = ce.getValue();
            if (!tc.contains(t)) continue;
            boolean critico = mappa.entrySet().stream()
                    .filter(e -> tc.contains(e.getKey()) && !colore.equals(e.getValue().getColore()))
                    .collect(Collectors.groupingBy(e -> e.getValue().getColore(), Collectors.counting()))
                    .values().stream().anyMatch(n -> n >= tc.size() - 1);
            if (critico) score += 500;
        }

        // 3. Nicchia: tutti gli adiacenti sono miei → al sicuro, posso lasciarlo con meno
        boolean nicchia = RisikoBoardData.ADIACENZE.getOrDefault(t, List.of()).stream()
                .allMatch(adj -> miei.contains(adj));
        if (nicchia) score -= 300;

        // 4. Ponte verso continente dove non ho nessun territorio
        for (Map.Entry<String, List<String>> ce : RisikoBoardData.CONTINENTI.entrySet()) {
            List<String> tc = ce.getValue();
            if (tc.contains(t)) continue;
            boolean hoNessuno = tc.stream().noneMatch(miei::contains);
            boolean sonoPonte = RisikoBoardData.ADIACENZE.getOrDefault(t, List.of())
                    .stream().anyMatch(tc::contains);
            if (hoNessuno && sonoPonte) score += 150;
        }

        // 5. Valore punti (adiacenze = punti): più adiacenze → più importante
        int adiacenze = RisikoBoardData.ADIACENZE.getOrDefault(t, List.of()).size();
        score += adiacenze * 10;

        return score;
    }

    private boolean isInObiettivo(String territorio, RisikoBoardData.ObiettivoTarget obj) {
        if (obj == null) return false;
        if (obj.territoriSpecifici() != null && obj.territoriSpecifici().contains(territorio))
            return true;
        if (obj.continentiTarget() != null) {
            for (String cont : obj.continentiTarget()) {
                List<String> tc = RisikoBoardData.CONTINENTI.get(cont);
                if (tc != null && tc.contains(territorio)) return true;
            }
        }
        return false;
    }

    private String nomLegg(String t) {
        if (t == null) return "";
        String s = t.replace("_", " ");
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

// ── RIMUOVI il vecchio inizializzaMappa() ─────────────────────────────────
// (il metodo con 2 + RANDOM.nextInt(3) o 6 + RANDOM.nextInt(2) non serve più)

    // ── HINT ─────────────────────────────────────────────────────────────────

    public HintDto getHint(Long simId, String colore) {
        Optional<HintUsato> esistente = hintRepo.findBySimulazioneIdAndColore(simId, colore);
        if (esistente.isPresent())
            return new HintDto(colore, esistente.get().getTestoHint(), 0, true);

        GiocatoreSimulato giocatore = giocatoreRepo.findBySimulazioneId(simId).stream()
            .filter(g -> colore.equals(g.getColore())).findFirst()
            .orElseThrow(() -> new RuntimeException("Giocatore non trovato: " + colore));

        String testo = generaTesto(giocatore.getObiettivoId(), colore);

        HintUsato hint = new HintUsato();
        hint.setSimulazione(simRepo.getReferenceById(simId));
        hint.setColore(colore);
        hint.setTestoHint(testo);
        hintRepo.save(hint);

        return new HintDto(colore, testo, COSTO_HINT, false);
    }

    private String generaTesto(int obiettivoId, String colore) {
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);
        if (obj == null) return "Hint non disponibile.";
        String etichetta = lbl(colore);

        if (obj.minimoTerritoriTarget() > 0)
            return etichetta + " punta a conquistare almeno " + obj.minimoTerritoriTarget()
                + " territori — espansione globale senza zona preferenziale.";

        if (obj.continentiTarget() != null && !obj.continentiTarget().isEmpty()) {
            String cont = obj.continentiTarget().stream()
                .min(Comparator.comparingInt(k -> RisikoBoardData.CONTINENTI.getOrDefault(k, List.of()).size()))
                .orElse(obj.continentiTarget().get(0));
            return etichetta + " è interessato al controllo di: " + cap(cont.replace("_", " ")) + ".";
        }

        if (obj.territoriSpecifici() != null && !obj.territoriSpecifici().isEmpty())
            return etichetta + " mira a certi territori specifici in Asia orientale.";

        return etichetta + ": osserva i pattern di attacco e rinforzo nel tempo.";
    }

    // ── VALUTA RISPOSTA ───────────────────────────────────────────────────────

    public RisultatoSimulazioneDto valutaRisposta(Long simId, IndovinaObiettiviRequest req,
                                                   String userId, String difficolta) {
        List<GiocatoreSimulato> giocatori = giocatoreRepo.findBySimulazioneId(simId);
        if (giocatori.isEmpty()) throw new RuntimeException("Simulazione non trovata: " + simId);

        Map<String, Integer> segreti = giocatori.stream()
            .collect(Collectors.toMap(GiocatoreSimulato::getColore, GiocatoreSimulato::getObiettivoId));

        Map<String, Integer> risposte = Map.of(
            "BLU", req.blu(), "ROSSO", req.rosso(), "VERDE", req.verde(), "GIALLO", req.giallo()
        );

        int corretti = 0;
        List<DettaglioRisposta> dettagli = new ArrayList<>();
        for (String colore : COLORI) {
            int segreto  = segreti.getOrDefault(colore, -1);
            int risposta = risposte.getOrDefault(colore, -1);
            boolean ok   = segreto == risposta;
            if (ok) corretti++;
            String nome = Optional.ofNullable(RisikoBoardData.OBIETTIVI.get(segreto))
                .map(ObiettivoTarget::nome).orElse("?");
            dettagli.add(new DettaglioRisposta(colore, risposta, segreto, nome, ok));
        }

        long hintUsati = hintRepo.countBySimulazioneId(simId);
        int  puntiBase = corretti * 25;
        int  decurta   = (int) hintUsati * COSTO_HINT;
        int  punteggio = Math.max(0, puntiBase - decurta);

        if (userId != null) {
            SimulazioneRisultato res = new SimulazioneRisultato();
            res.setUserId(userId);
            res.setSimulazione(simRepo.getReferenceById(simId));
            res.setDifficolta(difficolta != null ? difficolta : "MEDIO");
            res.setCorretti(corretti);
            res.setPunteggio(punteggio);
            res.setHintUsati((int) hintUsati);
            res.setGiocataIl(LocalDateTime.now());
            risultatoRepo.save(res);
        }

        return new RisultatoSimulazioneDto(corretti, punteggio, (int) hintUsati, decurta, dettagli);
    }

    // ── STATISTICHE UTENTE ────────────────────────────────────────────────────

    public StatSimulazioneDto getStats(String userId) {
        List<SimulazioneRisultato> storico = risultatoRepo.findByUserIdOrderByGiocataIlDesc(userId);
        if (storico.isEmpty()) return new StatSimulazioneDto(0, 0, 0, 0.0);
        int    totale   = storico.size();
        int    perfetti = (int) storico.stream().filter(r -> r.getCorretti() == 4).count();
        int    maxPt    = storico.stream().mapToInt(SimulazioneRisultato::getPunteggio).max().orElse(0);
        double media    = storico.stream().mapToInt(SimulazioneRisultato::getCorretti).average().orElse(0);
        return new StatSimulazioneDto(totale, perfetti, maxPt, Math.round(media * 100) / 100.0);
    }

    // ── STORICO UTENTE ────────────────────────────────────────────────────────

    public List<SimulazioneRiepilogoDto> getStorico(String userId) {
        return risultatoRepo.findByUserIdOrderByGiocataIlDesc(userId)
            .stream()
            .limit(20)
            .map(r -> new SimulazioneRiepilogoDto(
                r.getPunteggio(), r.getCorretti(), r.getDifficolta(),
                r.getHintUsati(), r.getGiocataIl().toString()))
            .collect(Collectors.toList());
    }

    // ── CLASSIFICA GLOBALE (deduplicata: 1 per utente) ────────────────────────

    public List<ClassificaSimulazioneDto> getClassifica() {
        return risultatoRepo.findTop10Deduplicati().stream()
            .map(r -> new ClassificaSimulazioneDto(
                r.getUserId(), r.getPunteggio(), r.getCorretti(),
                r.getDifficolta(), r.getGiocataIl().toString()))
            .collect(Collectors.toList());
    }

    // ── PRIVATE ───────────────────────────────────────────────────────────────

    private Map<String, TerritoryState> inizializzaMappa() {
        List<String> tutti = new ArrayList<>(RisikoBoardData.TUTTI_TERRITORI);
        Collections.shuffle(tutti, RANDOM);
        int[] dims = {10, 10, 11, 11};

        Map<String, TerritoryState> mappa = new LinkedHashMap<>();
        int idx = 0;
        for (int p = 0; p < COLORI.length; p++)
            for (int t = 0; t < dims[p]; t++)
                mappa.put(tutti.get(idx++), new TerritoryState(COLORI[p], 6 + RANDOM.nextInt(2)));
        return mappa;
    }

    private Map<String, Integer> assegnaObiettivi() {
        List<Integer> pool = new ArrayList<>(OBIETTIVI_DISPONIBILI);
        Collections.shuffle(pool, RANDOM);
        Map<String, Integer> r = new LinkedHashMap<>();
        for (int i = 0; i < COLORI.length; i++) r.put(COLORI[i], pool.get(i));
        return r;
    }

    private SnapshotTurnoDto creaSnapshot(int turno,
                                          Map<String, TerritoryState> mappa,
                                          List<String> logAzioni,
                                          List<AttaccoEventoDto> attacchi,
                                          Map<String, StatoCarteDto> statoCarte,
                                          List<EventoCartinaDto> cartine,
                                          List<EventoTrisDto> tris,
                                          String fase,      // ← NUOVO
                                          String giocatore) { // ← NUOVO
        Map<String, TerritoryStateDto> snap = new LinkedHashMap<>();
        mappa.forEach((k, v) -> snap.put(k, new TerritoryStateDto(v.getColore(), v.getArmate())));
        return new SnapshotTurnoDto(turno, snap, new ArrayList<>(logAzioni),
                attacchi, statoCarte, cartine, tris, fase, giocatore);
    }

    private List<String> buildLogIniziale(Map<String, TerritoryState> mappa, String diff, int turni) {
        List<String> log = new ArrayList<>();
        log.add("🗺️ Simulazione " + diff + " — " + turni + " turni");
        for (String colore : COLORI) {
            long cnt  = mappa.values().stream().filter(ts -> colore.equals(ts.getColore())).count();
            int  arms = mappa.entrySet().stream()
                .filter(e -> colore.equals(e.getValue().getColore()))
                .mapToInt(e -> e.getValue().getArmate()).sum();
            log.add("  " + lbl(colore) + ": " + cnt + " territori, " + arms + " armate");
        }
        log.add("🎯 Indovina l'obiettivo di ogni giocatore!");
        return log;
    }

    private void aggiungiRiepilogoContinenti(List<String> logTurno, Map<String, TerritoryState> mappa) {
        for (Map.Entry<String, List<String>> e : RisikoBoardData.CONTINENTI.entrySet()) {
            List<String> terr = e.getValue();
            long colorCount = terr.stream()
                .map(t -> mappa.get(t) != null ? mappa.get(t).getColore() : "?")
                .distinct().count();
            if (colorCount == 1) {
                String owner = mappa.get(terr.get(0)).getColore();
                logTurno.add("🌍 " + lbl(owner) + " controlla tutto: " + cap(e.getKey().replace("_", " ")));
            }
        }
    }

    private String lbl(String c) {
        return switch (c) {
            case "BLU"    -> "🔵 Blu";
            case "ROSSO"  -> "🔴 Rosso";
            case "VERDE"  -> "🟢 Verde";
            case "GIALLO" -> "🟡 Giallo";
            default       -> c;
        };
    }

    private String cap(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }


}
