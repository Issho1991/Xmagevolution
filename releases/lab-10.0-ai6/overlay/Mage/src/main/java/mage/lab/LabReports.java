package mage.lab;

import java.text.DateFormat;
import java.util.*;

public final class LabReports {
    private LabReports() { }

    public static String compareGames(LabDeck a, LabDeck b, List<LabGame> games) {
        if (a.compositionId.equals(b.compositionId)) return "Stessa composizione: le ristampe non costituiscono due varianti di gioco.\n";
        SortedMap<String, int[][]> groups = new TreeMap<>();
        Map<String, String> descriptions = new HashMap<>();
        int excluded = 0;
        for (LabGame game : games) {
            boolean relevant = game.players.stream().anyMatch(p -> a.compositionId.equals(p.compositionId) || b.compositionId.equals(p.compositionId));
            if (!relevant) continue;
            if (!game.eligible()) { excluded++; continue; }
            for (LabGame.Participant player : game.players) {
                int variant = a.compositionId.equals(player.compositionId) ? 0 : b.compositionId.equals(player.compositionId) ? 1 : -1;
                if (variant < 0) continue;
                String key = game.context(player);
                int[][] counts = groups.computeIfAbsent(key, k -> new int[2][3]);
                descriptions.putIfAbsent(key, describeContext(game, player));
                int outcome = "WIN".equals(player.result) ? 0 : "DRAW".equals(player.result) ? 1 : "LOSS".equals(player.result) ? 2 : -1;
                if (outcome >= 0) counts[variant][outcome]++;
            }
        }
        StringBuilder text = new StringBuilder("RISULTATI OSSERVATI\n");
        text.append("Unità conteggiata: singola manche; le manche dello stesso incontro non sono prove indipendenti.\n");
        text.append("Separati per modalità, vite, avversari, piloti/livello IA, posti e versione del motore.\n")
                .append("Le partite sono osservazionali: nessun vincitore statistico o effetto causale dichiarato.\n")
                .append("Partite pertinenti incomplete/escluse: ").append(excluded).append("\n\n");
        if (groups.isEmpty()) return text.append("Nessuna partita completa confrontabile registrata. Gioca con le due varianti e aggiorna il laboratorio.\n").toString();
        int context = 0;
        for (Map.Entry<String, int[][]> group : groups.entrySet()) {
            text.append("Contesto ").append(++context).append(": ").append(descriptions.get(group.getKey())).append('\n');
            for (int variant = 0; variant < 2; variant++) {
                int[] c = group.getValue()[variant];
                text.append(variant == 0 ? "A: " : "B: ").append(c[0]).append(" vittorie, ").append(c[1]).append(" pareggi, ").append(c[2]).append(" sconfitte\n");
            }
            if (Arrays.stream(group.getValue()[0]).sum() == 0 || Arrays.stream(group.getValue()[1]).sum() == 0) {
                text.append("Manca una delle due varianti in questo contesto.\n");
            }
            text.append('\n');
        }
        return text.toString();
    }

