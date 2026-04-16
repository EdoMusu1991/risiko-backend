package com.risiko.service;

import com.risiko.service.RisikoBoardData.ObiettivoTarget;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AIPlayerService — Logica AI per la simulazione RisiKo!
 *
 * Regole implementate:
 * - Rinforzi: max(3, territori/3) + bonus continente
 * - Attacca solo con almeno il doppio delle armate del difensore
 * - Difende sempre con il massimo dei dadi (fino a 3)
 * - Lascia minimo 2 armate nel territorio da cui attacca
 * - Tris usato strategicamente (non appena ha 3 carte)
 * - Bonus tris +2 per ogni territorio in carta posseduto
 * - Sdadata: non conquista più di 2 territori se vuole sdadare
 * - Sdada solo se è in vantaggio di punteggio
 * - Blocca continenti avversari con alta priorità
 * - Priorità obiettivo + equilibrio partita in parallelo
 */
@Service
public class AIPlayerService {

    private static final Random RND = new Random();

    // ── Minimo armate lasciate in un territorio ───────────────────────────────
    private static final int MIN_ARMATE_DIFESA = 2;

    // ── Soglia minima territori per non scendere sotto 3 rinforzi/turno ───────
    private static final int SOGLIA_MIN_TERRITORI = 7;

    // ── Ordine preferenziale continenti da completare ─────────────────────────
    private static final List<String> ORDINE_CONTINENTI = List.of(
            "oceania", "sudamerica", "africa", "europa", "nordamerica", "asia"
    );

    // ── Territori strategici ad alto valore ───────────────────────────────────
    private static final Set<String> TERRITORI_STRATEGICI = Set.of(
            "medio_oriente", "ucraina", "ontario", "cina",
            "kamchatka", "africa_del_nord", "egitto", "brasile",
            "venezuela", "europa_settentrionale", "afghanistan", "mongolia"
    );

    // ── Sistema carte ─────────────────────────────────────────────────────────
    private enum Simbolo { FANTE, CANNONE, CAVALLO, JOLLY }
    private record Carta(String territorio, Simbolo simbolo) {}

    private final Map<String, List<Carta>> maniCarte = new HashMap<>();
    private final Map<String, Integer> territorConquistatiNelTurno = new HashMap<>();

    // ── Sistema cartina ───────────────────────────────────────────────────────
    // Tiene traccia degli accordi di cartina attivi: colore → partner
    private final Map<String, String> accordiCartina = new HashMap<>();
    // Territorio lasciato scoperto per cartina: colore → territorio
    private final Map<String, String> territoriCartina = new HashMap<>();
    // Armate precedenti per rilevare spostamenti (territorio → armate turno prima)
    private final Map<String, Integer> armaturePrecedenti = new HashMap<>();
    // Turni in cui un territorio è rimasto a 2 armate (segnale cartina): territorio → turni
    private final Map<String, Integer> turniTerritorioScoperto = new HashMap<>();
    // Turno in cui è stata offerta la cartina: colore → turno
    private final Map<String, Integer> turnoOffertaCartina = new HashMap<>();

    // ── Inferenza obiettivi avversari ─────────────────────────────────────────
    private final ObiettivoInferencer inferencer = new ObiettivoInferencer();
    private boolean inferencerInizializzato = false;

    // ── Tracking eventi per il frontend ──────────────────────────────────────
    private final List<EventoAttacco>   eventiAttaccoTurno  = new ArrayList<>();
    private final List<EventoCartina>   eventiCartinaTurno  = new ArrayList<>();
    private final List<EventoTris>      eventiTrisTurno     = new ArrayList<>();

    // ═══════════════════════════════════════════════════════════════════════════
    //  RECORD DI OUTPUT (NUOVO)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Risultato di un turno completo: log + sdadata + attacchi + stato carte.
     */
    public record TurnoResult(
            List<String>              log,
            SdadataResult             sdadata,
            List<EventoAttacco>       attacchi,
            Map<String, StatoCarte>   statoCarte,
            List<EventoCartina>       cartine,
            List<EventoTris>          tris) {}

    /**
     * Risultato di una singola fase del turno di un giocatore.
     * SimulazioneService crea uno snapshot per ogni FaseResult.
     */
    public record FaseResult(
            String               fase,        // "TRIS","RINFORZI","ATTACCHI","SPOSTAMENTO","CARTINA"
            String               colore,
            int                  turno,
            List<String>         log,
            List<EventoAttacco>  attacchi,
            Map<String, StatoCarte> statoCarte,
            List<EventoCartina>  cartine,
            List<EventoTris>     tris,
            SdadataResult        sdadata) {}  // non-null solo nell'ultima fase

    /** Singolo attacco del turno (usato per le frecce SVG nel frontend). */
    public record EventoAttacco(
            String  da,
            String  verso,
            String  coloreAttaccante,
            String  coloreDifensore,
            boolean conquistato) {}

    /** Uso di un tris in questo turno. */
    public record EventoTris(
            String colore,
            int    bonus,
            String tipo) {} // "DIVERSI", "UGUALI", "JOLLY"

    /** Accordo di cartina rilevato o offerto in questo turno. */
    public record EventoCartina(
            String coloreOffre,
            String coloreRiceve,
            String territorio) {}

    /** Stato mano carte di un giocatore. */
    public record StatoCarte(int fanti, int cannoni, int cavalli, int jolly) {
        public int totale() { return fanti + cannoni + cavalli + jolly; }
    }

    /**
     * Esito del tentativo di sdadata.
     *
     * @param tentato  true se le condizioni erano soddisfatte e l'AI ha tentato
     * @param riuscita true se la somma dei dadi ≤ soglia del turno
     * @param dado1    valore primo dado
     * @param dado2    valore secondo dado
     * @param totale   somma dado1+dado2
     * @param soglia   soglia massima (T35=4, T36=5, T37=6, T38+=7)
     */
    public record SdadataResult(
            boolean tentato, boolean riuscita,
            int dado1, int dado2, int totale, int soglia) {}

    // ═══════════════════════════════════════════════════════════════════════════
    //  DISTRIBUZIONE INIZIALE (NUOVO — vincolo max metà continente)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Distribuisce i 42 territori tra i giocatori rispettando:
     * - Nessun giocatore può avere più della metà dei territori di un continente
     *   (es. max 2 in Oceania che ne ha 4, max 3 in Africa che ne ha 6)
     * - Con 4 giocatori: 1° e 2° di mano ricevono 10 territori, 3° e 4° ricevono 11
     *
     * @param colori  lista ordinata dei colori (ordine di mano)
     * @param rnd     generatore random per lo shuffle iniziale
     * @return mappa colore → lista di territori assegnati
     */
    public static Map<String, List<String>> distribuisciTerritori(List<String> colori, Random rnd) {
        List<String> tutti = new ArrayList<>(RisikoBoardData.TUTTI_TERRITORI);
        Collections.shuffle(tutti, rnd);

        int n = colori.size();
        int base  = 42 / n;
        int extra = 42 % n; // con 4 giocatori = 2 → gli ultimi 2 di mano prendono 1 in più
        int[] quote = new int[n];
        for (int i = 0; i < n; i++) quote[i] = base + (i >= (n - extra) ? 1 : 0);

        // Cap: nessuno può avere più di floor(size/2) territori per continente
        Map<String, Integer> limiti = new HashMap<>();
        RisikoBoardData.CONTINENTI.forEach((cont, terr) -> limiti.put(cont, terr.size() / 2));

        Map<String, List<String>> assegnazioni = new LinkedHashMap<>();
        colori.forEach(c -> assegnazioni.put(c, new ArrayList<>()));

        List<String> pool = new ArrayList<>(tutti);

        for (int gi = 0; gi < n; gi++) {
            String colore = colori.get(gi);
            int target    = quote[gi];

            while (assegnazioni.get(colore).size() < target && !pool.isEmpty()) {
                // Cerca il primo territorio che non viola il cap per nessun continente
                Optional<String> scelto = pool.stream()
                        .filter(t -> distribuzioneNonViolaCap(t, colore, assegnazioni, limiti))
                        .findFirst();

                // Fallback: se il cap rende impossibile, prendi il primo disponibile
                String t = scelto.orElse(pool.get(0));
                assegnazioni.get(colore).add(t);
                pool.remove(t);
            }
        }
        return assegnazioni;
    }

    private static boolean distribuzioneNonViolaCap(String territorio, String colore,
                                                    Map<String, List<String>> assegnazioni,
                                                    Map<String, Integer> limiti) {
        for (Map.Entry<String, List<String>> ce : RisikoBoardData.CONTINENTI.entrySet()) {
            if (!ce.getValue().contains(territorio)) continue;
            long gia = assegnazioni.get(colore).stream().filter(ce.getValue()::contains).count();
            if (gia >= limiti.get(ce.getKey())) return false;
        }
        return true;
    }

