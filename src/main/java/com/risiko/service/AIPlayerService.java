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
    private static final int SOGLIA_MIN_TERRITORI = 9;

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

    // ── Inferenza obiettivi avversari ─────────────────────────────────────────
    private final ObiettivoInferencer inferencer = new ObiettivoInferencer();
    private boolean inferencerInizializzato = false;

    // ═══════════════════════════════════════════════════════════════════════════
    //  RECORD DI OUTPUT (NUOVO)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Risultato di un turno completo: log eventi + esito sdadata.
     *
     * @param log     lista di messaggi del turno
     * @param sdadata esito della sdadata, null se non tentata
     */
    public record TurnoResult(List<String> log, SdadataResult sdadata) {}

    /**
     * Esito del tentativo di sdadata.
     *
     * @param tentato  true se le condizioni erano soddisfatte e l'AI ha tentato
     * @param riuscita true se la somma dei dadi ≤ soglia del turno
     * @param dado1    valore primo dado
     * @param dado2    valore secondo dado
     * @param totale   somma dado1+dado2
     * @param soglia   soglia massima (T25=4, T26=5, T27=6, T28+=7)
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

    public TurnoResult eseguiTurno(String colore, int obiettivoId, int turno,
                                   Map<String, TerritoryState> mappa) {
        List<String> log = new ArrayList<>();
        log.add("── " + lbl(colore) + " (T" + turno + ") ──");

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
        if (miei.isEmpty()) return new TurnoResult(log, null);

        // Azzera contatore conquiste del turno
        territorConquistatiNelTurno.put(colore, 0);

        // 1. Usa tris se strategicamente utile
        usaTrisSeUtile(colore, obiettivoId, miei, mappa, log);

        // 2. Calcola e piazza rinforzi
        int rinforzi = calcolaRinforzi(colore, miei, mappa);
        piazzaRinforzi(colore, rinforzi, obiettivoId, miei, mappa, log);

        // 3. Esegui attacchi (include logica cartina)
        eseguiAttacchi(colore, obiettivoId, mappa, log);

        // 4. Pesca carta se ha conquistato almeno 1 territorio
        if (territorConquistatiNelTurno.getOrDefault(colore, 0) > 0) {
            pescaCarta(colore);
        }

        // 5. Valuta se offrire cartina o ricambiare
        gestisciCartina(colore, obiettivoId, mappa, log);

        // 6. Spostamento finale su territorio adiacente
        eseguiSpostamento(colore, obiettivoId, mappa, log);

        // 7. Salva armate attuali per rilevare cartine al prossimo turno
        salvaArmaturePrecedenti(mappa);

        // 8. Aggiorna inferenza obiettivi avversari
        inferencer.aggiornaTurno(mappa);
        log.add("🔍 Stima avversari → " + avversariDebug(colore, mappa));

        // 9. Valuta e tenta sdadata
        SdadataResult sdadata = valutaSdadata(colore, obiettivoId, turno, mappa);
        if (sdadata != null) {
            if (sdadata.riuscita()) {
                log.add("🎲 SDADATA! " + sdadata.dado1() + "+" + sdadata.dado2() +
                        "=" + sdadata.totale() + " ≤ " + sdadata.soglia());
            } else {
                log.add("🎲 Sdadata fallita: " + sdadata.dado1() + "+" + sdadata.dado2() +
                        "=" + sdadata.totale() + " > " + sdadata.soglia());
            }
        }

        return new TurnoResult(log, sdadata);
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
        mano.subList(0, 3).clear();
    }

    private int trovaTris(List<Carta> mano) {
        if (mano.size() < 3) return 0;
        List<Carta> prime3 = mano.subList(0, 3);
        Map<Simbolo, Long> count = prime3.stream()
                .collect(Collectors.groupingBy(Carta::simbolo, Collectors.counting()));

        long jolly = count.getOrDefault(Simbolo.JOLLY, 0L);
        if (jolly >= 1) {
            boolean ha2Uguali = count.entrySet().stream()
                    .filter(e -> e.getKey() != Simbolo.JOLLY)
                    .anyMatch(e -> e.getValue() >= 2);
            if (ha2Uguali) return 12; // Jolly + 2 uguali
        }
        if (count.values().stream().anyMatch(v -> v >= 3)) return 8; // 3 uguali
        long tipiDiversi = count.entrySet().stream()
                .filter(e -> e.getKey() != Simbolo.JOLLY && e.getValue() >= 1).count();
        if (tipiDiversi >= 3) return 10; // Fante + Cannone + Cavallo
        return 0;
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

    private void piazzaRinforzi(String colore, int rinforzi, int obiettivoId,
                                List<String> miei, Map<String, TerritoryState> mappa,
                                List<String> log) {
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);

        List<String> confine = getTerritoriBordoNemico(colore, miei, mappa);
        if (confine.isEmpty()) confine = miei;

        int rim = rinforzi;

        // ── PRIORITÀ 0: territori con ≤ 2 armate → porta tutti a 3 ─────────────
        // Bersagli gratuiti per la pesca carte avversaria.
        List<String> debolissimi = confine.stream()
                .filter(t -> mappa.get(t).getArmate() <= MIN_ARMATE_DIFESA)
                .sorted(Comparator.comparingInt(t -> mappa.get(t).getArmate()))
                .collect(Collectors.toList());

        for (String t : debolissimi) {
            if (rim <= 0) break;
            int attuale      = mappa.get(t).getArmate();
            int daAggiungere = Math.min(3 - attuale, rim);
            if (daAggiungere > 0) {
                mappa.get(t).setArmate(attuale + daAggiungere);
                rim -= daAggiungere;
                log.add("🔒 " + lbl(colore) + " rinforza esposto " +
                        nom(t) + " (" + attuale + "→" + (attuale + daAggiungere) + ")");
            }
        }

        if (rim <= 0) {
            log.add("🛡 " + lbl(colore) + " piazza " + rinforzi + " armate");
            return;
        }

        // ── PRIORITÀ 1: territori 3-6 armate sotto pressione nemica ────────────
        // Per ogni territorio di confine calcola la pressione: max armate nemiche
        // adiacenti. Se il nemico ha abbastanza per attaccare (≥ 2x le mie armate),
        // il territorio è a rischio. I territori in obiettivo hanno soglia più bassa.
        //
        // Rinforzi assegnati per livello di rischio (cap: max 2 rinforzi per
        // territorio in questa fase, per non svuotare il budget):
        //   - In obiettivo sotto pressione forte (nemico ≥ 1.5x): +2
        //   - In obiettivo sotto pressione media (nemico ≥ 1.0x): +1
        //   - Fuori obiettivo sotto pressione forte (nemico ≥ 2.0x): +1

        // Calcola pressione per ogni territorio di confine
        record Pressione(String territorio, int mie, int maxNemica, boolean inObj) {}

        List<Pressione> pressioni = confine.stream()
                .filter(t -> {
                    int armate = mappa.get(t).getArmate();
                    return armate >= 3 && armate <= 6; // fascia a rischio
                })
                .map(t -> {
                    int mie = mappa.get(t).getArmate();
                    int maxNem = getConfinanti(t).stream()
                            .map(mappa::get)
                            .filter(st -> st != null && !colore.equals(st.getColore()))
                            .mapToInt(TerritoryState::getArmate)
                            .max().orElse(0);
                    return new Pressione(t, mie, maxNem, isInObiettivo(t, obj));
                })
                // Ordina: prima i più a rischio (rapporto nemico/mie più alto)
                .sorted(Comparator.comparingDouble(
                        (Pressione p) -> (double) p.maxNemica() / Math.max(1, p.mie())).reversed())
                .collect(Collectors.toList());

        for (Pressione p : pressioni) {
            if (rim <= 0) break;

            double rapporto = (double) p.maxNemica() / Math.max(1, p.mie());
            int daAggiungere = 0;

            if (p.inObj()) {
                // Territorio in obiettivo: difendo anche sotto pressione media
                if      (rapporto >= 1.5) daAggiungere = Math.min(2, rim);
                else if (rapporto >= 1.0) daAggiungere = Math.min(1, rim);
            } else {
                // Fuori obiettivo: intervengo solo sotto pressione forte (il nemico
                // ha già il doppio → può attaccare subito)
                if (rapporto >= 2.0) daAggiungere = Math.min(1, rim);
            }

            if (daAggiungere > 0) {
                int prima = mappa.get(p.territorio()).getArmate();
                mappa.get(p.territorio()).setArmate(prima + daAggiungere);
                rim -= daAggiungere;
                log.add("🛡 " + lbl(colore) + " difende " + nom(p.territorio()) +
                        " (" + prima + "→" + (prima + daAggiungere) +
                        ", pressione " + p.maxNemica() + " nem.)");
            }
        }

        if (rim <= 0) {
            log.add("🛡 " + lbl(colore) + " piazza " + rinforzi + " armate");
            return;
        }

        // ── PRIORITÀ 2-N: logica obiettivo / strategica / continenti ────────────
        String continenteDaDifendere = trovaContinenteDaDifendere(colore, mappa);
        List<String> ordine = new ArrayList<>();

        if (continenteDaDifendere != null) {
            List<String> terrCont = RisikoBoardData.CONTINENTI.get(continenteDaDifendere);
            confine.stream()
                    .filter(t -> terrCont != null && terrCont.contains(t))
                    .forEach(ordine::add);
        }

        confine.stream()
                .filter(t -> isInObiettivo(t, obj) && TERRITORI_STRATEGICI.contains(t))
                .forEach(ordine::add);

        confine.stream()
                .filter(t -> isInObiettivo(t, obj))
                .forEach(ordine::add);

        for (String cont : ORDINE_CONTINENTI) {
            if (!isInObiettivoContinente(cont, obj)) continue;
            List<String> terrCont = RisikoBoardData.CONTINENTI.getOrDefault(cont, List.of());
            confine.stream().filter(terrCont::contains).forEach(ordine::add);
        }

        ordine.addAll(confine);
        List<String> ord = ordine.stream().distinct().collect(Collectors.toList());
        if (ord.isEmpty()) ord = miei;

        String principale = ord.get(0);
        int iniziali = Math.min(3, rim);
        mappa.get(principale).setArmate(mappa.get(principale).getArmate() + iniziali);
        rim -= iniziali;

        int idx = 1;
        while (rim > 0) {
            String t = ord.get(idx % ord.size());
            mappa.get(t).setArmate(mappa.get(t).getArmate() + 1);
            rim--;
            idx++;
        }
        log.add("🛡 " + lbl(colore) + " piazza " + rinforzi + " armate");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ATTACCHI
    // ═══════════════════════════════════════════════════════════════════════════

    private void eseguiAttacchi(String colore, int obiettivoId,
                                Map<String, TerritoryState> mappa, List<String> log) {
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);

        // Calcola punteggio attuale dell'AI e degli avversari
        int punteggioMio = calcolaPunteggioAttuale(colore, obiettivoId, mappa);
        int punteggioMassAvversari = getMassimoPunteggioAvversari(colore, mappa);
        boolean sonoInVantaggio = punteggioMio > punteggioMassAvversari;

        // Se sono in vantaggio, limito a max 2 conquiste per poter sdadare
        int maxConquiste = sonoInVantaggio ? 2 : Integer.MAX_VALUE;

        for (int tentativo = 0; tentativo < 5; tentativo++) {
            List<String> miei = getTerritoriGiocatore(colore, mappa);

            // Non attaccare se troppo pochi territori
            if (miei.size() <= SOGLIA_MIN_TERRITORI) break;

            // Stop se ho già raggiunto il limite conquiste per sdadata
            if (territorConquistatiNelTurno.getOrDefault(colore, 0) >= maxConquiste
                    && sonoInVantaggio) break;

            AttaccoCandidate best = trovaMigliorAttacco(colore, miei, mappa, obj);
            if (best == null) break;

            boolean vinto = eseguiAttacco(best, mappa, log, colore);
            if (!vinto) break; // Se fallisce, smette di attaccare
        }
    }

    private record AttaccoCandidate(String da, String verso, int priorita) {}

    private AttaccoCandidate trovaMigliorAttacco(String colore, List<String> miei,
                                                 Map<String, TerritoryState> mappa,
                                                 ObiettivoTarget obj) {
        AttaccoCandidate best = null;

        for (String mio : miei) {
            int mieArmate = mappa.get(mio).getArmate();
            if (mieArmate <= MIN_ARMATE_DIFESA) continue;

            for (String nemico : getConfinanti(mio)) {
                TerritoryState st = mappa.get(nemico);
                if (st == null || colore.equals(st.getColore())) continue;

                int armateNemico = st.getArmate();

                // Rapporto minimo dipende dalla forza del difensore:
                //   difensore ≤ 4 → serve 2.5x  (es. 4 difesa → 10 attacco)
                //   difensore > 4 → serve 2x+3   (es. 6 difesa → 15 attacco)
                int minimoPerAttaccare = armateNemico <= 4
                        ? (int) Math.ceil(armateNemico * 2.5)
                        : armateNemico * 2 + 3;

                if (mieArmate < minimoPerAttaccare) continue;

                int p = calcolaPrioritaAttacco(nemico, colore, st.getColore(), obj, mappa);
                if (best == null || p > best.priorita())
                    best = new AttaccoCandidate(mio, nemico, p);
            }
        }
        return best;
    }

    private int calcolaPrioritaAttacco(String territorio, String colore,
                                       String coloreNemico, ObiettivoTarget obj,
                                       Map<String, TerritoryState> mappa) {
        int p = 0;

        // Priorità 1: blocca continente avversario quasi completato
        if (staBlocandoContinente(territorio, coloreNemico, mappa)) p += 200;

        // Priorità 2: territorio in obiettivo ad alto valore
        if (isInObiettivo(territorio, obj)) {
            p += 100;
            // Extra punti per valore (numero adiacenze = punti)
            p += getConfinanti(territorio).size() * 5;
        }

        // Priorità 3: territorio strategico
        if (TERRITORI_STRATEGICI.contains(territorio)) p += 40;

        // Priorità 4: continente più piccolo nell'obiettivo
        for (int i = 0; i < ORDINE_CONTINENTI.size(); i++) {
            String cont = ORDINE_CONTINENTI.get(i);
            List<String> terrCont = RisikoBoardData.CONTINENTI.getOrDefault(cont, List.of());
            if (terrCont.contains(territorio) && isInObiettivoContinente(cont, obj)) {
                p += (ORDINE_CONTINENTI.size() - i) * 10; // Oceania = priorità più alta
                break;
            }
        }

        // Malus: territorio con molte armate (rischioso)
        p -= mappa.get(territorio).getArmate() * 3;

        return p;
    }

    private boolean eseguiAttacco(AttaccoCandidate att, Map<String, TerritoryState> mappa,
                                  List<String> log, String colore) {
        TerritoryState da    = mappa.get(att.da());
        TerritoryState verso = mappa.get(att.verso());
        String difensore = verso.getColore();

        int totPerseAtt = 0;
        int totPerseDif = 0;

        // ── Multi-round con limite di sicurezza ───────────────────────────────
        // Max 50 round per battaglia: evita lentezze con eserciti molto grandi
        int round = 0;
        while (da.getArmate() > MIN_ARMATE_DIFESA && verso.getArmate() > 0 && round++ < 50) {
            int numAtt = Math.min(3, da.getArmate() - 1);
            int numDif = Math.min(3, verso.getArmate());

            int[] datiAtt = tiraDadiOrdinati(numAtt);
            int[] datiDif = tiraDadiOrdinati(numDif);

            int confronti = Math.min(numAtt, numDif);
            int perseAtt  = 0;
            int perseDif  = 0;

            for (int i = 0; i < confronti; i++) {
                if (datiAtt[i] > datiDif[i]) perseDif++;
                else perseAtt++;
            }

            da.setArmate(Math.max(MIN_ARMATE_DIFESA, da.getArmate() - perseAtt));
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

    private void eseguiSpostamento(String colore, int obiettivoId,
                                   Map<String, TerritoryState> mappa, List<String> log) {
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);
        List<String> miei = getTerritoriGiocatore(colore, mappa);
        List<String> confine = getTerritoriBordoNemico(colore, miei, mappa);
        if (confine.isEmpty()) return;

        // Trova territorio interno adiacente a un confine con molte armate da spostare
        for (String frontiera : confine) {
            // Cerca territorio interno adiacente con più armate
            Optional<String> interno = getConfinanti(frontiera).stream()
                    .filter(t -> colore.equals(mappa.getOrDefault(t,
                            new TerritoryState("?", 0)).getColore()))
                    .filter(t -> !confine.contains(t)) // è interno
                    .filter(t -> mappa.get(t).getArmate() > MIN_ARMATE_DIFESA + 1)
                    .max(Comparator.comparingInt(t -> mappa.get(t).getArmate()));

            if (interno.isEmpty()) continue;

            String fonte = interno.get();
            int qty = mappa.get(fonte).getArmate() - MIN_ARMATE_DIFESA;
            if (qty <= 0) continue;

            // Sposta solo verso frontiera in obiettivo o strategica
            boolean valePena = isInObiettivo(frontiera, obj)
                    || TERRITORI_STRATEGICI.contains(frontiera)
                    || staBlocandoContinente(frontiera, null, mappa);

            if (!valePena) continue;

            mappa.get(fonte).setArmate(MIN_ARMATE_DIFESA);
            mappa.get(frontiera).setArmate(mappa.get(frontiera).getArmate() + qty);
            log.add("🚀 " + lbl(colore) + " sposta " + qty + " da " +
                    nom(fonte) + " → " + nom(frontiera));
            break; // Un solo spostamento per turno
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
    private void gestisciCartina(String colore, int obiettivoId,
                                 Map<String, TerritoryState> mappa, List<String> log) {
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);
        List<String> miei = getTerritoriGiocatore(colore, mappa);
        List<Carta> mano = maniCarte.getOrDefault(colore, new ArrayList<>());

        int punteggioMio = calcolaPunteggioAttuale(colore, obiettivoId, mappa);
        int punteggioMax = getMassimoPunteggioAvversari(colore, mappa);
        boolean hoBisognoDiCarte = mano.size() < 3;
        boolean sonoLontanoDaObiettivo = punteggioMio < 40; // meno della metà di 86

        // ── RILEVA E ACCETTA/RIFIUTA CARTINA OFFERTA ─────────────────────────

        String cartinaTrovata = rilevaCarthina(colore, miei, mappa, obj);
        if (cartinaTrovata != null) {
            String partner = mappa.get(cartinaTrovata).getColore();
            boolean accetta = deveAccettareCartina(colore, partner, mano, mappa,
                    hoBisognoDiCarte, sonoLontanoDaObiettivo);
            if (accetta) {
                accettaCartina(colore, cartinaTrovata, partner, obiettivoId, mappa, log);
            } else {
                log.add("🤝 " + lbl(colore) + " rifiuta cartina di " + lbl(partner));
            }
        }

        // ── OFFRI CARTINA SE STRATEGICAMENTE UTILE ───────────────────────────

        if (hoBisognoDiCarte && sonoLontanoDaObiettivo && !accordiCartina.containsKey(colore)) {
            offriCartina(colore, obiettivoId, miei, mappa, log);
        }

        // ── RICAMBIA CARTINA SE IN ACCORDO ATTIVO ────────────────────────────

        if (accordiCartina.containsKey(colore)) {
            ricambiaCartina(colore, obiettivoId, miei, mappa, log);
        }

        // ── ROMPI ACCORDO SE NON SERVE PIÙ ───────────────────────────────────

        if (!hoBisognoDiCarte && mano.size() >= 3) {
            String partner = accordiCartina.remove(colore);
            if (partner != null) {
                log.add("🤝 " + lbl(colore) + " interrompe accordo con " + lbl(partner));
            }
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

        // Accetta preferibilmente con giocatore debole
        List<String> lorTerritori = getTerritoriGiocatore(partner, mappa);
        if (lorTerritori.size() > 15) return false; // troppo forte, rischioso

        // Accetta se il partner non ha un continente (meno pericoloso)
        boolean haContinente = RisikoBoardData.CONTINENTI.values().stream()
                .anyMatch(terrCont -> lorTerritori.containsAll(terrCont));

        return !haContinente || lorTerritori.size() < 12;
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

        // Registra accordo
        accordiCartina.put(colore, partner);
        territoriCartina.put(colore, territorio);
    }

    /**
     * Offre una cartina: sposta armate lasciando 2 in un territorio
     * fuori obiettivo adiacente al partner preferito.
     */
    private void offriCartina(String colore, int obiettivoId, List<String> miei,
                              Map<String, TerritoryState> mappa, List<String> log) {
        ObiettivoTarget obj = RisikoBoardData.OBIETTIVI.get(obiettivoId);

        // Trova il partner ideale: più debole e senza continente
        String partnerIdeale = trovaMigliorPartnerCartina(colore, mappa);
        if (partnerIdeale == null) return;

        // Trova territorio fuori obiettivo adiacente al partner, con più di 2 armate
        List<String> lorTerritori = getTerritoriGiocatore(partnerIdeale, mappa);
        Optional<String> territorioDaLasciare = miei.stream()
                .filter(t -> !isInObiettivo(t, obj))           // fuori obiettivo
                .filter(t -> !TERRITORI_STRATEGICI.contains(t)) // non strategico
                .filter(t -> mappa.get(t).getArmate() > MIN_ARMATE_DIFESA + 1) // ha abbastanza
                .filter(t -> getConfinanti(t).stream().anyMatch(lorTerritori::contains)) // adiacente al partner
                .max(Comparator.comparingInt(t -> mappa.get(t).getArmate()));

        if (territorioDaLasciare.isEmpty()) return;

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

        log.add("🤝 " + lbl(colore) + " offre cartina a " + lbl(partnerIdeale) +
                " su " + nom(terrCartina) + " (2 armate)");
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
        List<String> miei = getTerritoriGiocatore(colore, mappa);
        Set<String> adiacenti = miei.stream()
                .flatMap(t -> getConfinanti(t).stream())
                .map(t -> mappa.getOrDefault(t, new TerritoryState("?", 0)).getColore())
                .filter(c -> !colore.equals(c) && !"?".equals(c))
                .collect(Collectors.toSet());

        return adiacenti.stream()
                .filter(partner -> {
                    List<String> loro = getTerritoriGiocatore(partner, mappa);
                    // Non fare cartina con chi ha troppi territori (pericoloso)
                    if (loro.size() > 14) return false;
                    // Preferisce chi non ha un continente
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
     * - Soglie: T25≤4, T26≤5, T27≤6, T28+≤7
     * - L'AI tenta SOLO se ha punteggio > massimo avversario stimato
     * - Non può tentare se ha conquistato >2 territori nel turno corrente
     *
     * @return SdadataResult con esito del tentativo, oppure null se non tenta
     */
    public SdadataResult valutaSdadata(String colore, int obiettivoId, int turno,
                                       Map<String, TerritoryState> mappa) {
        // Condizione 0: sdadata disponibile solo dal turno 25
        if (turno < 25) return null;

        // Condizione 1: ha conquistato troppo → non può sdadare
        int conquiste = territorConquistatiNelTurno.getOrDefault(colore, 0);
        if (conquiste > 2) return null;

        // Condizione 2: deve essere in vantaggio di punteggio
        int mio = calcolaPunteggioAttuale(colore, obiettivoId, mappa);
        int max = getMassimoPunteggioAvversari(colore, mappa);
        if (mio <= max) return null;

        // Tentativo: tira 2 dadi
        int dado1  = RND.nextInt(6) + 1;
        int dado2  = RND.nextInt(6) + 1;
        int totale = dado1 + dado2;
        int soglia = switch (turno - 24) {  // turno 25 → fase 1, turno 26 → fase 2, ecc.
            case 1  -> 4;
            case 2  -> 5;
            case 3  -> 6;
            default -> 7;
        };

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