    public static String game(LabGame game) {
        StringBuilder text = new StringBuilder("PARTITA ").append(game.id).append('\n');
        text.append(DateFormat.getDateTimeInstance().format(new Date(game.startedAt))).append(" · ")
                .append(game.mode).append(" · ").append(game.players.size()).append(" giocatori · ").append(game.startingLife).append(" vite\n")
                .append("Stato: ").append(status(game)).append('\n');
        text.append("Mano iniziale prevista: ").append(game.startingHandSize).append(" · pareggio della partita: ").append(game.drawn ? "sì" : "no").append('\n');
        if (game.endedAt > 0) text.append("Durata osservata: ").append((game.endedAt - game.startedAt) / 1000).append(" s (include attese umane)\n");
        if (!game.exclusions.isEmpty()) text.append("Esclusioni dal confronto: ").append(game.exclusions).append('\n');
        if (game.match == null) text.append("Incontro non registrato nello storico: nessun raggruppamento dedotto.\n");
        else text.append("Incontro ").append(game.match.id).append(" · manche ").append(game.match.round)
                .append(" · obiettivo ").append(game.match.winsNeeded).append(" vittorie\n");
        if (game.telemetry == null) text.append("Mulligan, eventi e tempi IA non disponibili in questa registrazione.\n");
        Map<String, String> names = new HashMap<>();
        for (LabGame.Participant player : game.players) {
            names.put(player.id, player.name);
            text.append("Posto ").append(player.seat).append(" · ").append(player.name).append(" · ").append(player.human ? "umano" : "IA")
                    .append(" · ").append(outcome(player.result)).append(" · ").append(player.deckName).append('\n')
                    .append("Revisione: ").append(shortId(player.revisionId)).append("\nPilota: ").append(pilot(player)).append('\n');
        }
        text.append("\nCAMPIONI DELLA PARTITA\nCampioni originali, anche di azioni poi annullate; per le osservazioni confermate usa Analisi incontri. Prima osservazione nella principale precombattimento e nella sottofase finale; non un replay completo.\n")
                .append("Le terre sul campo non misurano il mana producibile; una carta in mano non è necessariamente inutilizzabile.\n\n");
        for (LabGame.Sample sample : game.samples) {
            String phase = "PRECOMBAT_MAIN".equals(sample.phase) ? "Principale precombattimento" : "END_TURN".equals(sample.phase) ? "Sottofase finale" : "PRE_ELIMINATION".equals(sample.phase) ? "Prima dell’uscita di " + names.getOrDefault(sample.departingPlayer, sample.departingPlayer) : "Stato conclusivo";
            text.append("Turno globale ").append(sample.globalTurn).append(" · ").append(names.getOrDefault(sample.activePlayer, sample.activePlayer)).append(" · ").append(phase).append('\n');
            for (LabGame.Position position : sample.players) {
                text.append(names.getOrDefault(position.playerId, position.playerId)).append(Boolean.FALSE.equals(position.inGame) ? " [uscito; zone già ripulite]" : "").append(": vite ").append(position.life)
                        .append(", terre ").append(position.lands).append(", creature ").append(position.creatures)
                        .append(", mano ").append(position.handSize).append(", biblioteca ").append(position.librarySize)
                        .append(", cimitero ").append(position.graveyardSize).append('\n')
                        .append("  Carte in mano: ").append(position.hand).append('\n');
            }
            text.append('\n');
        }
        return text.toString();
    }

    public static String matches(List<LabGame> games) {
        Map<String, List<LabGame>> groups = new LinkedHashMap<>();
        int unlinked = 0;
        for (LabGame game : games) {
            if (game.match == null || game.match.id == null) { unlinked++; continue; }
            groups.computeIfAbsent(game.match.id, key -> new ArrayList<>()).add(game);
        }
        StringBuilder text = new StringBuilder("INCONTRI REGISTRATI\n")
                .append("Un incontro termina secondo l’obiettivo di vittorie scelto nel tavolo. In multiplayer può durare più di tre manche.\n")
                .append("Manche prive di identificativo incontro: ").append(unlinked)
                .append(". Lo storico precedente non viene raggruppato per orario, nomi o mazzi.\n\n");
        if (groups.isEmpty()) text.append("Nessun incontro collegato. I nuovi incontri verranno registrati dal server Laboratorio 2.\n");
        for (Map.Entry<String, List<LabGame>> entry : groups.entrySet()) {
            List<LabGame> rounds = entry.getValue();
            rounds.sort(Comparator.comparingInt(g -> g.match.round));
            LabGame last = rounds.get(rounds.size() - 1);
            LabGame.MatchInfo info = last.match;
            boolean complete = info.finished && info.resultRecorded;
            boolean clean = complete;
            int expected = 1;
            Map<String, Integer> previous = null;
            for (LabGame round : rounds) {
                LabGame.MatchInfo m = round.match;
                if (m.round != expected++ || m.winsNeeded != info.winsNeeded || !m.resultRecorded
                        || (previous != null && !previous.equals(m.scoreBefore))) complete = false;
                if (m.round == 1 && m.scoreBefore.values().stream().anyMatch(score -> score != 0)) complete = false;
                if (!round.eligible()) clean = false;
                previous = m.scoreAfter;
            }
            boolean reachedTarget = info.winners.stream().anyMatch(id -> info.scoreAfter.getOrDefault(id, 0) >= info.winsNeeded);
            clean &= complete && reachedTarget;
            text.append("INCONTRO ").append(entry.getKey()).append(" · primo a ").append(info.winsNeeded).append(" vittorie\n")
                    .append("Manche registrate: ").append(rounds.size()).append(" · ")
                    .append(info.finished ? "Chiuso dal motore" : "In corso / chiusura non registrata").append('\n');
            text.append("Copertura: ").append(complete ? "tutte le manche e punteggio finale" : "incompleta o incoerente")
                    .append(" · ").append(clean ? "utilizzabile per confronti fra incontri" : "non utilizzabile per confronti fra incontri").append('\n');
            Map<String, Integer> scores = info.resultRecorded ? info.scoreAfter : info.scoreBefore;
            text.append(info.resultRecorded ? "Ultimo punteggio confermato: " : "Punteggio prima dell’ultima manche: ");
            scores.forEach((id, score) -> text.append(info.names.getOrDefault(id, id)).append(' ').append(score).append("; "));
            text.append('\n');
            if (info.finished && !reachedTarget) text.append("Chiusura senza raggiungere l’obiettivo di vittorie (per esempio un abbandono).\n");
            for (String winner : info.winners) text.append("Vincitore dell’incontro: ").append(info.names.getOrDefault(winner, winner)).append('\n');
            for (LabGame round : rounds) {
                text.append("  Manche ").append(round.match.round).append(" · ").append(round.startingLife).append(" vite · ");
                for (LabGame.Participant p : round.players) if ("WIN".equals(p.result)) text.append(p.name).append(" vince; ");
                if (round.drawn) text.append("pareggio; ");
                text.append(status(round)).append(" · ").append(round.id).append('\n');
            }
            text.append('\n');
        }
        return text.toString();
    }