    private boolean nonViolaCap(String territorio, String colore,
                                List<String> giàAssegnati,
                                Map<String, Integer> limiti) {
        for (Map.Entry<String, List<String>> ce : RisikoBoardData.CONTINENTI.entrySet()) {
            if (!ce.getValue().contains(territorio)) continue;
            long giàInCont = giàAssegnati.stream().filter(ce.getValue()::contains).count();
            if (giàInCont >= limiti.get(ce.getKey())) return false;
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PIAZZAMENTO INIZIALE (fase alternata, 3 carri per turno)
    // ═══════════════════════════════════════════════════════════════════════════

    public void piazzaIniziale(String colore, int obiettivoId,
                               Map<String, TerritoryState> mappa,
                               Map<String, Integer> carriPiazzatiDaAltri) {
        List<String> miei = getTerritoriGiocatore(colore, mappa);
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);

        // Calcola dove gli altri stanno piazzando molto (da difendere)
        Set<String> territoriCaldi = carriPiazzatiDaAltri.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        // Priorità piazzamento:
        // 1. Territori in obiettivo confinanti con territori caldi nemici
        // 2. Territori in obiettivo di confine
        // 3. Territori fuori obiettivo (minimi, solo 1 o 2 carri)
        List<String> inObiettivo = miei.stream()
                .filter(t -> isInObiettivo(t, obj))
                .collect(Collectors.toList());
        List<String> fuoriObiettivo = miei.stream()
                .filter(t -> !isInObiettivo(t, obj))
                .collect(Collectors.toList());

        int carriDaPiazzare = 3;

        // Prima i territori in obiettivo più minacciati
        for (String t : inObiettivo) {
            if (carriDaPiazzare <= 0) break;
            boolean minacciato = getConfinanti(t).stream()
                    .anyMatch(c -> territoriCaldi.contains(c) &&
                            !colore.equals(mappa.getOrDefault(c, new TerritoryState("?", 0)).getColore()));
            if (minacciato) {
                mappa.get(t).setArmate(mappa.get(t).getArmate() + 1);
                carriDaPiazzare--;
            }
        }

        // Poi i rimanenti sui territori in obiettivo più deboli
        if (carriDaPiazzare > 0) {
            inObiettivo.stream()
                    .sorted(Comparator.comparingInt(t -> mappa.get(t).getArmate()))
                    .limit(carriDaPiazzare)
                    .forEach(t -> mappa.get(t).setArmate(mappa.get(t).getArmate() + 1));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  TURNO COMPLETO
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Esegue il turno e restituisce la lista di fasi.
     * Ogni fase corrisponde a uno snapshot nel replay.
     */
    public List<FaseResult> eseguiFasi(String colore, int obiettivoId, int turno,
                                       Map<String, TerritoryState> mappa) {
        List<FaseResult> fasi = new ArrayList<>();

        // Inizializza l'inferencer al primo utilizzo
        if (!inferencerInizializzato) {
            Set<String> avversari = mappa.values().stream()
                    .map(TerritoryState::getColore)
                    .filter(c -> !colore.equals(c) && !"?".equals(c))
                    .collect(Collectors.toSet());
            inferencer.inizializza(avversari, mappa);
            inferencerInizializzato = true;
        }

        List<String> miei = getTerritoriGiocatore(colore, mappa);
        if (miei.isEmpty()) return fasi;

        territorConquistatiNelTurno.put(colore, 0);
        eventiAttaccoTurno.clear();
        eventiCartinaTurno.clear();
        eventiTrisTurno.clear();
        // Rimuovi marker territorio cartina conquistato (vale solo nel turno stesso)
        territoriCartina.remove(colore + "_conquistato");
        // Rimuovi flag spostamento cartina (vale solo nel turno stesso)
        territoriCartina.remove(colore + "_spostato");

        // ── FASE 1: TRIS ──────────────────────────────────────────────────────
        {
            List<String> log = new ArrayList<>();
            log.add("🃏 " + lbl(colore) + " — Fase tris (T" + turno + ")");
            eventiTrisTurno.clear();
            usaTrisSeUtile(colore, obiettivoId, new ArrayList<>(getTerritoriGiocatore(colore, mappa)), mappa, log);
            if (log.size() > 1) { // ha fatto qualcosa
                fasi.add(new FaseResult("TRIS", colore, turno, new ArrayList<>(log),
                        List.of(), buildStatoCarteCorrente(), List.of(),
                        List.copyOf(eventiTrisTurno), null));
            }
            eventiTrisTurno.clear();
        }

        // ── FASE 2: RINFORZI ──────────────────────────────────────────────────
        {
            List<String> log = new ArrayList<>();
            log.add("🛡 " + lbl(colore) + " — Fase rinforzi (T" + turno + ")");
            int rinforzi = calcolaRinforzi(colore, getTerritoriGiocatore(colore, mappa), mappa);
            piazzaRinforzi(colore, rinforzi, obiettivoId, turno, getTerritoriGiocatore(colore, mappa), mappa, log);
            fasi.add(new FaseResult("RINFORZI", colore, turno, new ArrayList<>(log),
                    List.of(), buildStatoCarteCorrente(), List.of(), List.of(), null));
        }

        // ── FASE 3: ATTACCHI ──────────────────────────────────────────────────
        {
            List<String> log = new ArrayList<>();
            log.add("⚔️ " + lbl(colore) + " — Fase attacchi (T" + turno + ")");
            eventiAttaccoTurno.clear();
            eventiCartinaTurno.clear();
            eseguiAttacchi(colore, obiettivoId, turno, mappa, log);

            // Pesca carta
            if (territorConquistatiNelTurno.getOrDefault(colore, 0) > 0) {
                pescaCarta(colore);
                log.add("🎴 " + lbl(colore) + " pesca una carta");
            }

            // Cartina
            gestisciCartina(colore, obiettivoId, turno, mappa, log);

            if (log.size() > 1) {
                fasi.add(new FaseResult("ATTACCHI", colore, turno, new ArrayList<>(log),
                        List.copyOf(eventiAttaccoTurno), buildStatoCarteCorrente(),
                        List.copyOf(eventiCartinaTurno), List.of(), null));
            }
            eventiAttaccoTurno.clear();
            eventiCartinaTurno.clear();
        }

        // ── FASE 4: SPOSTAMENTO ───────────────────────────────────────────────
        {
            List<String> log = new ArrayList<>();
            log.add("🚀 " + lbl(colore) + " — Fase spostamento (T" + turno + ")");
            eseguiSpostamento(colore, obiettivoId, turno, mappa, log);

            // Sdadata
            SdadataResult sdadata = valutaSdadata(colore, obiettivoId, turno, mappa);
            if (sdadata != null) {
                log.add(sdadata.riuscita()
                        ? "🎲 SDADATA! " + sdadata.dado1() + "+" + sdadata.dado2() + "=" + sdadata.totale() + " ≤ " + sdadata.soglia()
                        : "🎲 Sdadata fallita: " + sdadata.dado1() + "+" + sdadata.dado2() + "=" + sdadata.totale() + " > " + sdadata.soglia());
            }

            // Aggiorna inferencer
            inferencer.aggiornaTurno(mappa);
            salvaArmaturePrecedenti(mappa);

            fasi.add(new FaseResult("SPOSTAMENTO", colore, turno, new ArrayList<>(log),
                    List.of(), buildStatoCarteCorrente(), List.of(), List.of(), sdadata));
        }

        return fasi;
    }

    /** Mantiene la vecchia firma per compatibilità — delega a eseguiFasi. */
    public TurnoResult eseguiTurno(String colore, int obiettivoId, int turno,
                                   Map<String, TerritoryState> mappa) {
        List<FaseResult> fasi = eseguiFasi(colore, obiettivoId, turno, mappa);
        List<String> logTot = new ArrayList<>();
        List<EventoAttacco> att = new ArrayList<>();
        List<EventoCartina> cart = new ArrayList<>();
        List<EventoTris> tris = new ArrayList<>();
        SdadataResult sdadata = null;
        for (FaseResult f : fasi) {
            logTot.addAll(f.log());
            att.addAll(f.attacchi());
            cart.addAll(f.cartine());
            tris.addAll(f.tris());
            if (f.sdadata() != null) sdadata = f.sdadata();
        }
        Map<String, StatoCarte> sc = fasi.isEmpty() ? Map.of()
                : fasi.get(fasi.size()-1).statoCarte();
        return new TurnoResult(logTot, sdadata, att, sc, cart, tris);
    }

    /** Snapshot dello stato carte attuale di tutti i giocatori. */
    private Map<String, StatoCarte> buildStatoCarteCorrente() {
        Map<String, StatoCarte> result = new HashMap<>();
        maniCarte.forEach((c, mano) -> {
            int f=0,ca=0,k=0,j=0;
            for (Carta carta : mano) {
                switch (carta.simbolo()) {
                    case FANTE   -> f++;
                    case CANNONE -> ca++;
                    case CAVALLO -> k++;
                    case JOLLY   -> j++;
                }
            }
            result.put(c, new StatoCarte(f, ca, k, j));
        });
        return result;
    }



    // ═══════════════════════════════════════════════════════════════════════════
    //  SISTEMA CARTE E TRIS
    // ═══════════════════════════════════════════════════════════════════════════

    private void pescaCarta(String colore) {
        List<String> tutti = new ArrayList<>(RisikoBoardData.TUTTI_TERRITORI);
        String terr = tutti.get(RND.nextInt(tutti.size()));
        Simbolo sim = Simbolo.values()[RND.nextInt(3)]; // FANTE, CANNONE, CAVALLO
        maniCarte.computeIfAbsent(colore, k -> new ArrayList<>()).add(new Carta(terr, sim));
    }

    private void usaTrisSeUtile(String colore, int obiettivoId, List<String> miei,
                                Map<String, TerritoryState> mappa, List<String> log) {
        List<Carta> mano = maniCarte.getOrDefault(colore, new ArrayList<>());
        if (mano.size() < 3) return;

        int bonusTris = trovaTris(mano);
        if (bonusTris == 0) return;

        // Usa il tris solo se è strategicamente utile:
        // Deve poter attaccare con almeno il doppio dopo aver ricevuto i bonus
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);

        // Calcola bonus territorio in carta (+2 per ogni territorio in carta posseduto)
        List<Carta> tris = mano.subList(0, 3);
        int bonusTerritori = 0;
        List<String> territoriBonus = new ArrayList<>();
        for (Carta c : tris) {
            if (c.simbolo() != Simbolo.JOLLY && miei.contains(c.territorio())) {
                bonusTerritori += 2;
                territoriBonus.add(c.territorio());
            }
        }

        // ── FIX: distribuisce armate tris sui confini in obiettivo più deboli ──
        // (non tutte su un solo territorio come nella versione precedente)
        List<String> confiniObiettivo = miei.stream()
                .filter(t -> isInObiettivo(t, obj))
                .filter(t -> !getConfinanti(t).stream()
                        .allMatch(c -> colore.equals(mappa.getOrDefault(c,
                                new TerritoryState(colore, 0)).getColore())))
                .sorted(Comparator.comparingInt(t -> mappa.get(t).getArmate()))
                .collect(Collectors.toList());

        if (confiniObiettivo.isEmpty()) {
            confiniObiettivo = miei.stream()
                    .sorted(Comparator.comparingInt(t -> mappa.get(t).getArmate()))
                    .collect(Collectors.toList());
        }

        // Distribuisce ciclicamente: 3 sul primo (concentrazione), poi 1 alla volta
        int rim = bonusTris;
        int iniziali = Math.min(3, rim);
        String primo = confiniObiettivo.get(0);
        mappa.get(primo).setArmate(mappa.get(primo).getArmate() + iniziali);
        rim -= iniziali;
        int idx = 1;
        while (rim > 0) {
            String t = confiniObiettivo.get(idx % confiniObiettivo.size());
            mappa.get(t).setArmate(mappa.get(t).getArmate() + 1);
            rim--;
            idx++;
        }
        log.add("🃏 " + lbl(colore) + " usa tris (" + bonusTris + " armate) su " +
                confiniObiettivo.stream().limit(3).map(this::nom).collect(Collectors.joining(", ")));

        // Applica bonus +2 per ogni territorio in carta posseduto
        for (String t : territoriBonus) {
            mappa.get(t).setArmate(mappa.get(t).getArmate() + 2);
            log.add("🃏 " + lbl(colore) + " bonus carta +2 → " + nom(t));
        }

        // Rimuovi le 3 carte usate
        // Determina tipo tris per il frontend
        List<Carta> prime3 = mano.subList(0, 3);
        Map<Simbolo, Long> cnt = prime3.stream()
                .collect(Collectors.groupingBy(Carta::simbolo, Collectors.counting()));
        String tipoTris = cnt.getOrDefault(Simbolo.JOLLY, 0L) >= 1 ? "JOLLY"
                : cnt.values().stream().anyMatch(v -> v >= 3) ? "UGUALI"
                : "DIVERSI";
        eventiTrisTurno.add(new EventoTris(colore, bonusTris, tipoTris));

        mano.subList(0, 3).clear();
    }

    /**
     * Cerca il miglior tris disponibile nella mano (non solo le prime 3 carte).
     * Ordine preferenza: Jolly+2uguali(12) > 3uguali(8) > Fante+Cannone+Cavallo(10)
     * Se trovato, riordina la mano mettendo le 3 carte del tris in cima.
     */
    private int trovaTris(List<Carta> mano) {
        if (mano.size() < 3) return 0;

        Map<Simbolo, List<Carta>> perSimbolo = new HashMap<>();
        for (Carta c : mano) perSimbolo.computeIfAbsent(c.simbolo(), k -> new ArrayList<>()).add(c);

        List<Carta> f = perSimbolo.getOrDefault(Simbolo.FANTE,   List.of());
        List<Carta> ca = perSimbolo.getOrDefault(Simbolo.CANNONE, List.of());
        List<Carta> k  = perSimbolo.getOrDefault(Simbolo.CAVALLO, List.of());
        List<Carta> j  = perSimbolo.getOrDefault(Simbolo.JOLLY,   List.of());

        // Jolly + 2 uguali (valore 12)
        if (!j.isEmpty()) {
            for (List<Carta> gruppo : List.of(f, ca, k)) {
                if (gruppo.size() >= 2) {
                    List<Carta> tris = new ArrayList<>();
                    tris.add(j.get(0)); tris.add(gruppo.get(0)); tris.add(gruppo.get(1));
                    riordinaMano(mano, tris);
                    return 12;
                }
            }
        }

        // 3 uguali (valore 8)
        for (List<Carta> gruppo : List.of(f, ca, k)) {
            if (gruppo.size() >= 3) {
                List<Carta> tris = new ArrayList<>(gruppo.subList(0, 3));
                riordinaMano(mano, tris);
                return 8;
            }
        }

        // Fante + Cannone + Cavallo (valore 10)
        if (!f.isEmpty() && !ca.isEmpty() && !k.isEmpty()) {
            List<Carta> tris = List.of(f.get(0), ca.get(0), k.get(0));
            riordinaMano(mano, tris);
            return 10;
        }

        return 0;
    }

    /** Porta le 3 carte del tris in cima alla mano per l'uso successivo. */
    private void riordinaMano(List<Carta> mano, List<Carta> tris) {
        mano.removeAll(tris);
        mano.addAll(0, tris);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  META-PRIORITÀ: PREPARARSI ALLA SDADATA
    //  Valuta quanto il bot è "pronto" per vincere in sdadata rispetto agli avversari.
    //  Restituisce un moltiplicatore d'urgenza [0.5 - 2.0]:
    //   > 1.0 = situazione favorevole → meno urgenza, gioca difensivo
    //   < 1.0 = situazione sfavorevole → più urgenza, gioca aggressivo
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Calcola l'urgenza di intervenire per migliorare la posizione pre-sdadata.
     * Considera: punteggio relativo, territori, continenti nemici, obiettivi esclusivi.
     * Restituisce un bonus di priorità da aggiungere agli attacchi urgenti.
     */
    private int calcolaUrgenzaSdadata(String colore, int obiettivoId,
                                      Map<String, TerritoryState> mappa, int turno) {
        if (turno > 33) return 0; // in sdadata la logica è diversa

        int urgenza = 0;
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);
        List<String> miei = getTerritoriGiocatore(colore, mappa);
        int mioScore = calcolaPunteggioAttuale(colore, obiettivoId, mappa);
        int maxScoreAvv = getMassimoPunteggioAvversari(colore, mappa);

        // ── 1. DISTACCO PUNTEGGIO ─────────────────────────────────────────────
        // Se sono indietro di punti rispetto al leader → urgenza alta
        int distacco = maxScoreAvv - mioScore;
        if (distacco > 20) urgenza += 300;
        else if (distacco > 10) urgenza += 150;
        else if (distacco > 0) urgenza += 50;
        else urgenza -= 100; // sono in testa → meno urgenza

        // ── 2. TERRITORI RELATIVI ─────────────────────────────────────────────
        // Chi ha più territori piazza più carri → vantaggio strutturale
        int maxTerrAvv = mappa.values().stream()
                .map(TerritoryState::getColore)
                .filter(c -> !c.equals(colore) && !"?".equals(c))
                .distinct()
                .mapToInt(c -> getTerritoriGiocatore(c, mappa).size())
                .max().orElse(0);
        if (maxTerrAvv > miei.size() + 2) urgenza += 200; // nemico ha molti più territori
        else if (maxTerrAvv > miei.size()) urgenza += 80;

        // ── 3. CONTINENTE NEMICO ──────────────────────────────────────────────
        // Se un avversario ha un continente → +5/+7 rinforzi extra ogni turno
        // È un vantaggio che si accumula → contrasto urgente
        boolean nemicoContinente = mappa.values().stream()
                .map(TerritoryState::getColore)
                .filter(c -> !c.equals(colore) && !"?".equals(c))
                .distinct()
                .anyMatch(c -> {
                    List<String> loro = getTerritoriGiocatore(c, mappa);
                    return RisikoBoardData.CONTINENTI.values().stream().anyMatch(loro::containsAll);
                });
        if (nemicoContinente) urgenza += 250;

        // ── 4. OBIETTIVO ESCLUSIVO NEMICO ─────────────────────────────────────
        // Se un avversario ha molti territori in obiettivo che solo lui vuole
        // (nessun altro li contesta) → vantaggio crescente → contrasto urgente
        long nemiciConObiettivoPulito = mappa.values().stream()
                .map(TerritoryState::getColore)
                .filter(c -> !c.equals(colore) && !"?".equals(c))
                .distinct()
                .filter(c -> {
                    int obAvv = inferencer.getObiettivoPiuProbabile(c);
                    RisikoBoardData.ObiettivoTarget objAvv = RisikoBoardData.OBIETTIVI.get(obAvv);
                    if (objAvv == null || objAvv.territoriSpecifici() == null) return false;
                    List<String> lorTerr = getTerritoriGiocatore(c, mappa);
                    // Ha già più della metà dei suoi territori in obiettivo esclusivo
                    long inObj = lorTerr.stream().filter(t -> isInObiettivo(t, objAvv)).count();
                    return inObj > objAvv.territoriSpecifici().size() / 2;
                })
                .count();
        urgenza += (int)(nemiciConObiettivoPulito * 120);

        // ── 5. MIO OBIETTIVO ESCLUSIVO ────────────────────────────────────────
        // Se ho territori in obiettivo che solo io voglio → sono in vantaggio
        if (obj != null && obj.territoriSpecifici() != null) {
            long mieTerrInObj = miei.stream().filter(t -> isInObiettivo(t, obj)).count();
            long totaleObj = obj.territoriSpecifici().size();
            double percentuale = (double) mieTerrInObj / totaleObj;
            if (percentuale > 0.6) urgenza -= 150; // sono avanti nel mio obiettivo
            else if (percentuale < 0.3) urgenza += 100; // sono indietro
        }

        // ── 6. TURNI RIMASTI ALLA SDADATA ─────────────────────────────────────
        // Avvicinarsi al turno 35 aumenta l'urgenza di sistemare tutto
        int turniRimasti = 34 - turno;
        if (turniRimasti <= 5) urgenza = (int)(urgenza * 1.5); // amplifica urgenza finale
        else if (turniRimasti <= 10) urgenza = (int)(urgenza * 1.2);

        return Math.max(0, urgenza);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  RINFORZI
    // ═══════════════════════════════════════════════════════════════════════════

    private int calcolaRinforzi(String colore, List<String> miei,
                                Map<String, TerritoryState> mappa) {
        int base = Math.max(3, miei.size() / 3);
        int bonus = 0;
        // Bonus continente completato
        for (Map.Entry<String, List<String>> e : RisikoBoardData.CONTINENTI.entrySet()) {
            if (miei.containsAll(e.getValue())) {
                bonus += bonusContinente(e.getKey());
            }
        }
        return base + bonus;
    }

    private int bonusContinente(String continente) {
        return switch (continente) {
            case "nordamerica" -> 5;
            case "sudamerica"  -> 2;
            case "europa"      -> 5;
            case "africa"      -> 3;
            case "asia"        -> 7;
            case "oceania"     -> 2;
            default            -> 0;
        };
    }

    private static final int MAX_ARMATE_TOTALI = 130;

    private void piazzaRinforzi(String colore, int rinforzi, int obiettivoId, int turno,
                                List<String> miei, Map<String, TerritoryState> mappa,
                                List<String> log) {
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);

        // ── CAP 130 armate totali ─────────────────────────────────────────────
        int armateAttuali = miei.stream().mapToInt(t -> mappa.get(t).getArmate()).sum();
        int spazio = Math.max(0, MAX_ARMATE_TOTALI - armateAttuali);
        if (spazio == 0) {
            log.add("🚫 " + lbl(colore) + " ha raggiunto il limite di " + MAX_ARMATE_TOTALI + " armate");
            return;
        }
        int rim = Math.min(rinforzi, spazio);

        List<String> confine = getTerritoriBordoNemico(colore, miei, mappa);
        if (confine.isEmpty()) confine = miei;

        // ── FASE ASSOLUTA: territori in obiettivo mai a 1-2 armate (salvo nicchia) ──
        // Questa è la regola più assoluta di tutte — vale a qualsiasi turno,
        // prima di qualsiasi altra considerazione strategica.
        // L'Ucraina a 2 armate in sdadata è inaccettabile.
        {
            for (String t : new ArrayList<>(miei)) {
                if (!isInObiettivo(t, obj)) continue;
                if (mappa.get(t).getArmate() > 2) continue;

                // Nicchia = normalmente al sicuro, MA non per territori in obiettivo
                // con ≥5 adiacenze (troppo preziosi — se la nicchia si rompe sono esposti)
                boolean nicchia = getConfinanti(t).stream().allMatch(adj ->
                        colore.equals(mappa.getOrDefault(adj, new TerritoryState("?",0)).getColore()));
                int adj = getConfinanti(t).size();
                if (nicchia && adj < 5) continue; // piccoli in nicchia: ok a 1-2

                // Territorio in obiettivo con ≥5 punti: minimo proporzionale al valore
                // anche in nicchia (se la nicchia si rompe deve poter resistere)
                int adjT2 = getConfinanti(t).size();
                int target = adjT2 >= 6 ? 10 : adjT2 >= 5 ? 8 : 3;
                int mancanti = target - mappa.get(t).getArmate();

                // Prima usa i rinforzi disponibili
                if (rim >= mancanti) {
                    mappa.get(t).setArmate(target);
                    rim -= mancanti;
                } else {
                    // Poi prendi da qualsiasi territorio con più di 3 armate
                    // (interni prima, poi confine — l'importante è proteggere l'obiettivo)
                    // In sdadata non scendere sotto 3 rubando armate
                    int minimoFonte = (turno >= 35) ? 3 : 1;
                    for (String fonte : miei) {
                        if (fonte.equals(t)) continue;
                        if (mappa.get(fonte).getArmate() <= Math.max(3, minimoFonte)) continue;
                        mappa.get(fonte).setArmate(mappa.get(fonte).getArmate() - 1);
                        mappa.get(t).setArmate(mappa.get(t).getArmate() + 1);
                        if (mappa.get(t).getArmate() >= target) break;
                    }
                    if (mappa.get(t).getArmate() < target && turno < 35) {
                        // Solo pre-sdadata: usa anche armate fuori obiettivo con >1
                        for (String fonte : miei) {
                            if (fonte.equals(t) || isInObiettivo(fonte, obj)) continue;
                            if (mappa.get(fonte).getArmate() <= 1) continue;
                            mappa.get(fonte).setArmate(mappa.get(fonte).getArmate() - 1);
                            mappa.get(t).setArmate(mappa.get(t).getArmate() + 1);
                            if (mappa.get(t).getArmate() >= target) break;
                        }
                    }
                }
                if (mappa.get(t).getArmate() >= target) {
                    log.add("🔒 " + lbl(colore) + " protegge obiettivo " + nom(t) +
                            " → " + mappa.get(t).getArmate() + " armate (ASSOLUTO)");
                }
            }
        }

        // ── FASE CRITICA: difesa prioritaria se il bot è a 9-10 territori ────
        // Scendere sotto 9 territori è molto grave (rinforzi < 3).
        // Se si è a 9-10 territori, prima di tutto si rinforzano i confini
        // più deboli per evitare di perderne altri.
        // Soglia: se armateNemiche > mie × 1.5 su un confine → rinforza subito.
        int nTerritoMiei = miei.size();
        if (nTerritoMiei <= 10) {
            int budgetCritico = (nTerritoMiei == 9) ? rim : rim / 2;

            List<String> confineCritico = confine.stream()
                    .sorted(Comparator.comparingInt(t -> {
                        int nemici = getConfinanti(t).stream()
                                .filter(adj -> {
                                    TerritoryState st = mappa.getOrDefault(adj, new TerritoryState("?",0));
                                    return !colore.equals(st.getColore()) && !"?".equals(st.getColore());
                                })
                                .mapToInt(adj -> mappa.get(adj).getArmate()).sum();
                        return -nemici;
                    }))
                    .collect(Collectors.toList());

            for (String t : confineCritico) {
                if (budgetCritico <= 0) break;
                int attualeT = mappa.get(t).getArmate();

                // Calcola la minaccia pesata: considera il peso del nemico più forte adiacente
                // (continente = +5/+10 territori equivalenti)
                int nemiciAdj = getConfinanti(t).stream()
                        .filter(adj -> {
                            TerritoryState st = mappa.getOrDefault(adj, new TerritoryState("?",0));
                            return !colore.equals(st.getColore()) && !"?".equals(st.getColore());
                        })
                        .mapToInt(adj -> mappa.get(adj).getArmate()).sum();

                // Peso del nemico più forte adiacente (proporzionale alla dominanza)
                int pesoMaxNemico = getConfinanti(t).stream()
                        .filter(adj -> {
                            TerritoryState st = mappa.getOrDefault(adj, new TerritoryState("?",0));
                            return !colore.equals(st.getColore()) && !"?".equals(st.getColore());
                        })
                        .mapToInt(adj -> calcolaPesoAvversario(
                                mappa.get(adj).getColore(), mappa))
                        .max().orElse(0);

                // Margine extra proporzionale alla dominanza del nemico
                int margineExtra = pesoMaxNemico > 15 ? 5 : pesoMaxNemico > 11 ? 3 : 2;

                if (nemiciAdj > attualeT * 1.5) {
                    int minimo = Math.min(nemiciAdj + margineExtra, 25);
                    int aggiunti = Math.min(minimo - attualeT, budgetCritico);
                    if (aggiunti > 0) {
                        mappa.get(t).setArmate(attualeT + aggiunti);
                        rim -= aggiunti;
                        budgetCritico -= aggiunti;
                        log.add("🚨 " + lbl(colore) + " difesa critica " + nom(t) +
                                " (" + attualeT + "→" + (attualeT + aggiunti) +
                                " | peso nemico=" + pesoMaxNemico + ")");
                    }
                }
            }
            if (rim <= 0) { log.add("🛡 " + lbl(colore) + " piazza " + rinforzi + " armate"); return; }
        }

        // ── FASE 0: territori interni → riduce armate liberate per i confini ──
        // Un territorio interno (tutti adiacenti miei) è al sicuro.
        // In sdadata (T35+): minimo 3 (mai 1-2, troppo rischioso).
        // Prima del T35: minimo 1.
        // In obiettivo: mai sotto 3 in qualsiasi turno.
        int minimoInterno = (turno >= 35) ? 3 : 1;
        for (String t : new ArrayList<>(miei)) {
            boolean interno = getConfinanti(t).stream()
                    .allMatch(adj -> colore.equals(
                            mappa.getOrDefault(adj, new TerritoryState("?", 0)).getColore()));
            if (!interno) continue;
            int minimoT = isInObiettivo(t, obj) ? Math.max(3, minimoInterno) : minimoInterno;
            if (mappa.get(t).getArmate() > minimoT) {
                rim += mappa.get(t).getArmate() - minimoT;
                mappa.get(t).setArmate(minimoT);
            }
        }

        // vengono re-immesse nel budget per rinforzare i confini veri.
        for (String t : new ArrayList<>(miei)) {
            boolean interno = getConfinanti(t).stream()
                    .allMatch(adj -> colore.equals(
                            mappa.getOrDefault(adj, new TerritoryState("?", 0)).getColore()));
            if (interno && mappa.get(t).getArmate() > 1) {
                rim += mappa.get(t).getArmate() - 1;
                mappa.get(t).setArmate(1);
            }
        }

        // ── FASE 0b: territorio in obiettivo = ultimo nel suo continente ────
        // Se un territorio in obiettivo è l'unico del giocatore in quel continente,
        // va difeso con priorità assoluta perché:
        // 1. Perderlo = perdere punti obiettivo
        // 2. Il nemico potrebbe avere carte (3 carte = +10 armate extra stimate)
        // Calcola la minaccia nemica massima adiacente e porta il territorio
        // a un livello sufficiente a resistere anche a un attacco potenziato dal tris.
        for (String t : new ArrayList<>(confine)) {
            if (!isInObiettivo(t, obj)) continue;

            // Verifica se è l'ultimo del giocatore in quel continente
            boolean ultimoInContinente = false;
            for (Map.Entry<String, List<String>> ce : RisikoBoardData.CONTINENTI.entrySet()) {
                List<String> tc = ce.getValue();
                if (!tc.contains(t)) continue;
                long mieiInCont = tc.stream()
                        .filter(terr -> colore.equals(
                                mappa.getOrDefault(terr, new TerritoryState("?",0)).getColore()))
                        .count();
                if (mieiInCont == 1) { ultimoInContinente = true; break; }
            }
            if (!ultimoInContinente) continue;

            // Calcola pressione nemica: max armate nemiche adiacenti
            int maxArmateNemiche = getConfinanti(t).stream()
                    .map(adj -> mappa.getOrDefault(adj, new TerritoryState("?", 0)))
                    .filter(st -> !colore.equals(st.getColore()) && !"?".equals(st.getColore()))
                    .mapToInt(TerritoryState::getArmate)
                    .max().orElse(0);

            // Stima se il nemico più forte ha carte (3+ carte = +10 armate potenziali)
            // Usa il colore del nemico più forte adiacente
            String coloreMiniccioso = getConfinanti(t).stream()
                    .filter(adj -> {
                        TerritoryState st = mappa.getOrDefault(adj, new TerritoryState("?",0));
                        return !colore.equals(st.getColore()) && !"?".equals(st.getColore())
                                && st.getArmate() == maxArmateNemiche;
                    })
                    .map(adj -> mappa.get(adj).getColore())
                    .findFirst().orElse(null);

            // Stima carte del nemico dal log delle carte (se tracciato)
            // Conservativamente: se ha già ≥6 armate adiacenti, assume possa avere carte
            int armateNemicheStimate = maxArmateNemiche;
            if (maxArmateNemiche >= 6) {
                armateNemicheStimate += 10; // stima tris nemico
            }

            // Il territorio deve avere almeno 2x le armate nemiche stimate per resistere
            int minimoDifesa = Math.max(6, armateNemicheStimate * 2);
            int attuale = mappa.get(t).getArmate();
            int mancanti = minimoDifesa - attuale;

            if (mancanti > 0 && rim > 0) {
                int aggiunti = Math.min(mancanti, rim);
                mappa.get(t).setArmate(attuale + aggiunti);
                rim -= aggiunti;
                log.add("🔒 " + lbl(colore) + " protegge ultimo in continente " +
                        nom(t) + " (" + attuale + "→" + (attuale + aggiunti) + ")");
            }

            if (rim <= 0) {
                log.add("🛡 " + lbl(colore) + " piazza " + rinforzi + " armate");
                return;
            }
        }

        // ── FASE 0b2: difendi ingressi di continenti già chiusi ─────────────
        // Se il giocatore controlla un intero continente, difende i suoi ingressi
        // (territori del continente adiacenti a nemici) con:
        // min = armate nemiche adiacenti + 5, max = 25
        // Priorità altissima: perdere 1 territorio = perdere il bonus continente.
        for (Map.Entry<String, List<String>> ce : RisikoBoardData.CONTINENTI.entrySet()) {
            List<String> tc = ce.getValue();
            boolean controlloTotale = tc.stream().allMatch(t ->
                    colore.equals(mappa.getOrDefault(t, new TerritoryState("?",0)).getColore()));
            if (!controlloTotale) continue;

            for (String ingresso : tc) {
                boolean haConfineNemico = getConfinanti(ingresso).stream()
                        .anyMatch(adj -> {
                            TerritoryState st = mappa.getOrDefault(adj, new TerritoryState("?",0));
                            return !colore.equals(st.getColore()) && !"?".equals(st.getColore());
                        });
                if (!haConfineNemico) continue;

                int armateNemicheAdj = getConfinanti(ingresso).stream()
                        .filter(adj -> {
                            TerritoryState st = mappa.getOrDefault(adj, new TerritoryState("?",0));
                            return !colore.equals(st.getColore()) && !"?".equals(st.getColore());
                        })
                        .mapToInt(adj -> mappa.get(adj).getArmate()).sum();

                // Cap ingresso: proporzionale al valore del continente
                // Sud America/Oceania: max 12, Africa: max 15, NA/EU/Asia: max 20
                int capIngresso = switch (ce.getKey()) {
                    case "nordamerica", "europa", "asia" -> 20;
                    case "africa" -> 15;
                    default -> 12; // sudamerica, oceania — basso valore
                };
                int minimoIngresso = Math.min(armateNemicheAdj + 5, capIngresso);
                int attuale = mappa.get(ingresso).getArmate();

                if (attuale < minimoIngresso && rim > 0) {
                    int aggiunti = Math.min(minimoIngresso - attuale, rim);
                    mappa.get(ingresso).setArmate(attuale + aggiunti);
                    rim -= aggiunti;
                    log.add("🏰 " + lbl(colore) + " difende ingresso " + nom(ingresso) +
                            " (" + attuale + "→" + (attuale + aggiunti) + " | max 25)");
                }
            }
        }
        if (rim <= 0) { log.add("🛡 " + lbl(colore) + " piazza " + rinforzi + " armate"); return; }

        // ── FASE 0c: territorio in obiettivo sotto pressione 3x ─────────────
        // Se le armate nemiche adiacenti dello stesso colore sommano ≥ 3x
        // le armate del territorio in obiettivo, va rinforzato con priorità alta.
        // Porta il territorio ad almeno la metà della pressione nemica.
        for (String t : new ArrayList<>(confine)) {
            if (!isInObiettivo(t, obj)) continue;
            if (rim <= 0) break;

            int mieArmate = mappa.get(t).getArmate();

            // Raggruppa i nemici per colore e somma le armate per ogni colore
            Map<String, Integer> sommaNemiciPerColore = new HashMap<>();
            for (String adj : getConfinanti(t)) {
                TerritoryState st = mappa.getOrDefault(adj, new TerritoryState("?", 0));
                if (!colore.equals(st.getColore()) && !"?".equals(st.getColore())) {
                    sommaNemiciPerColore.merge(st.getColore(), st.getArmate(), Integer::sum);
                }
            }

            // Prende la pressione massima da un singolo colore nemico
            int pressioneMassima = sommaNemiciPerColore.values().stream()
                    .mapToInt(Integer::intValue).max().orElse(0);

            // Se la pressione ≥ 3x le mie armate → territorio sotto assedio
            if (pressioneMassima >= mieArmate * 3) {
                // Porta il territorio ad almeno la metà della pressione nemica
                int target = pressioneMassima / 2;
                int mancanti = target - mieArmate;
                if (mancanti > 0) {
                    int aggiunti = Math.min(mancanti, rim);
                    mappa.get(t).setArmate(mieArmate + aggiunti);
                    rim -= aggiunti;
                    log.add("🛡 " + lbl(colore) + " rinforza sotto pressione 3x: "
                            + nom(t) + " (" + mieArmate + "→" + (mieArmate + aggiunti)
                            + " vs " + pressioneMassima + " nemici)");
                }
            }
        }
        if (rim <= 0) { log.add("🛡 " + lbl(colore) + " piazza " + rinforzi + " armate"); return; }

        // ── FASE 0d: difendi contro avversario che punta al Nord America ────
        // Se l'inferencer stima che un avversario punta al Nord America
        // (continente nel suo obiettivo più probabile), e noi abbiamo almeno
        // 1 territorio lì → difendilo con priorità alta.
        // La stessa logica vale per Asia e Europa (continenti ad alto bonus).
        {
            List<String> continentiAltoValore = List.of("nordamerica", "asia", "europa");
            for (String cont : continentiAltoValore) {
                List<String> terrCont = RisikoBoardData.CONTINENTI.getOrDefault(cont, List.of());

                // Ho territori in questo continente?
                List<String> mieiInCont = miei.stream()
                        .filter(terrCont::contains).collect(Collectors.toList());
                if (mieiInCont.isEmpty()) continue;

                // Qualche avversario ha questo continente come obiettivo probabile
                // E ha già molti territori lì (è vicino a chiuderlo)?
                boolean minacciaReale = mappa.entrySet().stream()
                        .filter(e -> terrCont.contains(e.getKey())
                                && !colore.equals(e.getValue().getColore()))
                        .collect(Collectors.groupingBy(e -> e.getValue().getColore(), Collectors.counting()))
                        .entrySet().stream()
                        .anyMatch(e -> {
                            // Il nemico ha ≥ metà dei territori del continente
                            boolean haTerritori = e.getValue() >= terrCont.size() / 2;
                            // E l'inferencer stima che il suo obiettivo includa questo continente
                            List<String> contProbabili = inferencer.getContinentiProbabili(e.getKey());
                            // Oppure il suo peso effettivo è molto alto (>16) → minaccia generale
                            int peso = calcolaPesoAvversario(e.getKey(), mappa);
                            return (haTerritori && contProbabili.contains(cont)) || peso > 16;
                        });

                if (!minacciaReale) continue;

                // Difendi il nostro territorio in quel continente con il più alto valore
                // (più adiacenze = punto di confine più importante)
                Optional<String> daRinforzare = mieiInCont.stream()
                        .filter(confine::contains) // deve essere sul confine
                        .max(Comparator.comparingInt(t -> getConfinanti(t).size()));

                if (daRinforzare.isEmpty()) daRinforzare = mieiInCont.stream()
                        .max(Comparator.comparingInt(t -> getConfinanti(t).size()));

                if (daRinforzare.isPresent() && rim > 0) {
                    String t = daRinforzare.get();
                    int attuale = mappa.get(t).getArmate();
                    // Porta il territorio ad almeno 10 armate (soglia anti-conquista)
                    int minimo = 10;
                    if (attuale < minimo) {
                        int aggiunti = Math.min(minimo - attuale, rim);
                        mappa.get(t).setArmate(attuale + aggiunti);
                        rim -= aggiunti;
                        log.add("🔒 " + lbl(colore) + " rinforza " + nom(t) +
                                " (minaccia " + cont + " → " + (attuale + aggiunti) + " armate)");
                    }
                }
            }
        }
        if (rim <= 0) { log.add("🛡 " + lbl(colore) + " piazza " + rinforzi + " armate"); return; }

        // ── FASE 0c: difendi territori che il nemico potrebbe rendere nicchia ──
        // Se tutti gli adiacenti di un nostro territorio sono già del nemico,
        // quel territorio è in pericolo estremo — va rinforzato subito.
        for (String t : new ArrayList<>(confine)) {
            List<String> confinanti = getConfinanti(t);
            if (confinanti.isEmpty()) continue;
            long adjNemici = confinanti.stream()
                    .filter(adj -> {
                        TerritoryState st = mappa.getOrDefault(adj, new TerritoryState("?",0));
                        return !colore.equals(st.getColore()) && !"?".equals(st.getColore());
                    })
                    .count();
            // Se quasi tutti gli adiacenti sono nemici → territorio quasi-nicchiato
            if (adjNemici >= confinanti.size() - 1 && rim > 0) {
                int attuale = mappa.get(t).getArmate();
                // Stima minaccia: massima armata nemica adiacente (+ stima tris se ≥6)
                int maxNem = confinanti.stream()
                        .filter(adj -> {
                            TerritoryState st = mappa.getOrDefault(adj, new TerritoryState("?",0));
                            return !colore.equals(st.getColore()) && !"?".equals(st.getColore());
                        })
                        .mapToInt(adj -> mappa.get(adj).getArmate())
                        .max().orElse(0);
                int minacciaStimata = maxNem >= 6 ? maxNem + 10 : maxNem;
                int minimo = Math.max(6, minacciaStimata * 2);
                int mancanti = minimo - attuale;
                if (mancanti > 0) {
                    int aggiunti = Math.min(mancanti, rim);
                    mappa.get(t).setArmate(attuale + aggiunti);
                    rim -= aggiunti;
                    log.add("🔒 " + lbl(colore) + " difende quasi-nicchia " +
                            nom(t) + " (" + attuale + "→" + (attuale + aggiunti) + ")");
                }
            }
        }
        if (rim <= 0) { log.add("🛡 " + lbl(colore) + " piazza " + rinforzi + " armate"); return; }

        // ── FASE 0e0: territori in obiettivo mai a 1 o 2 armate (salvo nicchia) ──
        // Un territorio in obiettivo con 1 o 2 armate è indifendibile.
        // Eccezione: se è in nicchia (tutti gli adiacenti sono miei) è al sicuro.
        {
            // Nessuna eccezione per la cartina: anche il territorio cartina
            // in obiettivo deve avere almeno 3 armate
            for (String t : new ArrayList<>(miei)) {
                if (!isInObiettivo(t, obj)) continue;
                if (mappa.get(t).getArmate() > 2) continue; // già ok

                // È in nicchia? (tutti gli adiacenti sono miei)
                boolean inNicchia = getConfinanti(t).stream()
                        .allMatch(adj -> colore.equals(
                                mappa.getOrDefault(adj, new TerritoryState("?",0)).getColore()));
                if (inNicchia) continue; // in nicchia è al sicuro anche con 1-2 armate

                // Porta ad almeno 3
                if (rim > 0) {
                    int diff = 3 - mappa.get(t).getArmate();
                    int aggiunti = Math.min(diff, rim);
                    mappa.get(t).setArmate(mappa.get(t).getArmate() + aggiunti);
                    rim -= aggiunti;
                } else {
                    // Prendi da territorio interno con più armate
                    miei.stream()
                            .filter(s -> !s.equals(t) && mappa.get(s).getArmate() > 3)
                            .filter(s -> getConfinanti(s).stream().allMatch(adj2 ->
                                    colore.equals(mappa.getOrDefault(adj2, new TerritoryState("?",0)).getColore())))
                            .max(Comparator.comparingInt(s -> mappa.get(s).getArmate()))
                            .ifPresent(fonte -> {
                                mappa.get(fonte).setArmate(mappa.get(fonte).getArmate() - 1);
                                mappa.get(t).setArmate(mappa.get(t).getArmate() + 1);
                                log.add("🔒 " + lbl(colore) + " protegge obiettivo " + nom(t) + " (mai 1-2 armate)");
                            });
                }
            }
        }
        if (rim <= 0) { log.add("🛡 " + lbl(colore) + " piazza " + rinforzi + " armate"); return; }

        // ── FASE 0e: territori in obiettivo con ≥6 punti MAI a 2 armate ───
        // Un territorio in obiettivo da 6+ adiacenze a 2 armate è inaccettabile
        // a qualsiasi turno — è troppo prezioso per lasciarlo indifeso.
        // Fuori obiettivo: regola dopo turno 5.
        {
            String terrCartinaObjE = territoriCartina.getOrDefault(colore, null);
            for (String t : new ArrayList<>(miei)) {
                if (t.equals(terrCartinaObjE)) continue;
                boolean inObj = isInObiettivo(t, obj);
                int adj = getConfinanti(t).size();
                // In obiettivo con ≥6 punti: regola assoluta (qualsiasi turno)
                // Fuori obiettivo con ≥6 punti: solo dopo turno 5
                if (adj < 6) continue;
                if (!inObj && turno <= 5) continue;
                if (mappa.get(t).getArmate() > 2) continue;
                if (rim > 0) {
                    mappa.get(t).setArmate(3);
                    rim--;
                } else {
                    miei.stream()
                            .filter(s -> !s.equals(t) && mappa.get(s).getArmate() > 3)
                            .filter(s -> getConfinanti(s).stream().allMatch(adj2 ->
                                    colore.equals(mappa.getOrDefault(adj2, new TerritoryState("?",0)).getColore())))
                            .max(Comparator.comparingInt(s -> mappa.get(s).getArmate()))
                            .ifPresent(fonte -> {
                                mappa.get(fonte).setArmate(mappa.get(fonte).getArmate() - 1);
                                mappa.get(t).setArmate(3);
                                log.add("🔒 " + lbl(colore) + " protegge " + nom(t) +
                                        (inObj ? " (obiettivo" : " (") + ", ≥6 punti, mai a 2)");
                            });
                }
            }
        }

        // ── FASE 0e2: dopo il turno 5, max 1 territorio a 2 armate ─────────
        if (turno > 5) {
            String terrCartinaCorrenteE = territoriCartina.getOrDefault(colore, null);
            for (String t : new ArrayList<>(miei)) {
                if (t.equals(terrCartinaCorrenteE)) continue;
                if (getConfinanti(t).size() < 6) continue; // solo ≥6 adiacenze
                if (mappa.get(t).getArmate() > 2) continue; // già ok
                if (rim > 0) {
                    mappa.get(t).setArmate(3);
                    rim--;
                } else {
                    // Prendi 1 armata da un territorio interno con più di 3
                    miei.stream()
                            .filter(s -> !s.equals(t) && mappa.get(s).getArmate() > 3)
                            .filter(s -> getConfinanti(s).stream().allMatch(adj ->
                                    colore.equals(mappa.getOrDefault(adj, new TerritoryState("?",0)).getColore())))
                            .max(Comparator.comparingInt(s -> mappa.get(s).getArmate()))
                            .ifPresent(fonte -> {
                                mappa.get(fonte).setArmate(mappa.get(fonte).getArmate() - 1);
                                mappa.get(t).setArmate(3);
                                log.add("🔒 " + lbl(colore) + " protegge " + nom(t) +
                                        " (≥6 punti, mai a 2)");
                            });
                }
            }
        }

        // ── FASE 0e2: dopo il turno 5, max 1 territorio a 2 armate ─────────
        if (turno > 5) {
            String terrCartinaCorrente = territoriCartina.getOrDefault(colore, null);
            List<String> territoriBassoDifesa = miei.stream()
                    .filter(t -> mappa.get(t).getArmate() == 2)
                    .filter(t -> !t.equals(terrCartinaCorrente)) // escludi territorio cartina
                    .collect(Collectors.toList());

            // Se ce n'è più di 1 a 2 armate, porta tutti tranne 1 a 3 armate
            if (territoriBassoDifesa.size() > 1) {
                // Lascia il primo (quello con meno minaccia), porta gli altri a 3
                for (int ii = 1; ii < territoriBassoDifesa.size(); ii++) {
                    String t = territoriBassoDifesa.get(ii);
                    if (rim > 0) {
                        mappa.get(t).setArmate(3);
                        rim--;
                    } else {
                        // Prendi da territori interni con più armate
                        Optional<String> interno = miei.stream()
                                .filter(s -> !s.equals(t))
                                .filter(s -> getConfinanti(s).stream().allMatch(adj ->
                                        colore.equals(mappa.getOrDefault(adj, new TerritoryState("?",0)).getColore())))
                                .filter(s -> mappa.get(s).getArmate() > 2)
                                .max(Comparator.comparingInt(s -> mappa.get(s).getArmate()));
                        if (interno.isPresent()) {
                            mappa.get(interno.get()).setArmate(mappa.get(interno.get()).getArmate() - 1);
                            mappa.get(t).setArmate(3);
                        }
                    }
                }
            }
        }

        // ── FASE 1 (difesa): porta ogni territorio ai minimi per tipo ──────
        // Nei primi 7 turni: minimi ridotti (flessibilità iniziale)
        // Territorio cartina (lasciato a 2): non toccare
        // Fuori obiettivo: min 3-4 (T1-7), min 4-8 (T8+) — max 8
        // In obiettivo: min 10-11, proporzionale al valore (adiacenze)
        // Il budget difesa usa al massimo i 2/3 dei rinforzi disponibili.

        String terrCartina = territoriCartina.getOrDefault(colore, null); // territorio a 2 per cartina
        int budgetDifesa = Math.max(0, rim * 2 / 3);
        boolean primiTurni = turno <= 7;

        // Ordina: prima in obiettivo (più urgenti), poi fuori obiettivo
        List<String> tuttiDaRinforzare = new ArrayList<>(confine);
        tuttiDaRinforzare.addAll(miei.stream().filter(t -> !tuttiDaRinforzare.contains(t)).collect(Collectors.toList()));

        for (String t : tuttiDaRinforzare) {
            if (budgetDifesa <= 0) break;
            if (t.equals(terrCartina)) continue; // non toccare il territorio cartina

            int attuale = mappa.get(t).getArmate();

            // Peso del nemico più forte adiacente → difesa proporzionale alla sua dominanza
            int pesoNemicoAdj = getConfinanti(t).stream()
                    .filter(adj -> {
                        TerritoryState st = mappa.getOrDefault(adj, new TerritoryState("?",0));
                        return !colore.equals(st.getColore()) && !"?".equals(st.getColore());
                    })
                    .mapToInt(adj -> calcolaPesoAvversario(mappa.get(adj).getColore(), mappa))
                    .max().orElse(0);
            // bonus: +2 se peso>11, +3 se >15, +4 se >20
            int bonusPeso = pesoNemicoAdj > 20 ? 4 : pesoNemicoAdj > 15 ? 3 : pesoNemicoAdj > 11 ? 2 : 0;

            int minimo;
            if (isInObiettivo(t, obj)) {
                int adiacenze = getConfinanti(t).size();
                int minimoBase = switch (adiacenze) {
                    case 1, 2, 3 -> 8;
                    case 4       -> 10;
                    case 5       -> 12;
                    case 6       -> 14;
                    default      -> 16;
                };
                minimo = primiTurni ? Math.max(4, minimoBase / 2) : minimoBase + bonusPeso;
            } else {
                minimo = primiTurni ? 3 : 4 + bonusPeso;
            }

            if (attuale < minimo) {
                int mancanti = minimo - attuale;
                int aggiunti = Math.min(mancanti, budgetDifesa);
                if (aggiunti > 0) {
                    mappa.get(t).setArmate(attuale + aggiunti);
                    rim          -= aggiunti;
                    budgetDifesa -= aggiunti;
                    log.add("🔒 " + lbl(colore) + " difende " + nom(t) +
                            " (" + attuale + "→" + (attuale + aggiunti) + ")");
                }
            }

            // Cap fuori obiettivo: max 8 armate
            if (!isInObiettivo(t, obj) && !t.equals(terrCartina)) {
                int att = mappa.get(t).getArmate();
                if (att > 8) {
                    int eccesso = att - 8;
                    mappa.get(t).setArmate(8);
                    rim += eccesso; // restituisce al budget
                }
            }
        }
        if (rim <= 0) { log.add("🛡 " + lbl(colore) + " piazza " + rinforzi + " armate"); return; }

        // ── FASE 2 (offensiva): accumula TUTTE le armate restanti ────────────
        // Dal turno 35 (sdadata): SOLO territori in obiettivo contano.
        // Chi è sotto punteggio deve recuperare — tutto il resto è irrilevante.
        // Il numero di turno viene passato via obiettivoId come workaround
        // (vedi nota): usiamo la variabile locale se disponibile nel contesto.

        String continenteDaDifendere = trovaContinenteDaDifendere(colore, mappa);
        List<String> ordine = new ArrayList<>();

        // 1. In obiettivo + strategico (massima priorità sempre)
        confine.stream()
                .filter(t -> isInObiettivo(t, obj) && TERRITORI_STRATEGICI.contains(t))
                .forEach(ordine::add);

        // 2. In obiettivo — dal turno 35 aggiunto anche dall'interno (non solo confine)
        confine.stream().filter(t -> isInObiettivo(t, obj)).forEach(ordine::add);
        // Aggiungi anche i territori interni in obiettivo nella fase sdadata
        // (es. se Siberia è in obiettivo ma è interna, va rinforzata comunque)
        miei.stream()
                .filter(t -> isInObiettivo(t, obj))
                .filter(t -> !ordine.contains(t))
                .sorted(Comparator.comparingInt(t -> mappa.get(t).getArmate()))
                .forEach(ordine::add);

        // 3. Blocca continente avversario (dopo obiettivo, non prima)
        if (continenteDaDifendere != null) {
            List<String> tc = RisikoBoardData.CONTINENTI.get(continenteDaDifendere);
            confine.stream().filter(t -> tc != null && tc.contains(t)).forEach(ordine::add);
        }

        // 4. Confine del continente da completare
        for (String cont : ORDINE_CONTINENTI) {
            if (!isInObiettivoContinente(cont, obj)) continue;
            List<String> tc = RisikoBoardData.CONTINENTI.getOrDefault(cont, List.of());
            confine.stream().filter(tc::contains).forEach(ordine::add);
        }

        // Tutto il confine
        ordine.addAll(confine);
        List<String> ord = ordine.stream().distinct().collect(Collectors.toList());
        if (ord.isEmpty()) ord = miei;

        // Dal turno 35: riordina mettendo PRIMA i territori in obiettivo
        // con meno armate — massimizza il punteggio per la sdadata.
        if (turno >= 35) {
            List<String> inObiettivo = ord.stream()
                    .filter(t -> isInObiettivo(t, obj))
                    .sorted(Comparator.comparingInt(t -> mappa.get(t).getArmate()))
                    .collect(Collectors.toList());
            List<String> fuoriObiettivo = ord.stream()
                    .filter(t -> !isInObiettivo(t, obj))
                    .collect(Collectors.toList());
            ord = new ArrayList<>(inObiettivo);
            ord.addAll(fuoriObiettivo);
        }

        // ── CAP 20 ARMATE: oltre 20 solo se pressione nemica ≥ 2x ────────────
        // Se il territorio principale ha già ≥ 20 armate, aggiunge rinforzi
        // solo se la somma delle armate nemiche adiacenti ≥ 2x le sue armate.
        // In quel caso aggiunge finché non raggiunge di nuovo la soglia sicura.
        // Altrimenti scende al territorio successivo in lista.
        // Cap standard: 35 armate per territorio.
        // Eccezioni: si può superare solo se:
        // A) Non c'è altro posto dove mettere i rinforzi (tutti gli altri al cap)
        // B) Si vuole attaccare un territorio con >20 armate (serve forza superiore)
        final int CAP_ARMATE_TERRITORIO = 35;
        final int SOGLIA_DIFESA_STANDARD = 20;

        // Salta il principale se ha ≥ 20 armate e non è sotto pressione
        if (!ord.isEmpty()) {
            String cand = ord.get(0);
            int armCand = mappa.get(cand).getArmate();
            if (armCand >= SOGLIA_DIFESA_STANDARD) {
                int pressioneNemica = getConfinanti(cand).stream()
                        .filter(adj -> {
                            TerritoryState st = mappa.getOrDefault(adj, new TerritoryState("?",0));
                            return !colore.equals(st.getColore()) && !"?".equals(st.getColore());
                        })
                        .mapToInt(adj -> mappa.get(adj).getArmate())
                        .sum();
                if (pressioneNemica < armCand * 2) {
                    // Non è sotto pressione: scendi al prossimo in lista
                    ord = new ArrayList<>(ord.size() > 1 ? ord.subList(1, ord.size()) : ord);
                }
                // Se è sotto pressione (pressioneNemica >= 2x): continua ad aggiungere
                // finché non raggiunge pressioneNemica / 2 (la soglia sicura)
            }
        }

        // ── PRIMA di concentrare: distribuisci a tutti gli obiettivi ───────
        // Porta tutti gli obiettivi al minimo, rispettando il cap per adiacenze.
        for (String t : ord) {
            if (rim <= 0) break;
            if (!isInObiettivo(t, obj)) continue;
            int attualeT = mappa.get(t).getArmate();
            int adjT = getConfinanti(t).size();
            // Cap per adiacenze: mai superare questo valore
            int capT = adjT <= 3 ? 12 : adjT <= 5 ? 20 : 25;
            int minimoObjT = turno >= 35 ? (adjT >= 6 ? 14 : adjT >= 4 ? 10 : 8) : 3;
            minimoObjT = Math.min(minimoObjT, capT); // minimo non può superare il cap
            boolean inNicchiaT = getConfinanti(t).stream().allMatch(adj ->
                    colore.equals(mappa.getOrDefault(adj, new TerritoryState("?",0)).getColore()));
            // Nicchia con ≥5 adj in obiettivo: mantieni almeno 3 comunque
            if (inNicchiaT && adjT < 5) continue;
            // In nicchia ma ≥5 adj: mantieni minimo proporzionale al valore
            if (inNicchiaT && adjT >= 6) minimoObjT = Math.max(10, minimoObjT);
            else if (inNicchiaT && adjT >= 5) minimoObjT = Math.max(8, minimoObjT);
            if (attualeT < minimoObjT) {
                int aggiunti = Math.min(minimoObjT - attualeT, rim);
                mappa.get(t).setArmate(attualeT + aggiunti);
                rim -= aggiunti;
            }
        }
        if (rim <= 0) { log.add("🛡 " + lbl(colore) + " piazza " + rinforzi + " armate"); return; }

        String principale = ord.isEmpty() ? null : ord.get(0);
        if (principale == null) {
            log.add("🛡 " + lbl(colore) + " piazza " + rinforzi + " armate");
            return;
        }
        int attualePrincipale = mappa.get(principale).getArmate();

        // Cap per territorio basato sul suo valore (adiacenze = punti):
        // ≤3 adiacenze → max 12 (basso valore: Venezuela, Argentina...)
        // 4-5 adiacenze → max 20 (medio valore)
        // 6+ adiacenze → cap standard 35 (alto valore strategico)
        int adjPrincipale = getConfinanti(principale).size();
        // Cap ASSOLUTO per valore territorio — mai superare indipendentemente dall'attacco
        // 3 adj (Indonesia, Argentina): max 12
        // 4-5 adj (India, Brasile): max 20
        // 6+ adj (Cina, Africa del Nord): max 25
        int capPerValore = adjPrincipale <= 3 ? 12 : adjPrincipale <= 5 ? 20 : 25;

        // Eccezione: se il territorio adiacente ha >20 carri → accumulo illimitato
        boolean vuoleAttaccareFortemente = getConfinanti(principale).stream()
                .anyMatch(adj -> {
                    TerritoryState st = mappa.getOrDefault(adj, new TerritoryState("?",0));
                    return !colore.equals(st.getColore()) && st.getArmate() > 20;
                });

        // Cap effettivo: usa sempre il cap per valore come massimo assoluto
        // vuoleAttaccareFortemente alza il cap MA solo fino al massimo del CAP_ARMATE_TERRITORIO
        int capEffettivo;
        if (vuoleAttaccareFortemente) {
            int armateTarget = getConfinanti(principale).stream()
                    .filter(adj -> {
                        TerritoryState st = mappa.getOrDefault(adj, new TerritoryState("?",0));
                        return !colore.equals(st.getColore()) && st.getArmate() > 20;
                    })
                    .mapToInt(adj -> mappa.get(adj).getArmate())
                    .max().orElse(20);
            // Max = min(bersaglio + 10, 35) — mai illimitato
            capEffettivo = Math.min(armateTarget + 10, CAP_ARMATE_TERRITORIO);
        } else {
            capEffettivo = capPerValore; // cap stretto per territori a basso valore
        }

        int spazioDisponibile = Math.max(0, capEffettivo - attualePrincipale);
        int daAggiungere = Math.min(rim, spazioDisponibile);
        if (daAggiungere > 0) {
            mappa.get(principale).setArmate(attualePrincipale + daAggiungere);
            rim -= daAggiungere;
        }
        // Se il principale è già a 25, distribuisce sugli altri territori in ordine
        int idxExtra = 1;
        while (rim > 0 && idxExtra < ord.size()) {
            String t = ord.get(idxExtra % ord.size());
            int attualeT = mappa.get(t).getArmate();
            if (attualeT < CAP_ARMATE_TERRITORIO) {
                mappa.get(t).setArmate(attualeT + 1);
                rim--;
            }
            idxExtra++;
            if (idxExtra >= ord.size()) break; // evita loop infinito
        }
        log.add("🛡 " + lbl(colore) + " piazza " + rinforzi + " armate su " + nom(principale));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ATTACCHI
    // ═══════════════════════════════════════════════════════════════════════════

    private void eseguiAttacchi(String colore, int obiettivoId, int turno,
                                Map<String, TerritoryState> mappa, List<String> log) {
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);

        int punteggioMio    = calcolaPunteggioAttuale(colore, obiettivoId, mappa);
        int punteggioMaxAvv = getMassimoPunteggioAvversari(colore, mappa);
        int distaccoDalLeader = punteggioMaxAvv - punteggioMio; // negativo se sono in testa

        List<String> mieiPerConteggio = getTerritoriGiocatore(colore, mappa);
        boolean sottoSogliaTerritoriMinimi = mieiPerConteggio.size() < 9;
        int nTerr = mieiPerConteggio.size();

        int maxAttacchi;
        double moltiplicatoreSoglia;
        int maxConquiste = Integer.MAX_VALUE;

        // ══════════════════════════════════════════════════════════════════════
        // FASE SDADATA (turno ≥ 35): logica speciale
        // ══════════════════════════════════════════════════════════════════════
        if (turno >= 35) {
            // Stima quanti punti guadagnerei conquistando ogni territorio in obiettivo
            // raggiungibile. Ordina per punti decrescenti.
            List<String> miei = getTerritoriGiocatore(colore, mappa);
            List<int[]> bersagliConPunti = new ArrayList<>(); // [puntiGuadagnati, indice]

            for (String mio : miei) {
                for (String nemico : getConfinanti(mio)) {
                    TerritoryState st = mappa.get(nemico);
                    if (st == null || colore.equals(st.getColore())) continue;
                    if (!isInObiettivo(nemico, obj)) continue;
                    int punti = getConfinanti(nemico).size(); // valore del territorio
                    bersagliConPunti.add(new int[]{punti});
                }
            }
            bersagliConPunti.sort((a, b) -> b[0] - a[0]);

            // Caso A: posso andare sopra con ≤ 2 conquiste in obiettivo?
            int punteggioStimato = punteggioMio;
            int conquiste2 = 0;
            for (int[] bp : bersagliConPunti) {
                if (conquiste2 >= 2) break;
                punteggioStimato += bp[0];
                conquiste2++;
            }
            boolean possoPrendereVantaggioIn2 = punteggioStimato > punteggioMaxAvv;

            if (possoPrendereVantaggioIn2) {
                // Attacca max 2, soglia bassa per massimizzare chance
                maxAttacchi = 2;
                moltiplicatoreSoglia = 1.5;
                maxConquiste = 2;
            } else {
                // Caso B: non basta con 2 conquiste
                // Calcolo quanti territori servono per andare sopra
                int punteggioCon3 = punteggioMio;
                int c3 = 0;
                for (int[] bp : bersagliConPunti) {
                    if (c3 >= 3) break;
                    punteggioCon3 += bp[0];
                    c3++;
                }
                boolean possoCon3 = punteggioCon3 > punteggioMaxAvv;

                if (possoCon3 && distaccoDalLeader <= 15) {
                    // Con 3 conquiste vado sopra E sono vicino (≤15 punti):
                    // attacca 3 ma solo se posso difenderli (la soglia resta normale)
                    maxAttacchi = 3;
                    moltiplicatoreSoglia = 2.0;
                    maxConquiste = 3;
                } else if (distaccoDalLeader <= 15) {
                    // Sono vicino ma non riesco a sorpassare: attacca 1 (la migliore opportunità)
                    maxAttacchi = 1;
                    moltiplicatoreSoglia = 2.0;
                    maxConquiste = 1;
                } else {
                    // Troppo distante: non rischiare, difendi
                    maxAttacchi = 0;
                    moltiplicatoreSoglia = 99.0;
                    maxConquiste = 0;
                }
            }

            // ══════════════════════════════════════════════════════════════════════
            // FASE NORMALE (turni 1-34): logica difensiva standard
            // ══════════════════════════════════════════════════════════════════════
        } else {
            boolean sonoInVantaggio = punteggioMio > punteggioMaxAvv;
            maxConquiste = sonoInVantaggio ? 2 : Integer.MAX_VALUE;

            boolean endgame    = turno >= 30;
            boolean preEndgame = turno >= 20 && turno < 30;
            boolean sonoInTesta  = distaccoDalLeader < 0;
            boolean sonoIndietro = distaccoDalLeader > 10;

            if (sottoSogliaTerritoriMinimi) {
                maxAttacchi = 10; moltiplicatoreSoglia = 1.5;
            } else if (nTerr >= 12) {
                maxAttacchi = 1;  moltiplicatoreSoglia = 3.5;
            } else if (nTerr == 10) {
                // SOGLIA CRITICA (10t): perdere 1 = scendere a 9 (rinforzi ai minimi)
                // Attacca solo se opportunità eccezionale, mai a rischio
                maxAttacchi = 1;  moltiplicatoreSoglia = 4.0;
            } else if (nTerr == 11) {
                // Zona confortevole: attacca con cautela
                maxAttacchi = 2;  moltiplicatoreSoglia = 3.0;
            } else if (endgame && sonoIndietro) {
                maxAttacchi = 3;  moltiplicatoreSoglia = 2.0;
            } else if (endgame && sonoInTesta) {
                maxAttacchi = 1;  moltiplicatoreSoglia = 3.5;
            } else if (endgame) {
                maxAttacchi = 2;  moltiplicatoreSoglia = 2.5;
            } else if (turno <= 8) {
                maxAttacchi = 3;  moltiplicatoreSoglia = 2.5;
            } else if (turno <= 20) {
                maxAttacchi = 2;  moltiplicatoreSoglia = 3.0;
            } else {
                maxAttacchi = 1;
                moltiplicatoreSoglia = 3.5;
            }
        } // fine if turno >= 35 / else

        // META-PRIORITÀ: urgenza sdadata abbassa la soglia di attacco
        // Va applicata DOPO l'inizializzazione di moltiplicatoreSoglia e maxAttacchi
        if (turno < 35) {
            int urgenzaSdadata = calcolaUrgenzaSdadata(colore, obiettivoId, mappa, turno);
            if (urgenzaSdadata > 200) {
                moltiplicatoreSoglia = Math.max(1.5, moltiplicatoreSoglia - 0.5);
                maxAttacchi = Math.min(maxAttacchi + 1, 5);
            }
        }

        // ── ATTACCO CARTA: conquista facile per prendere la carta del turno ──
        // Se non ho ancora conquistato nessun territorio questo turno (niente carta),
        // cerco un bersaglio probabilisticamente facile (ratio ≥ 5x, difensore ≤ 4)
        // e lo conquisto solo per assicurarmi la carta.
        // La carta vale sempre: anche senza obiettivi vicini, 1 conquista facile = carta.
        // NON attacco se ho già conquistato (carta già garantita).
        if (territorConquistatiNelTurno.getOrDefault(colore, 0) == 0) {
            List<String> mieiOra = getTerritoriGiocatore(colore, mappa);
            AttaccoCandidate targhetFacile = null;
            double bestRatio = 0;

            for (String mio : mieiOra) {
                int mieArmate = mappa.get(mio).getArmate();
                for (String nemico : getConfinanti(mio)) {
                    TerritoryState st = mappa.get(nemico);
                    if (st == null || colore.equals(st.getColore())) continue;
                    int armNem = st.getArmate();
                    if (armNem == 0 || armNem > 4) continue; // difensore max 4 armate
                    double ratio = (double) mieArmate / armNem;
                    if (ratio >= 5.0 && ratio > bestRatio) { // conquista quasi certa (>95%)
                        bestRatio = ratio;
                        targhetFacile = new AttaccoCandidate(mio, nemico, 9999);
                    }
                }
            }

            if (targhetFacile != null) {
                log.add("🃏 " + lbl(colore) + " attacca " + nom(targhetFacile.verso())
                        + " per prendere carta (" + String.format("%.1f", bestRatio) + "x)");
                eseguiAttacco(targhetFacile, mappa, log, colore);
            }
        }

        // Territori già tentati e falliti in questo turno — non riprovarli
        Set<String> giaTentati = new HashSet<>();
        int fallitiConsecutivi = 0;

        for (int tentativo = 0; tentativo < maxAttacchi; tentativo++) {
            List<String> miei = getTerritoriGiocatore(colore, mappa);

            if (miei.size() <= SOGLIA_MIN_TERRITORI) break;
            if (territorConquistatiNelTurno.getOrDefault(colore, 0) >= maxConquiste) break;
            // Dopo 3 fallimenti consecutivi senza conquiste, smette
            if (fallitiConsecutivi >= 3) break;

            AttaccoCandidate best = trovaMigliorAttacco(colore, miei, mappa, obj, giaTentati, moltiplicatoreSoglia, turno);
            if (best == null) break;

            // ── VETO: non attaccare se lascia esposto un territorio in obiettivo
            // che è l'ultimo nel suo continente e poco difeso vs nemici adiacenti
            // (considera anche +10 armate se il nemico ha ≥3 carte stimate)
            if (vetaAttaccoPerEsposizione(colore, best, obj, mappa)) {
                giaTentati.add(best.verso());
                fallitiConsecutivi++;
                continue;
            }

            boolean vinto = eseguiAttacco(best, mappa, log, colore);
            if (vinto) {
                fallitiConsecutivi = 0;
                giaTentati.clear(); // dopo una conquista, tutti i bersagli sono di nuovo validi
            } else {
                giaTentati.add(best.verso()); // non riprovare questo bersaglio
                fallitiConsecutivi++;
            }
        }
    }

    private record AttaccoCandidate(String da, String verso, int priorita) {}

    private AttaccoCandidate trovaMigliorAttacco(String colore, List<String> miei,
                                                 Map<String, TerritoryState> mappa,
                                                 ObiettivoTarget obj) {
        return trovaMigliorAttacco(colore, miei, mappa, obj, Set.of());
    }

    private AttaccoCandidate trovaMigliorAttacco(String colore, List<String> miei,
                                                 Map<String, TerritoryState> mappa,
                                                 ObiettivoTarget obj,
                                                 Set<String> escludi) {
        return trovaMigliorAttacco(colore, miei, mappa, obj, escludi, 2.0, 1);
    }

    private AttaccoCandidate trovaMigliorAttacco(String colore, List<String> miei,
                                                 Map<String, TerritoryState> mappa,
                                                 ObiettivoTarget obj,
                                                 Set<String> escludi,
                                                 double moltiplicatoreSoglia,
                                                 int turno) {
        AttaccoCandidate best = null;

        for (String mio : miei) {
            int mieArmate = mappa.get(mio).getArmate();
            if (mieArmate < 2) continue;

            for (String nemico : getConfinanti(mio)) {
                if (escludi.contains(nemico)) continue;

                TerritoryState st = mappa.get(nemico);
                if (st == null || colore.equals(st.getColore())) continue;

                int armateNemico = st.getArmate();

                // Soglia difensività: cresce con i turni (2x → 2.5x → 3x)
                int minimoPerAttaccare = (int) Math.ceil(armateNemico * moltiplicatoreSoglia);

                if (mieArmate < minimoPerAttaccare) continue;

                int p = calcolaPrioritaAttacco(nemico, colore, st.getColore(), obj, mappa, turno);
                if (best == null || p > best.priorita())
                    best = new AttaccoCandidate(mio, nemico, p);
            }
        }
        return best;
    }

    private int calcolaPrioritaAttacco(String territorio, String colore,
                                       String coloreNemico, ObiettivoTarget obj,
                                       Map<String, TerritoryState> mappa,
                                       int turno) {
        int p = 0;
        int armateNemico = mappa.get(territorio).getArmate();

        // ── PRIORITÀ ASSOLUTA: territorio in obiettivo ───────────────────────
        // Il bonus cresce con i turni. Dal turno 35 (sdadata) diventa critico:
        // ogni turno aggiunge urgenza perché chi è sotto di punteggio perde.
        // Turni 1-10:  base 400
        // Turni 11-20: base 550
        // Turni 21-34: base 700
        // Turni 35+:   base 700 + (turno-34)*150 → cresce ogni turno di sdadata
        int bonusObiettivo;
        if (turno <= 10)      bonusObiettivo = 400;
        else if (turno <= 20) bonusObiettivo = 550;
        else if (turno <= 34) bonusObiettivo = 700;
        else                  bonusObiettivo = 700 + (turno - 34) * 150;

        // In fase sdadata, aggiunge urgenza se sotto di punteggio reale
        // Il bonus scala ogni turno: T35=+100, T36=+200, T37=+300 ecc.
        if (turno >= 35 && isInObiettivo(territorio, obj)) {
            bonusObiettivo += (turno - 34) * 100;
        }

        if (isInObiettivo(territorio, obj)) {
            p += bonusObiettivo;
            // Valore proporzionale alle adiacenze (= punti del territorio)
            int adjCount = getConfinanti(territorio).size();
            p += adjCount * 25;
            // Meta-urgenza sdadata: se la situazione è critica, +urgenza su obiettivi
            int obIdPerUrgenza = RisikoBoardData.OBIETTIVI.entrySet().stream()
                    .filter(e -> e.getValue() == obj).map(Map.Entry::getKey).findFirst().orElse(0);
            p += calcolaUrgenzaSdadata(colore, obIdPerUrgenza, mappa, turno) / 2;
            if (TERRITORI_STRATEGICI.contains(territorio)) p += 80;

            // ── BONUS ESCLUSIVITÀ: territorio in obiettivo che gli altri non hanno ──
            // Se nessun avversario ha questo territorio nel suo obiettivo stimato,
            // è "esclusivo" → insistere nell'attaccarlo perché nessuno lo difenderà
            // per ragioni proprie. Bonus proporzionale all'esclusività.
            boolean altriLoVogliono = mappa.values().stream()
                    .map(TerritoryState::getColore)
                    .filter(c -> !c.equals(colore) && !"?".equals(c))
                    .distinct()
                    .anyMatch(avversario -> {
                        int obAvv = inferencer.getObiettivoPiuProbabile(avversario);
                        RisikoBoardData.ObiettivoTarget objAvv = RisikoBoardData.OBIETTIVI.get(obAvv);
                        return objAvv != null && isInObiettivo(territorio, objAvv);
                    });
            if (!altriLoVogliono) {
                // Territorio esclusivo: nessun altro lo vuole → priorità alta
                // Il bot deve insistere su questi anche se il successo è simile ad altri
                p += 200; // bonus esclusività
            }
            for (int i = 0; i < ORDINE_CONTINENTI.size(); i++) {
                String cont = ORDINE_CONTINENTI.get(i);
                List<String> terrCont = RisikoBoardData.CONTINENTI.getOrDefault(cont, List.of());
                if (terrCont.contains(territorio) && isInObiettivoContinente(cont, obj)) {
                    p += (ORDINE_CONTINENTI.size() - i) * 20;
                    break;
                }
            }
        }

        // ── PRIORITÀ 2: blocca continente avversario quasi completato ────────
        // Scende ogni turno: nei turni finali non vale la pena distrarsi dal proprio obiettivo.
        // Turni 1-10:  100
        // Turni 11-20:  60
        // Turni 21+:    20 (quasi irrilevante — focus solo sull'obiettivo)
        int bonusContinente;
        if (turno <= 10)      bonusContinente = 100;
        else if (turno <= 20) bonusContinente = 60;
        else                  bonusContinente = 20;

        if (staBlocandoContinente(territorio, coloreNemico, mappa)) p += bonusContinente;

        // ── PRIORITÀ 3: territorio con 1-3 armate → carta facile ────────────
        // Bersaglio prioritario: pesca carta + toglie territorio debole al nemico
        if      (armateNemico == 1) p += 180;
        else if (armateNemico == 2) p += 140;
        else if (armateNemico == 3) p += 80;

        // ── PRIORITÀ EMERGENZA: recupera territori se sotto soglia minima ────
        long mieiTotali = mappa.values().stream()
                .filter(st -> colore.equals(st.getColore())).count();
        if (mieiTotali < 9) p += 600; // EMERGENZA ASSOLUTA: priorità sopra tutto

        // ── ENDGAME (turno ≥ 30): attacca i territori in obiettivo del leader ─
        // I bot stimano gli obiettivi avversari e attaccano i territori del
        // giocatore in testa per abbassarne il punteggio stimato.
        if (turno >= 30) {
            int pMio  = calcolaPunteggioAggregato(colore, mappa);
            int pNem  = calcolaPunteggioAggregato(coloreNemico, mappa);
            if (pNem > pMio) {
                // Il nemico è in testa: vale la pena attaccargli territori ad alto valore
                // Stima se quel territorio vale punti per il nemico (molte adiacenze = valore)
                int valorePerNemico = getConfinanti(territorio).size();
                p += valorePerNemico * (turno - 29) * 5; // scala con l'urgenza
            }
        }

        // ── PRIORITÀ 4: territorio strategico (fuori obiettivo) ─────────────
        if (!isInObiettivo(territorio, obj) && TERRITORI_STRATEGICI.contains(territorio)) p += 40;

        // ── PRIORITÀ 0 ASSOLUTA (T1-33): chiudi continente mancante di 1 ───
        // Priorità massima di tutto il gioco fino al turno 33:
        // Se manca 1 solo territorio per chiudere un continente,
        // l'attaccante ha almeno il doppio delle armate del difensore,
        // E il totale delle armate attaccanti è ≥ 12 → bonus altissimo.
        if (turno <= 33) {
            for (Map.Entry<String, List<String>> ce : RisikoBoardData.CONTINENTI.entrySet()) {
                List<String> tc = ce.getValue();
                if (!tc.contains(territorio)) continue;

                // Quanti ne ho già in questo continente?
                long mieiInCont = tc.stream()
                        .filter(t -> colore.equals(
                                mappa.getOrDefault(t, new TerritoryState("?",0)).getColore()))
                        .count();

                // Manca solo questo territorio per completare il continente
                if (mieiInCont == tc.size() - 1) {
                    // Trova il territorio attaccante con più armate adiacente
                    int maxMieArmate = getConfinanti(territorio).stream()
                            .filter(adj -> colore.equals(
                                    mappa.getOrDefault(adj, new TerritoryState("?",0)).getColore()))
                            .mapToInt(adj -> mappa.get(adj).getArmate())
                            .max().orElse(0);

                    // Condizioni: almeno 2x il difensore E almeno 12 armate totali attaccanti
                    if (maxMieArmate >= armateNemico * 2 && maxMieArmate >= 12) {
                        // Bonus proporzionale al valore del continente
                        int valoreCont = switch (ce.getKey()) {
                            case "asia"        -> 7;
                            case "nordamerica" -> 5;
                            case "europa"      -> 5;
                            case "africa"      -> 3;
                            case "sudamerica"  -> 2;
                            case "oceania"     -> 2;
                            default            -> 1;
                        };
                        p += 800 + valoreCont * 50; // Asia=1150, NA/EU=1050, Africa=950, SA/OC=900
                    }
                    break;
                }
            }
        }

        // ── PRIORITÀ 5b: completa continente mancante di 1 territorio ─────
        // Se il bot possiede tutti i territori di un continente tranne 1,
        // e quel territorio è facilmente conquistabile (3x), attacca con priorità alta.
        // La priorità è ancora più alta se il continente vale molti rinforzi.
        for (Map.Entry<String, List<String>> ce : RisikoBoardData.CONTINENTI.entrySet()) {
            List<String> tc = ce.getValue();
            if (!tc.contains(territorio)) continue; // questo territorio non è nel continente
            String cont = ce.getKey();

            // Quanti territori del continente possiedo già?
            long mieiInCont = tc.stream()
                    .filter(terr -> colore.equals(
                            mappa.getOrDefault(terr, new TerritoryState("?",0)).getColore()))
                    .count();

            // Manca solo questo territorio per completare il continente
            if (mieiInCont == tc.size() - 1) {
                // Bonus base: proporzionale al valore del continente (rinforzi)
                int bonusCompletaCont = switch (cont) {
                    case "asia"        -> 7;  // +7 rinforzi → priorità massima
                    case "nordamerica" -> 5;
                    case "europa"      -> 5;
                    case "africa"      -> 3;
                    case "sudamerica"  -> 2;
                    case "oceania"     -> 2;
                    default            -> 1;
                } * 60; // scala: Asia = 420, Nord America/Europa = 300, ecc.

                // "Facile" = difensore ≤ 3 armate → +50%
                if (armateNemico <= 3) {
                    bonusCompletaCont = (int)(bonusCompletaCont * 1.5);
                }

                p += bonusCompletaCont;
                break;
            }
        }

        // ── PRIORITÀ 5c: territorio che creerebbe nicchia al nemico ─────────
        // Una nicchia = territorio con tutti gli adiacenti dello stesso colore
        // → al sicuro dagli attacchi. Va conquistato/difeso prima che accada.
        // Caso A: tutti gli adiacenti (tranne il territorio stesso) sono GIÀ del nemico
        //         → nicchia immediata se conquistato → urgenza massima (+350)
        // Caso B: la maggioranza degli adiacenti è del nemico → rischio nicchia (+150)
        {
            List<String> confinanti = getConfinanti(territorio);
            if (!confinanti.isEmpty()) {
                long adjNemici = confinanti.stream()
                        .filter(adj -> coloreNemico.equals(
                                mappa.getOrDefault(adj, new TerritoryState("?",0)).getColore()))
                        .count();
                if (adjNemici >= confinanti.size() - 1) {
                    // Quasi tutti gli adiacenti sono del nemico → nicchia immediata
                    p += 350;
                } else if (adjNemici >= (confinanti.size() + 1) / 2) {
                    // Maggioranza degli adiacenti è del nemico → rischio nicchia
                    p += 150;
                }
            }
        }

        // ── PRIORITÀ 5: coalizione contro il giocatore dominante ───────────
        // Due condizioni indipendenti che si sommano:
        //
        // A) Ha >11 territori → minaccia globale, tutti lo attaccano
        //    Bonus proporzionale: +60 per ogni territorio oltre 11
        //
        // B) Controlla Nord America, Asia o Europa → bonus continente molto
        //    forte (+5 o +7 rinforzi/turno) → priorità assoluta fermarlo
        //    Bonus fisso: +500
        //
        // Le due priorità si moltiplicano bene con l'obiettivo:
        // se il territorio da conquistare è anche in obiettivo del bot
        // attaccante, i bonus si sommano → attacco quasi certo.

        long territoriNemico = mappa.values().stream()
                .filter(st -> st.getColore().equals(coloreNemico))
                .count();

        // Peso effettivo del nemico: territori reali + bonus continente
        // (Oceania/SA/Africa=+5, NA/EU/Asia=+10)
        int pesoNemico = calcolaPesoAvversario(coloreNemico, mappa);

        // Priorità di attacco proporzionale al peso (>11 = minaccia crescente)
        if (pesoNemico > 11) {
            p += (pesoNemico - 11) * 60; // +60 per ogni "territorio equivalente" oltre 11
        }

        // Difesa proporzionale: più il nemico è forte, più vale attaccarlo
        // per indebolirlo (coalizione implicita di tutti vs dominante)
        if (pesoNemico >= 20) p += 200; // molto dominante → urgenza alta
        else if (pesoNemico >= 15) p += 100; // moderatamente dominante

        // Malus: armate difensore (rischioso attaccare posizioni forti)
        p -= armateNemico * 3;

        // ── Perturbazione casuale ±10% ───────────────────────────────────────
        // Introduce variabilità nelle decisioni dell'AI: 5-10% delle mosse
        // saranno "sorpresa" — l'AI non sceglie sempre il target ottimale.
        if (p > 0) {
            int noise = (int)(p * (RND.nextDouble() * 0.2 - 0.1)); // da -10% a +10%
            p += noise;
        }

        return p;
    }

    private boolean eseguiAttacco(AttaccoCandidate att, Map<String, TerritoryState> mappa,
                                  List<String> log, String colore) {
        TerritoryState da    = mappa.get(att.da());
        TerritoryState verso = mappa.get(att.verso());
        String difensore = verso.getColore();

        int totPerseAtt = 0;
        int totPerseDif = 0;

        // ── Multi-round: ogni round = un lancio di dadi ─────────────────────
        // Regole dadi:
        //   Attaccante: max 3 dadi, ma al massimo (armate - MIN_ARMATE_DIFESA)
        //               così non scende mai sotto il minimo strategico
        //   Difensore:  max 3 dadi (house rule: 3, non 2 come nel classico)
        //   Confronto:  coppie dal più alto; il dado più basso perde 1 armata
        //   Parità:     vince sempre il difensore
        int round = 0;
        // Combatte finché il difensore ha armate E l'attaccante ha almeno 2
        // (regola: deve restare almeno 1 in caso di vittoria + 1 per la difesa del territorio)
        while (da.getArmate() > MIN_ARMATE_DIFESA && verso.getArmate() > 0 && round++ < 100) {
            // Dadi attaccante: massimo 3, deve lasciare almeno MIN_ARMATE_DIFESA
            int numAtt = Math.min(3, da.getArmate() - 1); // lascia 1 in territorio (regola gioco, non MIN_ARMATE_DIFESA)
            // Dadi difensore: massimo 2 (regola standard Risiko)
            // Con 3v3, P(att vince dado) = 41.7% → il difensore vince sempre. Bug matematico.
            // Con 3v2, P(att vince) ≈ 57% → l'attaccante ha un vantaggio reale.
            int numDif = Math.min(2, verso.getArmate());

            int[] datiAtt = tiraDadiOrdinati(numAtt);
            int[] datiDif = tiraDadiOrdinati(numDif);

            // Confronta dal più alto: min(att, dif) coppie
            int confronti = Math.min(numAtt, numDif);
            int perseAtt  = 0;
            int perseDif  = 0;

            for (int i = 0; i < confronti; i++) {
                if (datiAtt[i] > datiDif[i]) perseDif++; // attaccante vince la coppia
                else                          perseAtt++; // parità → difensore vince
            }

            da.setArmate(da.getArmate() - perseAtt);
            verso.setArmate(Math.max(0, verso.getArmate() - perseDif));
            totPerseAtt += perseAtt;
            totPerseDif += perseDif;
        }

        boolean conquistato = verso.getArmate() <= 0;

        if (conquistato) {
            int spedite   = Math.max(MIN_ARMATE_DIFESA, da.getArmate() - MIN_ARMATE_DIFESA);
            int rimanenti = da.getArmate() - spedite;
            if (rimanenti < MIN_ARMATE_DIFESA) {
                spedite   = da.getArmate() - MIN_ARMATE_DIFESA;
                rimanenti = MIN_ARMATE_DIFESA;
            }
            da.setArmate(rimanenti);
            verso.setColore(colore);
            verso.setArmate(Math.max(1, spedite));

            territorConquistatiNelTurno.merge(colore, 1, Integer::sum);
            log.add("⚔️ " + lbl(colore) + " conquista " + nom(att.verso()) +
                    " da " + nom(att.da()) + " [era " + lbl(difensore) + "]" +
                    " (att -" + totPerseAtt + " | dif -" + totPerseDif + ")");
        } else {
            log.add("🛡 " + lbl(difensore) + " respinge " + lbl(colore) +
                    " su " + nom(att.verso()) +
                    " [att -" + totPerseAtt + ", dif -" + totPerseDif + "]");
        }

        // Registra evento per il frontend (frecce SVG)
        eventiAttaccoTurno.add(new EventoAttacco(
                att.da(), att.verso(), colore, difensore, conquistato));

        return conquistato;
    }

    /** Tira {@code n} dadi e restituisce i valori ordinati in modo DECRESCENTE. */
    private int[] tiraDadiOrdinati(int n) {
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = RND.nextInt(6) + 1;
        Arrays.sort(r);
        for (int i = 0, j = n - 1; i < j; i++, j--) { int t = r[i]; r[i] = r[j]; r[j] = t; }
        return r;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SPOSTAMENTO FINALE (solo territorio adiacente)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Veta un attacco se eseguirlo lascerebbe un territorio in obiettivo
     * che è l'ultimo nel suo continente con difesa insufficiente.
     *
     * La difesa è insufficiente se:
     * - Le armate del territorio ≤ 2x le armate nemiche adiacenti massime
     * - Se il nemico più forte ha ≥6 armate adiacenti, si stima +10 per tris
     *   (con 3 carte un bot può aggiungere 10 armate extra al prossimo turno)
     */
    private boolean vetaAttaccoPerEsposizione(String colore, AttaccoCandidate attacco,
                                              ObiettivoTarget obj,
                                              Map<String, TerritoryState> mappa) {
        // Dopo l'attacco, il territorio "da" perde alcune armate.
        // Simula la situazione peggiore: attaccante perde MIN_ARMATE_DIFESA armate
        // (scende al minimo consentito).
        String da = attacco.da();
        int armateDopoAttacco = MIN_ARMATE_DIFESA; // scenario peggiore

        // Controlla tutti i nostri territori in obiettivo che sono ultimi nel continente
        List<String> miei = getTerritoriGiocatore(colore, mappa);
        for (String t : miei) {
            if (!isInObiettivo(t, obj)) continue;

            // È l'ultimo nel suo continente?
            boolean ultimoInContinente = false;
            for (Map.Entry<String, List<String>> ce : RisikoBoardData.CONTINENTI.entrySet()) {
                List<String> tc = ce.getValue();
                if (!tc.contains(t)) continue;
                long mieiInCont = tc.stream()
                        .filter(terr -> colore.equals(
                                mappa.getOrDefault(terr, new TerritoryState("?",0)).getColore()))
                        .count();
                if (mieiInCont == 1) { ultimoInContinente = true; break; }
            }
            if (!ultimoInContinente) continue;

            // Calcola armate del territorio dopo l'attacco
            int armateT = t.equals(da) ? armateDopoAttacco : mappa.get(t).getArmate();

            // Calcola minaccia nemica massima adiacente
            int maxNemica = getConfinanti(t).stream()
                    .filter(adj -> {
                        TerritoryState st = mappa.getOrDefault(adj, new TerritoryState("?",0));
                        return !colore.equals(st.getColore()) && !"?".equals(st.getColore());
                    })
                    .mapToInt(adj -> mappa.get(adj).getArmate())
                    .max().orElse(0);

            if (maxNemica == 0) continue;

            // Stima tris nemico: se ha ≥6 armate adiacenti, potrebbe avere carte
            int minacciaStimata = maxNemica >= 6 ? maxNemica + 10 : maxNemica;

            // Veta se la difesa è insufficiente (< 2x la minaccia stimata)
            if (armateT < minacciaStimata * 2) {
                return true; // veta l'attacco
            }
        }
        return false;
    }

    private void eseguiSpostamento(String colore, int obiettivoId, int turno,
                                   Map<String, TerritoryState> mappa, List<String> log) {
        // REGOLA: se ha già fatto uno spostamento per cartina questo turno, non ne fa un altro
        if (territoriCartina.containsKey(colore + "_spostato")) return;

        // REGOLA: giocatori con >11 territori O con continente non lasciano mai 2 armate
        // in nessun territorio durante lo spostamento
        List<String> mieiCheck = getTerritoriGiocatore(colore, mappa);
        boolean sonoDominanteSposta = mieiCheck.size() > 11
                || RisikoBoardData.CONTINENTI.values().stream()
                .anyMatch(tc -> mieiCheck.containsAll(tc));

        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);
        List<String> miei = getTerritoriGiocatore(colore, mappa);
        List<String> confine = getTerritoriBordoNemico(colore, miei, mappa);
        if (confine.isEmpty()) return;

        // Territorio appena conquistato via cartina: non può essere fonte di spostamento
        String terrCartinaConquistato = territoriCartina.getOrDefault(colore + "_conquistato", null);

        // Trova territorio interno adiacente a un confine con molte armate da spostare
        for (String frontiera : confine) {
            // Cerca territorio interno adiacente con più armate
            Optional<String> interno = getConfinanti(frontiera).stream()
                    .filter(t -> colore.equals(mappa.getOrDefault(t,
                            new TerritoryState("?", 0)).getColore()))
                    .filter(t -> !confine.contains(t)) // è interno
                    .filter(t -> !t.equals(terrCartinaConquistato)) // non è il territorio cartina
                    .filter(t -> mappa.get(t).getArmate() > MIN_ARMATE_DIFESA + 1)
                    .max(Comparator.comparingInt(t -> mappa.get(t).getArmate()));

            if (interno.isEmpty()) continue;

            String fonte = interno.get();

            // Verifica esplicita adiacenza (regola: solo tra territori adiacenti)
            if (!getConfinanti(fonte).contains(frontiera)) continue;

            // In sdadata (T35+): non lasciare mai 2 armate nella fonte,
            // a meno che il totale (fonte + frontiera) sia ≤ 7.
            // In quel caso si possono lasciare anche solo 1-2 armate.
            // Fuori sdadata: lascia 1 armata (regola standard).
            int armFonte = mappa.get(fonte).getArmate();
            int armFrontiera = mappa.get(frontiera).getArmate();
            int totale = armFonte + armFrontiera;

            int minimoNellaFonte;
            if (turno >= 35) {
                // Sdadata: lascia almeno 3 in origine (mai 2), salvo totale ≤ 7
                minimoNellaFonte = (totale <= 7) ? 1 : 3;
            } else if (sonoDominanteSposta) {
                // Dominante (>11t o continente): mai lasciare 2 armate — minimo 3
                minimoNellaFonte = 3;
            } else {
                minimoNellaFonte = 1; // regola standard
            }

            int qty = armFonte - minimoNellaFonte;
            if (qty <= 0) continue;

            // Sposta solo verso frontiera utile: in obiettivo, strategica o blocca continente
            boolean valePena = isInObiettivo(frontiera, obj)
                    || TERRITORI_STRATEGICI.contains(frontiera)
                    || staBlocandoContinente(frontiera, null, mappa);

            if (!valePena) continue;

            mappa.get(fonte).setArmate(1); // lascia 1 armata
            mappa.get(frontiera).setArmate(mappa.get(frontiera).getArmate() + qty);
            log.add("🚀 " + lbl(colore) + " sposta " + qty + " armate da " +
                    nom(fonte) + " → " + nom(frontiera));
            break; // REGOLA: 1 solo spostamento per turno, tra territori adiacenti
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SISTEMA CARTINA
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Gestisce la logica cartina:
     * 1. Rileva se un avversario sta offrendo una cartina
     * 2. Decide se accettarla o rifiutarla
     * 3. Decide se offrirne una propria
     * 4. Ricambia se è in accordo attivo
     */
    private void gestisciCartina(String colore, int obiettivoId, int turno,
                                 Map<String, TerritoryState> mappa, List<String> log) {
        // REGOLA: dal turno 33 in poi nessuno può aprire NÉ mantenere cartine
        if (turno >= 33) {
            // Chiudi tutti gli accordi attivi per questo giocatore
            String partner = accordiCartina.remove(colore);
            if (partner != null) {
                accordiCartina.remove(partner); // rimuovi anche il lato opposto
                log.add("🤝 " + lbl(colore) + " chiude accordo cartina (turno 33+)");
            }
            territoriCartina.remove(colore);
            territoriCartina.remove(colore + "_spostato");
            return;
        }

        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);
        List<String> miei = getTerritoriGiocatore(colore, mappa);
        List<Carta> mano = maniCarte.getOrDefault(colore, new ArrayList<>());

        int punteggioMio = calcolaPunteggioAttuale(colore, obiettivoId, mappa);
        boolean hoBisognoDiCarte = mano.size() < 3;
        boolean sonoLontanoDaObiettivo = punteggioMio < 40;

        // ── AGGIORNA CONTATORE TERRITORI SCOPERTI ────────────────────────────
        mappa.forEach((terr, st) -> {
            if (!colore.equals(st.getColore()) && st.getArmate() == 2) {
                boolean adj = getConfinanti(terr).stream()
                        .anyMatch(a -> colore.equals(mappa.getOrDefault(a, new TerritoryState("?",0)).getColore()));
                if (adj) turniTerritorioScoperto.merge(terr, 1, Integer::sum);
            }
        });
        turniTerritorioScoperto.entrySet().removeIf(e -> {
            TerritoryState st = mappa.get(e.getKey());
            return st == null || st.getArmate() != 2;
        });

        // ── RILEVA E ACCETTA/RIFIUTA CARTINA OFFERTA ─────────────────────────
        String cartinaTrovata = rilevaCarthina(colore, miei, mappa, obj);
        if (cartinaTrovata != null) {
            String partner = mappa.get(cartinaTrovata).getColore();
            int turniScoperto = turniTerritorioScoperto.getOrDefault(cartinaTrovata, 0);
            if (turniScoperto > 3) {
                // Territorio scoperto da >3 turni: cartina scaduta, posso conquistare
                log.add("⚔️ " + lbl(colore) + " può conquistare (cartina scaduta dopo " + turniScoperto + " turni)");
            } else {
                boolean accetta = deveAccettareCartina(colore, partner, mano, mappa,
                        hoBisognoDiCarte, sonoLontanoDaObiettivo);
                if (accetta) {
                    accettaCartina(colore, cartinaTrovata, partner, obiettivoId, mappa, log);
                } else {
                    log.add("🤝 " + lbl(colore) + " rifiuta cartina di " + lbl(partner));
                }
            }
        }

        // ── SE HO OFFERTO CARTINA E L'ALTRO RIFIUTA → DEVO RICOPRIRE ────────
        if (territoriCartina.containsKey(colore)) {
            String terrMio = territoriCartina.get(colore);
            TerritoryState st = mappa.get(terrMio);
            int turnoOfferta = turnoOffertaCartina.getOrDefault(colore, turno);
            if (st != null && colore.equals(st.getColore()) && st.getArmate() == 2
                    && turno - turnoOfferta >= 2) {
                int maxNem = getConfinanti(terrMio).stream()
                        .filter(adj -> !colore.equals(mappa.getOrDefault(adj, new TerritoryState("?",0)).getColore()))
                        .mapToInt(adj -> mappa.get(adj).getArmate()).max().orElse(0);
                int minimo = Math.max(6, maxNem * 2);
                for (String t : new ArrayList<>(miei)) {
                    if (t.equals(terrMio)) continue;
                    TerritoryState fonte = mappa.get(t);
                    if (fonte == null || fonte.getArmate() <= MIN_ARMATE_DIFESA) continue;
                    boolean interno = getConfinanti(t).stream().allMatch(a ->
                            colore.equals(mappa.getOrDefault(a, new TerritoryState("?",0)).getColore()));
                    if (!interno) continue;
                    int sposta = Math.min(fonte.getArmate() - MIN_ARMATE_DIFESA, minimo - st.getArmate());
                    if (sposta > 0) {
                        fonte.setArmate(fonte.getArmate() - sposta);
                        st.setArmate(st.getArmate() + sposta);
                        log.add("🔒 " + lbl(colore) + " ricopre territorio cartina " + nom(terrMio) + " → " + st.getArmate() + " armate");
                        territoriCartina.put(colore + "_spostato", terrMio); // conta come spostamento
                        break;
                    }
                }
                territoriCartina.remove(colore);
                accordiCartina.remove(colore);
            }
        }

        // ── REGOLA: annulla accordo se il partner ha >11 territori ─────────────
        // Non si lascia mai un territorio a 2 armate a chi è troppo forte.
        String partnerAttivo = accordiCartina.get(colore);
        if (partnerAttivo != null) {
            List<String> lorTerr = getTerritoriGiocatore(partnerAttivo, mappa);
            if (lorTerr.size() > 11) {
                accordiCartina.remove(colore);
                accordiCartina.remove(partnerAttivo);
                territoriCartina.remove(colore);
                log.add("🤝 " + lbl(colore) + " annulla accordo con " + lbl(partnerAttivo) + " (troppo forte)");
            }
        }

        // ── OFFRI CARTINA ─────────────────────────────────────────────────────
        // Probabilità base: 5% per turno.
        // ECCEZIONE: se 1-2 giocatori hanno >11 territori O un continente,
        // gli altri (2-3) si coalizzano via cartina → probabilità più alta (60%)
        // e in quel caso è l'UNICO caso dove la cartina può essere in 3 giocatori.

        boolean esistonoBersagliDeboli = miei.stream()
                .flatMap(t -> getConfinanti(t).stream()).distinct()
                .anyMatch(adj -> { TerritoryState st = mappa.get(adj);
                    return st != null && !colore.equals(st.getColore()) && st.getArmate() <= 2; });

        // Calcola giocatori dominanti
        List<String> dominanti = mappa.values().stream()
                .map(TerritoryState::getColore)
                .filter(c -> !c.equals(colore) && !"?".equals(c))
                .distinct()
                .filter(c -> {
                    List<String> loro = getTerritoriGiocatore(c, mappa);
                    if (loro.size() > 11) return true;
                    return RisikoBoardData.CONTINENTI.values().stream()
                            .anyMatch(loro::containsAll);
                })
                .collect(Collectors.toList());

        boolean coelizioneAttiva = !dominanti.isEmpty() && dominanti.size() <= 2;
        double probabilitaCartina = coelizioneAttiva ? 0.60 : 0.05;

        // In coalizione, si può fare cartina anche in 3 (unico caso ammesso)
        // I giocatori dominanti non aprono mai cartina
        boolean sonoDominante = getTerritoriGiocatore(colore, mappa).size() > 11
                || RisikoBoardData.CONTINENTI.values().stream()
                .anyMatch(tc -> getTerritoriGiocatore(colore, mappa).containsAll(tc));

        // Non si può mai aprire cartina con solo 9 territori
        boolean hoSoloNoveTerr = getTerritoriGiocatore(colore, mappa).size() <= 9;

        boolean possoFareCartina = !sonoDominante
                && !hoSoloNoveTerr
                && !accordiCartina.containsKey(colore)
                && !esistonoBersagliDeboli
                && hoBisognoDiCarte
                && sonoLontanoDaObiettivo
                && RND.nextDouble() < probabilitaCartina;

        // Limite 2 cartine totali — ma in coalizione si può arrivare a 3 accordi
        long accordiAttivi = accordiCartina.size() / 2;
        boolean limiteOk = coelizioneAttiva ? accordiAttivi < 3 : accordiAttivi < 2;

        if (possoFareCartina && limiteOk) {
            offriCartina(colore, obiettivoId, miei, mappa, log, turno);
        }

        // ── RICAMBIA CARTINA SE IN ACCORDO ATTIVO ────────────────────────────
        if (accordiCartina.containsKey(colore)) {
            ricambiaCartina(colore, obiettivoId, miei, mappa, log);
        }

        // ── ROMPI ACCORDO SE NON SERVE PIÙ ───────────────────────────────────
        if (!hoBisognoDiCarte && mano.size() >= 3) {
            String partner = accordiCartina.remove(colore);
            if (partner != null) log.add("🤝 " + lbl(colore) + " interrompe accordo con " + lbl(partner));
        }
    }

    /**
     * Rileva se un avversario ha lasciato un territorio con 2 armate
     * che in precedenza ne aveva di più (segnale di cartina).
     */
    private String rilevaCarthina(String colore, List<String> miei,
                                  Map<String, TerritoryState> mappa, ObiettivoTarget obj) {
        for (String mio : miei) {
            for (String adiacente : getConfinanti(mio)) {
                TerritoryState st = mappa.get(adiacente);
                if (st == null || colore.equals(st.getColore())) continue;

                // Segnali di cartina:
                // 1. Ha esattamente 2 armate
                if (st.getArmate() != MIN_ARMATE_DIFESA) continue;

                // 2. Prima ne aveva di più (spostamento rilevato)
                int precedenti = armaturePrecedenti.getOrDefault(adiacente, -1);
                if (precedenti <= MIN_ARMATE_DIFESA) continue;

                // 3. Il territorio non è nell'obiettivo dell'avversario
                //    (dedotto: se lo stanno lasciando scoperto, non gli serve)
                // Non possiamo saperlo con certezza, ma se è un territorio
                // a basso valore (pochi confinanti) è più probabile sia fuori obiettivo
                if (getConfinanti(adiacente).size() >= 5) continue; // territorio troppo prezioso

                return adiacente;
            }
        }
        return null;
    }

    /**
     * Decide se accettare la cartina offerta da un avversario.
     */
    private boolean deveAccettareCartina(String colore, String partner,
                                         List<Carta> mano,
                                         Map<String, TerritoryState> mappa,
                                         boolean hoBisognoDiCarte,
                                         boolean sonoLontanoDaObiettivo) {
        // Rifiuta se ho già abbastanza carte
        if (!hoBisognoDiCarte) return false;
        // Rifiuta se non sono lontano dall'obiettivo
        if (!sonoLontanoDaObiettivo) return false;

        List<String> lorTerritori = getTerritoriGiocatore(partner, mappa);

        // ── MOTIVI DI RIFIUTO (regole esplicite) ────────────────────────────
        // 1. Il partner ha punteggio più alto in obiettivo (è più avanti di me)
        //    → non conviene aiutarlo con una carta gratuita
        // 2. Il partner ha più di 11 territori → troppo forte, evita di rafforzarlo
        // 3. Il partner controlla un continente → già abbastanza vantaggi
        if (lorTerritori.size() > 11) return false;

        boolean haContinente = RisikoBoardData.CONTINENTI.values().stream()
                .anyMatch(lorTerritori::containsAll);
        if (haContinente) return false;

        return true; // accetta: il partner è nella norma
    }

    /**
     * Accetta la cartina: conquista il territorio con 2 armate
     * e decide se ricambiare lasciando 2 armate nel territorio conquistato
     * oppure su un altro territorio adiacente al partner.
     */
    private void accettaCartina(String colore, String territorio, String partner,
                                int obiettivoId, Map<String, TerritoryState> mappa,
                                List<String> log) {
        TerritoryState st = mappa.get(territorio);
        if (st == null) return;

        // Trova territorio da cui attaccare
        Optional<String> fonte = getConfinanti(territorio).stream()
                .filter(t -> colore.equals(mappa.getOrDefault(t,
                        new TerritoryState("?", 0)).getColore()))
                .filter(t -> mappa.get(t).getArmate() > MIN_ARMATE_DIFESA)
                .max(Comparator.comparingInt(t -> mappa.get(t).getArmate()));

        if (fonte.isEmpty()) return;

        // Conquista
        TerritoryState tfonte = mappa.get(fonte.get());
        tfonte.setArmate(tfonte.getArmate() - 1);
        st.setColore(colore);
        st.setArmate(MIN_ARMATE_DIFESA); // lascia 2 per ricambiare

        territorConquistatiNelTurno.merge(colore, 1, Integer::sum);
        pescaCarta(colore);

        log.add("🤝 " + lbl(colore) + " accetta cartina da " + lbl(partner) +
                " → conquista " + nom(territorio) + " (lascia " + MIN_ARMATE_DIFESA + ")");
        eventiCartinaTurno.add(new EventoCartina(partner, colore, territorio));
        territoriCartina.put(colore + "_spostato", territorio); // conta come spostamento del turno

        // Registra accordo bidirezionale (A→B e B→A)
        // Solo se non supera il limite di 2 cartine attive
        long accordiAttivi = accordiCartina.size() / 2;
        if (accordiAttivi < 2) {
            accordiCartina.put(colore, partner);
            accordiCartina.put(partner, colore); // bidirezionale
            territoriCartina.put(colore, territorio);
            // Marca il territorio come "conquistato via cartina" per bloccare lo spostamento
            // verso destinazioni diverse — le armate devono restare lì
            territoriCartina.put(colore + "_conquistato", territorio);
        }
    }

    /**
     * Offre una cartina: sposta armate lasciando 2 in un territorio
     * fuori obiettivo adiacente al partner preferito.
     */
    private void offriCartina(String colore, int obiettivoId, List<String> miei,
                              Map<String, TerritoryState> mappa, List<String> log, int turno) {
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);

        String partnerIdeale = trovaMigliorPartnerCartina(colore, mappa);
        if (partnerIdeale == null) return;

        List<String> lorTerritori = getTerritoriGiocatore(partnerIdeale, mappa);

        // ── CRITERI TERRITORIO DA OFFRIRE ─────────────────────────────────
        // Preferisce territori che valgono 5-7 punti (molte adiacenze = valore alto)
        // oppure 5-7 punti SE in obiettivo (doppio valore per il partner)
        // Il territorio deve avere >2 armate per poter lasciarne 2
        // Colori con cui NON voglio fare cartina (dominanti + chi non è il partner)
        Set<String> coloriIndesiderati = mappa.values().stream()
                .map(TerritoryState::getColore)
                .filter(c -> !c.equals(colore) && !c.equals(partnerIdeale) && !"?".equals(c))
                .collect(Collectors.toSet());

        Optional<String> territorioDaLasciare = miei.stream()
                .filter(t -> !isInObiettivo(t, obj))            // fuori obiettivo
                .filter(t -> !TERRITORI_STRATEGICI.contains(t)) // non strategico
                .filter(t -> mappa.get(t).getArmate() > MIN_ARMATE_DIFESA + 1)
                .filter(t -> getConfinanti(t).stream().anyMatch(lorTerritori::contains)) // adiacente al partner
                .filter(t -> getConfinanti(t).size() < 6) // REGOLA: mai su territori con ≥6 punti
                .filter(t -> getConfinanti(t).stream().noneMatch(adj -> // REGOLA: nessun adiacente indesiderato
                        coloriIndesiderati.contains(
                                mappa.getOrDefault(adj, new TerritoryState("?",0)).getColore())))
                .filter(t -> {
                    // Valore ottimale: 4-5 adiacenze
                    int adj = getConfinanti(t).size();
                    return adj >= 4 && adj <= 5;
                })
                .max(Comparator.comparingInt(t -> getConfinanti(t).size()));

        // Fallback: qualsiasi territorio che rispetta le 2 regole fondamentali
        if (territorioDaLasciare.isEmpty()) {
            territorioDaLasciare = miei.stream()
                    .filter(t -> !isInObiettivo(t, obj))
                    .filter(t -> !TERRITORI_STRATEGICI.contains(t))
                    .filter(t -> mappa.get(t).getArmate() > MIN_ARMATE_DIFESA + 1)
                    .filter(t -> getConfinanti(t).stream().anyMatch(lorTerritori::contains))
                    .filter(t -> getConfinanti(t).size() < 6) // mai ≥6 punti
                    .filter(t -> getConfinanti(t).stream().noneMatch(adj ->
                            coloriIndesiderati.contains(
                                    mappa.getOrDefault(adj, new TerritoryState("?",0)).getColore())))
                    .max(Comparator.comparingInt(t -> mappa.get(t).getArmate()));
        }

        if (territorioDaLasciare.isEmpty()) return;

        // REGOLA FINALE: non lasciare mai a 2 armate se il partner ha >11 territori
        // (potrebbe conquistarlo facilmente)
        if (getTerritoriGiocatore(partnerIdeale, mappa).size() > 11) return;

        String terrCartina = territorioDaLasciare.get();
        TerritoryState st = mappa.get(terrCartina);
        int armateToSposta = st.getArmate() - MIN_ARMATE_DIFESA;
        if (armateToSposta <= 0) return;

        // Trova territorio vicino dove spostare le armate in eccesso
        Optional<String> destinazione = getConfinanti(terrCartina).stream()
                .filter(t -> colore.equals(mappa.getOrDefault(t,
                        new TerritoryState("?", 0)).getColore()))
                .filter(t -> isInObiettivo(t, obj))
                .findFirst()
                .or(() -> getConfinanti(terrCartina).stream()
                        .filter(t -> colore.equals(mappa.getOrDefault(t,
                                new TerritoryState("?", 0)).getColore()))
                        .findFirst());

        if (destinazione.isEmpty()) return;

        // Sposta lasciando MIN_ARMATE_DIFESA
        st.setArmate(MIN_ARMATE_DIFESA);
        mappa.get(destinazione.get()).setArmate(
                mappa.get(destinazione.get()).getArmate() + armateToSposta);

        accordiCartina.put(colore, partnerIdeale);
        territoriCartina.put(colore, terrCartina);
        territoriCartina.put(colore + "_spostato", terrCartina); // spostamento già fatto

        log.add("🤝 " + lbl(colore) + " offre cartina a " + lbl(partnerIdeale) +
                " su " + nom(terrCartina) + " (2 armate)");
        eventiCartinaTurno.add(new EventoCartina(colore, partnerIdeale, terrCartina));
    }

    /**
     * Ricambia la cartina: lascia 2 armate su un territorio adiacente al partner.
     */
    private void ricambiaCartina(String colore, int obiettivoId, List<String> miei,
                                 Map<String, TerritoryState> mappa, List<String> log) {
        String partner = accordiCartina.get(colore);
        if (partner == null) return;

        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);
        List<String> lorTerritori = getTerritoriGiocatore(partner, mappa);

        // Trova un nuovo territorio fuori obiettivo adiacente al partner
        Optional<String> nuovaCartina = miei.stream()
                .filter(t -> !isInObiettivo(t, obj))
                .filter(t -> !TERRITORI_STRATEGICI.contains(t))
                .filter(t -> mappa.get(t).getArmate() > MIN_ARMATE_DIFESA + 1)
                .filter(t -> getConfinanti(t).stream().anyMatch(lorTerritori::contains))
                .filter(t -> !t.equals(territoriCartina.get(colore))) // territorio diverso dal precedente
                .max(Comparator.comparingInt(t -> mappa.get(t).getArmate()));

        if (nuovaCartina.isEmpty()) return;

        String terrCartina = nuovaCartina.get();
        TerritoryState st = mappa.get(terrCartina);
        int armateToSposta = st.getArmate() - MIN_ARMATE_DIFESA;
        if (armateToSposta <= 0) return;

        // Trova dove spostare le armate in eccesso
        Optional<String> destinazione = getConfinanti(terrCartina).stream()
                .filter(t -> colore.equals(mappa.getOrDefault(t,
                        new TerritoryState("?", 0)).getColore()))
                .findFirst();

        if (destinazione.isEmpty()) return;

        st.setArmate(MIN_ARMATE_DIFESA);
        mappa.get(destinazione.get()).setArmate(
                mappa.get(destinazione.get()).getArmate() + armateToSposta);

        territoriCartina.put(colore, terrCartina);
        territoriCartina.put(colore + "_spostato", terrCartina); // spostamento già fatto
        log.add("🤝 " + lbl(colore) + " ricambia cartina a " + lbl(partner) +
                " su " + nom(terrCartina));
    }

    /**
     * Trova il miglior partner per la cartina:
     * - Più debole (pochi territori)
     * - Senza continente completato
     * - Adiacente a qualche territorio dell'AI
     */
    private String trovaMigliorPartnerCartina(String colore,
                                              Map<String, TerritoryState> mappa) {
        // ── REGOLE CARTINA ──────────────────────────────────────────────────
        // 1. La cartina esiste SOLO tra 2 giocatori (non in 3 o 4)
        // 2. Max 2 cartine attive contemporaneamente (coppie alternate: A-B e C-D)
        // 3. Se già ci sono 2 accordi attivi → non aprire altri
        // 4. Non fare cartina con chi è già in cartina con qualcun altro

        // Conta accordi attivi totali (ogni accordo è registrato su entrambi i lati)
        long accordiAttivi = accordiCartina.size() / 2;
        // In coalizione (giocatore dominante esiste) si ammettono fino a 3 accordi
        // altrimenti massimo 2
        boolean dominanteEsiste = mappa.values().stream()
                .map(TerritoryState::getColore)
                .filter(c -> !c.equals(colore) && !"?".equals(c))
                .distinct()
                .anyMatch(c -> {
                    List<String> loro = getTerritoriGiocatore(c, mappa);
                    if (loro.size() > 11) return true;
                    return RisikoBoardData.CONTINENTI.values().stream().anyMatch(loro::containsAll);
                });
        int maxAccordi = dominanteEsiste ? 3 : 2;
        if (accordiAttivi >= maxAccordi) return null;

        List<String> miei = getTerritoriGiocatore(colore, mappa);
        Set<String> adiacenti = miei.stream()
                .flatMap(t -> getConfinanti(t).stream())
                .map(t -> mappa.getOrDefault(t, new TerritoryState("?", 0)).getColore())
                .filter(c -> !colore.equals(c) && !"?".equals(c))
                .collect(Collectors.toSet());

        return adiacenti.stream()
                .filter(partner -> {
                    // Non fare cartina con chi è già in accordo con qualcun altro
                    if (accordiCartina.containsKey(partner)) return false;
                    // Non fare cartina con chi ha troppi territori
                    List<String> loro = getTerritoriGiocatore(partner, mappa);
                    if (loro.size() > 11) return false;
                    // Non fare cartina con chi controlla un continente
                    return RisikoBoardData.CONTINENTI.values().stream()
                            .noneMatch(terrCont -> loro.containsAll(terrCont));
                })
                .min(Comparator.comparingInt(partner ->
                        getTerritoriGiocatore(partner, mappa).size()))
                .orElse(null);
    }

    /**
     * Salva le armate attuali per rilevare spostamenti al turno successivo.
     */
    /**
     * Calcola il "peso effettivo" di un avversario per calibrare difesa e attacco.
     * Territori reali + bonus continente:
     *   Oceania/Sud America/Africa → +5 territori equivalenti
     *   Nord America/Europa/Asia   → +10 territori equivalenti
     */
    private int calcolaPesoAvversario(String coloreAvv, Map<String, TerritoryState> mappa) {
        List<String> loro = getTerritoriGiocatore(coloreAvv, mappa);
        int peso = loro.size();
        for (Map.Entry<String, List<String>> ce : RisikoBoardData.CONTINENTI.entrySet()) {
            if (!loro.containsAll(ce.getValue())) continue;
            peso += switch (ce.getKey()) {
                case "nordamerica", "europa", "asia" -> 10;
                default -> 5;
            };
        }
        return peso;
    }

    private void salvaArmaturePrecedenti(Map<String, TerritoryState> mappa) {
        mappa.forEach((t, st) -> armaturePrecedenti.put(t, st.getArmate()));
    }



    // ═══════════════════════════════════════════════════════════════════════════
    //  SDADATA (NUOVO)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Valuta e tenta la sdadata secondo le regole:
     * - La sdadata è disponibile SOLO dal turno 25 in poi
     * - Tira 2 dadi, somma ≤ soglia(turno) → sdadata riuscita → partita finita
     * - Soglie: T35≤4, T36≤5, T37≤6, T38+≤7
     * - L'AI tenta SOLO se ha punteggio > massimo avversario stimato
     * - Non può tentare se ha conquistato >2 territori nel turno corrente
     *
     * @return SdadataResult con esito del tentativo, oppure null se non tenta
     */
    /**
     * Sdadata OBBLIGATORIA se conquiste ≤ 2.
     *
     * GIALLO (4° di mano) inizia al T35:  T35=4, T36=5, T37=6, T38+=7
     * BLU/ROSSO/VERDE iniziano al T36:    T36=4, T37=5, T38=6, T39+=7
     * Dal T39 (GIALLO) e T40 (altri) in poi la soglia è fissa a 7.
     * Al T45 la partita finisce comunque.
     */
    public SdadataResult valutaSdadata(String colore, int obiettivoId, int turno,
                                       Map<String, TerritoryState> mappa) {
        // Condizione 1: conquiste > 2 → non può sdadare
        int conquiste = territorConquistatiNelTurno.getOrDefault(colore, 0);
        if (conquiste > 2) return null;

        // GIALLO inizia al T35, tutti gli altri al T36
        int turnoBase = "GIALLO".equals(colore) ? 35 : 36;
        if (turno < turnoBase) return null;

        // Soglia: 4 al turnoBase, +1 ogni turno, max 7
        int soglia = Math.min(7, 4 + (turno - turnoBase));

        int dado1  = RND.nextInt(6) + 1;
        int dado2  = RND.nextInt(6) + 1;
        int totale = dado1 + dado2;

        return new SdadataResult(true, totale <= soglia, dado1, dado2, totale, soglia);
    }

    private String avversariDebug(String colore, Map<String, TerritoryState> mappa) {
        return mappa.values().stream()
                .map(TerritoryState::getColore)
                .filter(c -> !colore.equals(c) && !"?".equals(c))
                .distinct()
                .map(c -> lbl(c) + "~" + inferencer.stimaPunteggio(c, mappa) + "pt")
                .collect(Collectors.joining(" | "));
    }

    /** Stima il punteggio aggregato di un giocatore basandosi sui suoi territori
     *  e sull'inferenza degli obiettivi avversari. Usato nell'endgame. */
    private int calcolaPunteggioAggregato(String colore, Map<String, TerritoryState> mappa) {
        if (inferencerInizializzato) {
            return inferencer.stimaPunteggio(colore, mappa);
        }
        // Fallback: somma adiacenze dei suoi territori / 2
        return getTerritoriGiocatore(colore, mappa).stream()
                .mapToInt(t -> getConfinanti(t).size())
                .sum() / 2;
    }

    public int calcolaPunteggioAttuale(String colore, int obiettivoId,
                                       Map<String, TerritoryState> mappa) {
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);
        if (obj == null) return 0;

        return getTerritoriGiocatore(colore, mappa).stream()
                .filter(t -> isInObiettivo(t, obj))
                .mapToInt(t -> getConfinanti(t).size())
                .sum();
    }

