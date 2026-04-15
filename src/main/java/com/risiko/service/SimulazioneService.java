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

    public NuovaSimulazioneDto generaSimulazione(NuovaSimulazioneRequest req) {
        String diff  = req.difficolta().toUpperCase();
        int    turni = switch (diff) {
            case "FACILE" -> 35;  // 35 normali + T25(≤4) T26(≤5) T27(≤6) T28+(≤7)
            case "MEDIO"  -> 20;  // nessuna sdadata (parte dal turno 25)
            default       -> req.turni();
        };

        Map<String, TerritoryState> mappa              = inizializzaMappa();
        Map<String, Integer>        obiettiviPerColore = assegnaObiettivi();

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

        List<SnapshotTurnoDto> snapshots = new ArrayList<>();
        snapshots.add(creaSnapshot(0, mappa, buildLogIniziale(mappa, diff, turni)));

        Set<String> attivi = new LinkedHashSet<>(Arrays.asList(COLORI));
        boolean partitaFinita = false;

        for (int t = 1; t <= turni && !partitaFinita; t++) {
            List<String> logTurno = new ArrayList<>();
            logTurno.add("══════ TURNO " + t + " / " + turni + " ══════");

            for (String colore : List.copyOf(attivi)) {
                if (aiService.getTerritoriGiocatore(colore, mappa).isEmpty()) {
                    attivi.remove(colore);
                    logTurno.add("💀 " + lbl(colore) + " è stato eliminato!");
                    continue;
                }

                AIPlayerService.TurnoResult risultato = aiService.eseguiTurno(
                        colore, obiettiviPerColore.get(colore), t, mappa);
                logTurno.addAll(risultato.log());

                // Sdadata: partita finita
                if (risultato.sdadata() != null && risultato.sdadata().riuscita()) {
                    logTurno.add("🏆 " + lbl(colore) + " vince con la sdadata!");
                    snapshots.add(creaSnapshot(t, mappa, logTurno));
                    partitaFinita = true;
                    break;
                }

                for (String altro : List.copyOf(attivi)) {
                    if (!altro.equals(colore) && aiService.getTerritoriGiocatore(altro, mappa).isEmpty()) {
                        attivi.remove(altro);
                        logTurno.add("💀 " + lbl(altro) + " è stato eliminato!");
                    }
                }
            }

            if (!partitaFinita) {
                aggiungiRiepilogoContinenti(logTurno, mappa);
                snapshots.add(creaSnapshot(t, mappa, logTurno));

                if (attivi.size() == 1) {
                    String vincitore = attivi.iterator().next();
                    snapshots.add(creaSnapshot(t + 1, mappa,
                            List.of("🏆 " + lbl(vincitore) + " ha conquistato il mondo! Simulazione terminata.")));
                    break;
                }
            }
        }

        return new NuovaSimulazioneDto(sim.getId(), turni, diff, snapshots);
    }
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
        for (int i = dims.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            int tmp = dims[i]; dims[i] = dims[j]; dims[j] = tmp;
        }
        Map<String, TerritoryState> mappa = new LinkedHashMap<>();
        int idx = 0;
        for (int p = 0; p < COLORI.length; p++)
            for (int t = 0; t < dims[p]; t++)
                mappa.put(tutti.get(idx++), new TerritoryState(COLORI[p], 2 + RANDOM.nextInt(3)));
        return mappa;
    }

    private Map<String, Integer> assegnaObiettivi() {
        List<Integer> pool = new ArrayList<>(OBIETTIVI_DISPONIBILI);
        Collections.shuffle(pool, RANDOM);
        Map<String, Integer> r = new LinkedHashMap<>();
        for (int i = 0; i < COLORI.length; i++) r.put(COLORI[i], pool.get(i));
        return r;
    }

    private SnapshotTurnoDto creaSnapshot(int turno, Map<String, TerritoryState> mappa, List<String> logAzioni) {
        Map<String, TerritoryStateDto> snap = new LinkedHashMap<>();
        mappa.forEach((k, v) -> snap.put(k, new TerritoryStateDto(v.getColore(), v.getArmate())));
        return new SnapshotTurnoDto(turno, snap, new ArrayList<>(logAzioni));
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