    public static String telemetry(LabGame game, List<LabGame.Event> events) {
        if (game.telemetry == null) return "";
        LabGame.Telemetry data = game.telemetry;
        LabTrace trace = LabTrace.select(game, events);
        Map<String, String> names = new HashMap<>();
        for (LabGame.Participant p : game.players) names.put(p.id, p.name);
        StringBuilder text = new StringBuilder("\nMULLIGAN, EVENTI E TEMPI IA\n");
        text.append("Traccia: ").append(data.complete ? "registrazione conclusa" : "parziale / registrazione non conclusa")
                .append(" · eventi leggibili: ").append(events.size()).append('\n');
        if (data.events != events.size() && !"IN_PROGRESS".equals(game.status)) text.append("ATTENZIONE: il numero di eventi leggibili differisce dal totale salvato.\n");
        for (String warning : data.warnings) text.append(warning).append('\n');
        if (!game.exclusions.isEmpty()) text.append("Eventi osservati in una manche esclusa dai confronti: ").append(game.exclusions).append('\n');
        text.append("\nTEMPI IA\nTempo trascorso nelle chiamate di priorità, scelta attaccanti e bloccanti; comprende le attese interne al calcolo.\n")
                .append("Le chiamate dei piloti umani e dei giocatori controllati da altri sono escluse, così come le altre operazioni del motore. Non è una misura del solo utilizzo CPU.\n")
                .append("Totali di tutte le chiamate; nella cronologia sono elencate quelle di almeno 100 ms e le discontinuità degli orologi.\n")
                .append(LabDiagnostics.TIMING_NOTE).append('\n');
        if ("IN_PROGRESS".equals(game.status)) text.append("I totali definitivi vengono salvati alla fine della manche.\n");
        for (LabGame.Timing timing : data.ai.values()) {
            text.append(names.getOrDefault(timing.playerId, timing.playerId)).append(" · ").append(label(timing.operation))
                    .append(" · ").append(LabDiagnostics.timing(timing)).append(" · almeno 100 ms: ").append(timing.slowCalls).append('\n');
        }
        for (String note : trace.notes) text.append(note).append('\n');
        text.append("\nMANI E MULLIGAN\nLe mani pescate precedono eventuali carte rimesse in fondo; la mano tenuta è quella dopo la scelta finale.\n");
        int hands = 0;
        for (LabGame.Event event : trace.events) if (event.kind.startsWith("MULLIGAN_")) {
            hands++;
            text.append('#').append(event.sequence).append(" · ").append(names.getOrDefault(event.playerId, event.playerId))
                    .append(" · ").append(label(event.kind)).append(" · ").append(event.amount).append(" carte: ").append(event.hand).append('\n');
        }
        if (hands == 0) text.append("Nessuna mano di mulligan disponibile nella traccia.\n");
        text.append("\nEVENTI PER CARTA DURANTE IL GIOCO\nConteggi descrittivi: una magia lanciata può essere neutralizzata; entrare sul campo non implica essere stata lanciata.\n")
                .append("Nessun punteggio di utilità o causa della vittoria viene dedotto da questi conteggi.\n");
        Map<String, SortedMap<String, Integer>> cards = new TreeMap<>();
        for (LabGame.Event event : trace.events) {
            if (!"PLAY".equals(event.stage)) continue;
            String action = null;
            if (Arrays.asList("DREW_CARD", "SPELL_CAST", "LAND_PLAYED", "DISCARDED_CARD", "MILLED_CARD").contains(event.kind)) action = label(event.kind);
            if ("ZONE_CHANGE".equals(event.kind) && "BATTLEFIELD".equals(event.to)) action = "ingressi sul campo";
            if ("ZONE_CHANGE".equals(event.kind) && "BATTLEFIELD".equals(event.from)) action = "uscite dal campo";
            if (action == null) continue;
            String card = event.targetName != null ? event.targetName : "SPELL_CAST".equals(event.kind) ? event.sourceName : null;
            String key = names.getOrDefault(event.playerId, "Giocatore non disponibile") + " · " + (card == null ? "Nome non disponibile" : card);
            cards.computeIfAbsent(key, k -> new TreeMap<>()).merge(action, 1, Integer::sum);
        }
        if (!trace.completeEvents && !trace.partialEvents) text.append("Conteggi omessi: copertura non confermata. Consultare la cronologia osservata.\n");
        if (trace.partialEvents) text.append("COPERTURA PARZIALE: conteggi del solo tratto successivo all’ultimo ripristino, non dell’intera manche.\n");
        cards.forEach((card, counts) -> text.append(card).append(": ").append(counts).append('\n'));
        text.append("\nCRONOLOGIA DEGLI EVENTI SELEZIONATI\nCambi zona, pescate, scarti, macine, magie lanciate, terre giocate, danni ai giocatori e variazioni di vita.\n")
                .append("Danni e perdita di vite possono descrivere lo stesso effetto: non sommare le due categorie. Non è un replay completo.\n");
        Set<Long> used = new HashSet<>();
        for (LabGame.Event event : trace.events) used.add(event.sequence);
        text.append("La cronologia conserva anche azioni annullate o non confermate, etichettate quando non utilizzate nei conteggi.\n");
        int first = Math.max(0, events.size() - 2000);
        if (first > 0) text.append("Report limitato agli ultimi 2000 eventi; la traccia salvata contiene ").append(events.size()).append(" eventi.\n");
        for (int i = first; i < events.size(); i++) {
            LabGame.Event event = events.get(i);
            text.append('#').append(event.sequence).append(" +").append(event.elapsedMillis).append(" ms · turno ").append(event.globalTurn)
                    .append(" · ").append(event.phase).append(" · ").append(names.getOrDefault(event.playerId, "—"))
                    .append(" · ").append(label(event.kind));
            if (!used.contains(event.sequence) && !event.kind.startsWith("AI_") && !"STATE_RESTORED".equals(event.kind)) text.append(" · [non utilizzato nei conteggi]");
            if (event.restoredAfterEvent != null) text.append(" · ripristino dopo evento #").append(event.restoredAfterEvent)
                    .append(" / campione #").append(event.restoredAfterSample).append(" · ").append(event.restoreKind);
            if (event.restoreContext != null) text.append(" · contesto: ").append(event.restoreContext);
            if (event.targetName != null) text.append(" · ").append(event.targetName);
            else if (event.targetId != null) text.append(" · ").append(names.getOrDefault(event.targetId, event.targetId));
            if (event.sourceName != null && !event.sourceName.equals(event.targetName)) text.append(" · fonte: ").append(event.sourceName);
            if (event.from != null || event.to != null) text.append(" · ").append(event.from).append(" → ").append(event.to);
            if (event.amount != 0) text.append(" · quantità ").append(event.amount);
            if (event.combat != null) text.append(event.combat ? " · combattimento" : " · non da combattimento");
            if (event.durationNanos != 0) text.append(" · trascorso ").append(seconds(event.durationNanos)).append(" s");
            if (event.kind.startsWith("AI_")) {
                text.append(" · CPU thread: ").append(event.cpuNanos == null ? "non disponibile" : seconds(event.cpuNanos) + " s");
                if (event.wallMillis != null) text.append(" · orologio civile ").append(event.wallMillis).append(" ms");
                if (event.responseWaitNanos != null) text.append(" · attesa risposta ").append(seconds(event.responseWaitNanos)).append(" s");
                if (Boolean.TRUE.equals(event.clockDiscontinuity)) text.append(" · discontinuità degli orologi (sospensione o correzione possibile)");
                if (Boolean.TRUE.equals(event.longNonCpu)) text.append(" · intervallo lungo con poco tempo CPU: causa da verificare");
            }
            if ("PLAYER_EXIT".equals(event.kind)) text.append(" · ").append(LabDiagnostics.exit(event.exitReason, event.exitSource))
                    .append(" · vite ").append(event.life == null ? "non registrate" : event.life).append(" · ").append(LabDiagnostics.poison(event.poison));
            text.append('\n');
        }
        return text.toString();
    }