    private int getMassimoPunteggioAvversari(String colore, Map<String, TerritoryState> mappa) {
        // FIX: usa l'inferencer bayesiano invece della somma cieca di tutti i confinanti
        Set<String> avversari = mappa.values().stream()
                .map(TerritoryState::getColore)
                .filter(c -> !colore.equals(c) && !"?".equals(c))
                .collect(Collectors.toSet());

        int max = 0;
        for (String avversario : avversari) {
            int stima = inferencerInizializzato
                    ? inferencer.stimaPunteggio(avversario, mappa)
                    : stimaPunteggioFallback(avversario, mappa);
            if (stima > max) max = stima;
        }
        return max;
    }

    /** Fallback pre-inizializzazione: stima conservativa (metà della somma dei confinanti). */
    private int stimaPunteggioFallback(String colore, Map<String, TerritoryState> mappa) {
        return getTerritoriGiocatore(colore, mappa).stream()
                .mapToInt(t -> getConfinanti(t).size())
                .sum() / 2;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  UTILITY DIFESA CONTINENTI
    // ═══════════════════════════════════════════════════════════════════════════

    private String trovaContinenteDaDifendere(String colore,
                                              Map<String, TerritoryState> mappa) {
        for (Map.Entry<String, List<String>> e : RisikoBoardData.CONTINENTI.entrySet()) {
            List<String> terrCont = e.getValue();
            // Conta quanti territori del continente ha un singolo avversario
            Map<String, Long> conteggioPerColore = terrCont.stream()
                    .map(t -> mappa.getOrDefault(t, new TerritoryState("?", 0)).getColore())
                    .filter(c -> !colore.equals(c) && !"?".equals(c))
                    .collect(Collectors.groupingBy(c -> c, Collectors.counting()));

            for (Map.Entry<String, Long> ce : conteggioPerColore.entrySet()) {
                // Se un avversario ha tutti i territori tranne 1 → emergenza!
                if (ce.getValue() >= terrCont.size() - 1) {
                    return e.getKey();
                }
            }
        }
        return null;
    }

    private boolean staBlocandoContinente(String territorio, String coloreNemico,
                                          Map<String, TerritoryState> mappa) {
        for (Map.Entry<String, List<String>> e : RisikoBoardData.CONTINENTI.entrySet()) {
            List<String> terrCont = e.getValue();
            if (!terrCont.contains(territorio)) continue;

            if (coloreNemico != null) {
                long delNemico = terrCont.stream()
                        .filter(t -> {
                            TerritoryState st = mappa.get(t);
                            return st != null && coloreNemico.equals(st.getColore());
                        }).count();
                if (delNemico >= terrCont.size() - 1) return true;
            } else {
                // Controlla qualsiasi avversario
                Map<String, Long> count = terrCont.stream()
                        .map(t -> mappa.getOrDefault(t, new TerritoryState("?", 0)).getColore())
                        .collect(Collectors.groupingBy(c -> c, Collectors.counting()));
                if (count.values().stream().anyMatch(v -> v >= terrCont.size() - 1)) return true;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  UTILITY GENERALI
    // ═══════════════════════════════════════════════════════════════════════════

    public List<String> getTerritoriGiocatore(String colore, Map<String, TerritoryState> mappa) {
        return mappa.entrySet().stream()
                .filter(e -> colore.equals(e.getValue().getColore()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private List<String> getTerritoriBordoNemico(String colore, List<String> miei,
                                                 Map<String, TerritoryState> mappa) {
        return miei.stream()
                .filter(t -> getConfinanti(t).stream()
                        .anyMatch(c -> {
                            TerritoryState st = mappa.get(c);
                            return st != null && !colore.equals(st.getColore());
                        }))
                .collect(Collectors.toList());
    }

    private boolean isInObiettivo(String territorio, ObiettivoTarget obj) {
        if (obj == null) return false;
        if (obj.territoriSpecifici() != null &&
                obj.territoriSpecifici().contains(territorio)) return true;
        if (obj.continentiTarget() != null) {
            for (String cont : obj.continentiTarget()) {
                List<String> terrCont = RisikoBoardData.CONTINENTI.get(cont);
                if (terrCont != null && terrCont.contains(territorio)) return true;
            }
        }
        return false;
    }

    private boolean isInObiettivoContinente(String continente, ObiettivoTarget obj) {
        if (obj == null || obj.continentiTarget() == null) return false;
        return obj.continentiTarget().contains(continente);
    }

    private List<String> getConfinanti(String territorio) {
        return RisikoBoardData.ADIACENZE.getOrDefault(territorio, List.of());
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

    private String nom(String t) {
        if (t == null || t.isEmpty()) return "";
        String s = t.replace("_", " ");
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
    