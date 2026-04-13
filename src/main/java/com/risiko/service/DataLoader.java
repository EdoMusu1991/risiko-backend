package com.risiko.service;

import com.risiko.model.Obiettivo;
import com.risiko.repository.ObiettivoRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Carica i 16 obiettivi del Risiko Torneo nel database all'avvio.
 * I dati corrispondono esattamente al file dati_obbiettivi.txt fornito.
 */
@Component
public class DataLoader implements CommandLineRunner {

    private final ObiettivoRepository repo;

    public DataLoader(ObiettivoRepository repo) {
        this.repo = repo;
    }

    @Override
    public void run(String... args) {
        if (repo.count() > 0) return; // evita duplicati

        List<Obiettivo> obiettivi = List.of(

            new Obiettivo(1, "Letto", "obiettivo_01.jpg", List.of(
                "alaska","alberta","america_centrale","groenlandia","ontario","quebec",
                "stati_uniti_occidentali","stati_uniti_orientali","territori_del_nord_ovest",
                "argentina","brasile","peru","venezuela",
                "australia_occidentale","nuova_guinea","indonesia","siam","india","medio_oriente",
                "africa_del_nord","congo","egitto","africa_orientale"
            )),

            new Obiettivo(2, "Elefante", "obiettivo_02.jpg", List.of(
                "quebec","groenlandia","ontario","islanda","stati_uniti_orientali",
                "europa_occidentale","europa_meridionale","europa_settentrionale",
                "gran_bretagna","scandinavia","ucraina",
                "afghanistan","urali","medio_oriente",
                "africa_del_nord","egitto","congo","africa_orientale","africa_del_sud","madagascar"
            )),

            new Obiettivo(3, "Ciclista", "obiettivo_03.jpg", List.of(
                "europa_occidentale","europa_meridionale","europa_settentrionale",
                "gran_bretagna","islanda","scandinavia","ucraina",
                "afghanistan","urali","medio_oriente","india","siam",
                "australia_occidentale","australia_orientale","nuova_guinea","indonesia",
                "africa_del_nord","egitto","congo","africa_orientale","africa_del_sud","madagascar"
            )),

            new Obiettivo(4, "Giraffa", "obiettivo_04.jpg", List.of(
                "alaska","alberta","america_centrale","groenlandia","ontario","quebec",
                "stati_uniti_occidentali","stati_uniti_orientali","territori_del_nord_ovest",
                "europa_meridionale","europa_settentrionale","gran_bretagna","islanda","scandinavia","ucraina",
                "africa_del_nord","egitto","congo","africa_orientale","africa_del_sud","madagascar"
            )),

            new Obiettivo(5, "Granchio", "obiettivo_05.jpg", List.of(
                "alaska","alberta","america_centrale","groenlandia","ontario","quebec",
                "stati_uniti_occidentali","stati_uniti_orientali","territori_del_nord_ovest",
                "islanda","scandinavia","ucraina",
                "afghanistan","urali","medio_oriente","cina","india","siam",
                "australia_occidentale","australia_orientale","nuova_guinea","indonesia"
            )),

            new Obiettivo(6, "Formula1", "obiettivo_06.jpg", List.of(
                "europa_occidentale","europa_meridionale","europa_settentrionale",
                "gran_bretagna","islanda","scandinavia","ucraina",
                "afghanistan","medio_oriente","india","siam",
                "argentina","brasile","peru","venezuela",
                "australia_occidentale","australia_orientale","nuova_guinea","indonesia",
                "africa_del_nord","egitto","africa_orientale"
            )),

            new Obiettivo(7, "Befana", "obiettivo_07.jpg", List.of(
                "argentina","brasile","peru","venezuela",
                "africa_del_nord","egitto","congo","africa_orientale","africa_del_sud","madagascar",
                "afghanistan","urali","medio_oriente","india","siam","cina",
                "mongolia","jacuzia","cita","siberia","kamchatka","giappone","indonesia"
            )),

            new Obiettivo(8, "Elvis", "obiettivo_08.jpg", List.of(
                "alaska","alberta","america_centrale","groenlandia","ontario","quebec",
                "stati_uniti_occidentali","stati_uniti_orientali","territori_del_nord_ovest",
                "argentina","brasile","peru","venezuela",
                "europa_occidentale","europa_meridionale","europa_settentrionale",
                "gran_bretagna","islanda","scandinavia","ucraina",
                "kamchatka","giappone"
            )),

            new Obiettivo(9, "Dromedario con mosca", "obiettivo_09.jpg", List.of(
                "europa_occidentale","europa_meridionale","europa_settentrionale",
                "gran_bretagna","islanda","scandinavia","ucraina",
                "afghanistan","urali","medio_oriente","india","siam","cina",
                "mongolia","jacuzia","cita","siberia","kamchatka","giappone","indonesia"
            )),

            new Obiettivo(10, "Piovra", "obiettivo_10.jpg", List.of(
                "alaska","alberta","america_centrale","groenlandia","ontario","quebec",
                "stati_uniti_occidentali","stati_uniti_orientali","territori_del_nord_ovest",
                "europa_occidentale","europa_meridionale","europa_settentrionale",
                "gran_bretagna","islanda","scandinavia","ucraina",
                "urali","siberia","kamchatka","giappone","jacuzia"
            )),

            new Obiettivo(11, "Lupo (Siberiana)", "obiettivo_11.jpg", List.of(
                "europa_occidentale","europa_meridionale","europa_settentrionale",
                "gran_bretagna","islanda","scandinavia","ucraina",
                "siberia","urali","afghanistan","medio_oriente",
                "africa_del_nord","egitto","congo","africa_orientale","africa_del_sud","madagascar",
                "argentina","brasile","peru","venezuela"
            )),

            new Obiettivo(12, "Tappeto", "obiettivo_12.jpg", List.of(
                "africa_del_nord","egitto","congo","africa_orientale","africa_del_sud","madagascar",
                "afghanistan","urali","medio_oriente","india","siam","cina",
                "mongolia","jacuzia","cita","siberia","kamchatka","giappone","indonesia",
                "europa_meridionale","ucraina"
            )),

            new Obiettivo(13, "Guerra fredda", "obiettivo_13.jpg", List.of(
                "alaska","alberta","america_centrale","groenlandia","ontario","quebec",
                "stati_uniti_occidentali","stati_uniti_orientali","territori_del_nord_ovest",
                "afghanistan","urali","medio_oriente","india","siam","cina",
                "mongolia","jacuzia","siberia","cita","kamchatka","giappone"
            )),

            new Obiettivo(14, "Motorino", "obiettivo_14.jpg", List.of(
                "argentina","brasile","peru","venezuela",
                "africa_del_nord","egitto","congo","africa_orientale","africa_del_sud","madagascar",
                "australia_occidentale","australia_orientale","nuova_guinea","indonesia",
                "europa_occidentale","europa_meridionale","medio_oriente","india","siam","cina",
                "mongolia","cita","giappone"
            )),

            new Obiettivo(15, "Aragosta e pesciolino", "obiettivo_15.jpg", List.of(
                "alaska","alberta",
                "egitto","congo","africa_orientale","africa_del_sud","madagascar",
                "afghanistan","urali","medio_oriente","india","siam","cina",
                "mongolia","jacuzia","siberia","kamchatka","giappone","cita","indonesia",
                "australia_occidentale","australia_orientale","nuova_guinea"
            )),

            new Obiettivo(16, "Locomotiva", "obiettivo_16.jpg", List.of(
                "alaska","alberta","america_centrale","groenlandia","ontario","quebec",
                "stati_uniti_occidentali","stati_uniti_orientali","territori_del_nord_ovest",
                "argentina","brasile","peru","venezuela",
                "africa_del_nord","egitto","congo","africa_orientale","africa_del_sud","madagascar",
                "europa_occidentale","ucraina","europa_meridionale"
            ))
        );

        repo.saveAll(obiettivi);
        System.out.println("✅ Caricati " + obiettivi.size() + " obiettivi nel database.");
    }
}