    private static String seconds(long nanos) { return String.format(Locale.ITALY, "%.3f", nanos / 1.0e9); }
    private static String label(String kind) {
        switch (kind) {
            case "PRIORITY": case "AI_PRIORITY": return "priorità IA";
            case "ATTACK": case "AI_ATTACK": return "attaccanti IA";
            case "BLOCK": case "AI_BLOCK": return "bloccanti IA";
            case "PLAYER_EXIT": return "uscita dalla manche";
            case "MULLIGAN_HAND": return "mano pescata";
            case "MULLIGAN_KEEP": return "mano tenuta";
            case "MULLIGAN_REJECT": return "mano rifiutata";
            case "DREW_CARD": return "pescate";
            case "SPELL_CAST": return "magie lanciate";
            case "LAND_PLAYED": return "terre giocate";
            case "DISCARDED_CARD": return "scarti";
            case "MILLED_CARD": return "carte macinate";
            case "STATE_RESTORED": return "stato ripristinato / azioni annullate";
            case "ZONE_CHANGE": return "cambio zona";
            case "DAMAGED_PLAYER": return "danni al giocatore";
            case "GAINED_LIFE": return "vite guadagnate";
            case "LOST_LIFE": return "vite perse";
            default: return kind;
        }
    }

    private static String describeContext(LabGame game, LabGame.Participant subject) {
        StringBuilder text = new StringBuilder(game.mode).append(" · ").append(game.players.size()).append(" giocatori · ")
                .append(game.startingLife).append(" vite · mano iniziale ").append(game.startingHandSize)
                .append(" · posto ").append(subject.seat).append(" · ").append(pilot(subject)).append('\n');
        for (LabGame.Participant opponent : game.players) {
            if (!opponent.id.equals(subject.id)) text.append("Avversario al posto ").append(opponent.seat).append(": ")
                    .append(opponent.deckName == null ? "Mazzo" : opponent.deckName).append(" [").append(shortId(opponent.compositionId)).append("] · ")
                    .append(pilot(opponent)).append('\n');
            if (opponent.id.equals(game.startingPlayer)) text.append("Inizia il giocatore al posto ").append(opponent.seat).append('.').append('\n');
        }
        text.append("Mulligan: ").append(game.mulligan).append(" · gratuiti ").append(game.freeMulligans)
                .append(" · raggio ").append(game.range).append(" · attacchi ").append(game.attack).append('\n').append(game.build);
        return text.toString();
    }

    private static String pilot(LabGame.Participant player) {
        if (player.human) return "Umano · " + player.name;
        String profile = String.valueOf(player.pilot);
        String[] parts = profile.split(";", 2);
        String name = parts[0].substring(parts[0].lastIndexOf('.') + 1);
        return name + (parts.length == 2 ? " · " + parts[1].trim().replace("depth=", "profondità ").replace("nodes=", "nodi ").replace("seconds=", "secondi ") : "");
    }

    private static String shortId(String id) { return id == null ? "non disponibile" : id.substring(0, Math.min(8, id.length())); }
    private static String outcome(String value) {
        return "WIN".equals(value) ? "Vittoria" : "DRAW".equals(value) ? "Pareggio" : "LOSS".equals(value) ? "Sconfitta" : "Da determinare";
    }

    public static String status(LabGame game) {
        if ("COMPLETED".equals(game.status)) return game.exclusions.isEmpty() ? "Conclusa" : "Conclusa, esclusa dai confronti";
        if ("INTERRUPTED".equals(game.status)) return "Interrotta";
        return "In corso / senza risultato finale";
    }
}
