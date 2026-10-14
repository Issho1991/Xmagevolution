package mage.lab;

import java.io.IOException;
import java.util.*;

/** Offline interpretation of archived observations. No engine state or card evaluation is requested. */
public final class LabReview {
    public final String title;
    public final String overview;
    public final List<PlayerReview> players;

    private LabReview(String title, String overview, List<PlayerReview> players) {
        this.title = title; this.overview = overview; this.players = players;
    }

    public static final class CardReview {
        public final String name;
        public Integer deckCopies;
        public int openingCopies, drawn, cast, landsPlayed, discarded, milled, endSamples, longestRun;
        public int evidenceOmitted;
        public String runReference;
        public final List<String> evidence = new ArrayList<>();
        private CardReview(String name) { this.name = name; }
        private void add(String value) {
            if (evidence.size() < 24) evidence.add(value); else evidenceOmitted++;
        }
        public String describe(PlayerReview owner) {
            StringBuilder text = new StringBuilder(name).append("\n").append(owner).append('\n');
            text.append("Copie nella lista principale: ").append(deckCopies == null ? "lista non disponibile" : deckCopies).append('\n');
            if (deckCopies != null && deckCopies == 0) text.append("Nome assente dalla lista principale: può essere una carta acquisita, una copia, una pedina o una faccia diversa.\n");
            text.append("Manche con eventi completi: ").append(owner.eventGames).append('/').append(owner.games).append(" · con tratto finale recuperato: ").append(owner.partialEventGames).append('\n');
            text.append("Copie nelle mani tenute: ").append(owner.keeps == 0 ? "non disponibile" : openingCopies)
                    .append(" (mani tenute registrate: ").append(owner.keeps).append(")\n")
                    .append("Pescate durante il gioco: ").append(metric(drawn, owner.eventGames + owner.partialEventGames)).append('\n')
                    .append("Lanci: ").append(metric(cast, owner.eventGames + owner.partialEventGames)).append(" · terre giocate: ").append(metric(landsPlayed, owner.eventGames + owner.partialEventGames)).append('\n')
                    .append("Scarti: ").append(metric(discarded, owner.eventGames + owner.partialEventGames)).append(" · carte macinate: ").append(metric(milled, owner.eventGames + owner.partialEventGames)).append('\n')
                    .append("Presenza nei campioni di fine turno del pilota: ").append(owner.endSamples == 0 ? "non disponibile" : endSamples + "/" + owner.endSamples)
                    .append(" · serie massima di turni propri consecutivi: ").append(owner.endSamples == 0 ? "non disponibile" : longestRun).append('\n');
            text.append("Le presenze riguardano il nome della carta: copie diverse possono comparire in campioni successivi.\n")
                    .append("Le pescate escludono la preparazione iniziale e i mulligan. Un lancio non garantisce la risoluzione della magia.\n");
            if (endSamples == 0 && drawn == 0 && cast == 0 && landsPlayed == 0 && discarded == 0 && milled == 0 && openingCopies == 0)
                text.append("Nessuna osservazione di questa carta nei dati utilizzabili: non è un giudizio sulla sua utilità.\n");
            if (owner.partialEventGames > 0) text.append("COPERTURA PARZIALE: questi conteggi includono soltanto i tratti confermati indicati nella sintesi, non tutte le azioni dell’incontro.\n");
            if (runReference != null) text.append("Riferimento della serie massima: ").append(runReference).append('\n');
            if (longestRun >= 3) {
                text.append("\nFATTO: nome presente in almeno tre campioni di fine turno propri consecutivi.\n")
                        .append("IPOTESI DA VERIFICARE: riserva intenzionale, copie diverse dello stesso nome o vincoli di utilizzo; per le terre può incidere anche il limite di giocate.\n")
                        .append("PROVA: confronta mani ed eventi dei turni citati per capire quali copie sono state usate o conservate. La presenza nei campioni non dimostra che la carta non venisse usata. Valuta uno scambio solo se emerge un problema ricorrente rispetto al piano del mazzo.\n");
            }
            text.append("\nRIFERIMENTI\n");
            if (evidence.isEmpty()) text.append("Nessun riferimento disponibile.\n");
            for (String item : evidence) text.append("- ").append(item).append('\n');
            if (evidenceOmitted > 0) text.append(evidenceOmitted).append(" ulteriori riferimenti omessi dalla scheda; restano nelle registrazioni.\n");
            return text.toString();
        }
    }

    public static final class PlayerReview {
        public String playerId, name, revisionId, deckName, pilot;
        public int games, eligibleGames, eventGames, partialEventGames, endSamples, keeps, mulligans;
        public final SortedMap<String, CardReview> cards = new TreeMap<>();
        public final List<String> observations = new ArrayList<>();
        public final List<String> checks = new ArrayList<>();
        public final List<String> gaps = new ArrayList<>();
        private CardReview card(String name) {
            return cards.computeIfAbsent(name, CardReview::new);
        }
        @Override public String toString() { return name + " · " + value(deckName) + " [" + shortId(revisionId) + "] · " + pilotLabel(pilot); }
        public String summary() {
            StringBuilder text = new StringBuilder(toString()).append("\nPilota: ").append(value(pilot)).append('\n')
                    .append("Manche: ").append(games).append(" · ammesse ai confronti: ").append(eligibleGames)
                    .append(" · con eventi completi: ").append(eventGames).append(" · con eventi parziali recuperati: ").append(partialEventGames).append(" · campioni di fine turno propri: ").append(endSamples).append('\n')
                    .append("Mani tenute registrate: ").append(keeps).append(" · rifiuti di mulligan: ").append(eventGames == 0 ? "non disponibile" : mulligans).append('\n');
            text.append("\nFATTI OSSERVATI\n");
            for (String item : observations) text.append("- ").append(item).append('\n');
            text.append("\nSEGNALI E VERIFICHE PROPOSTE\n");
            if (endSamples == 0 && eventGames + partialEventGames == 0) text.append("Dati insufficienti per selezionare segnali sulle carte.\n");
            else if (checks.isEmpty() && cards.values().stream().noneMatch(card -> card.longestRun >= 3)) text.append("Nessun segnale selezionato dalle regole di questo report; non significa che il mazzo sia privo di problemi.\n");
            for (String item : checks) text.append("- ").append(item).append('\n');
            for (CardReview card : cards.values()) if (card.longestRun >= 3) text.append("- ").append(card.name)
                    .append(": presente in ").append(card.longestRun).append(" campioni propri consecutivi. Consulta la scheda per riferimenti e verifica proposta.\n");
            if (!gaps.isEmpty()) {
                text.append("\nDATI NON UTILIZZATI O MANCANTI\n");
                for (String gap : gaps) text.append("- ").append(gap).append('\n');
            }
            return text.toString();
        }
    }

    public static LabReview create(List<LabGame> input, LabStore store) throws IOException {
        if (input.isEmpty()) throw new IOException("Nessuna manche da analizzare.");
        List<LabGame> games = new ArrayList<>(input);
        boolean linked = games.get(0).match != null && games.get(0).match.id != null;
        String id = linked ? games.get(0).match.id : games.get(0).id;
        Set<String> ids = new HashSet<>();
        for (LabGame game : games) {
            if (!ids.add(game.id)) throw new IOException("Manche duplicata: " + game.id);
            if (linked ? game.match == null || !id.equals(game.match.id) : games.size() != 1)
                throw new IOException("Seleziona un unico incontro; le manche storiche senza collegamento si analizzano singolarmente.");
        }
        games.sort(Comparator.comparingInt(g -> g.match == null ? 0 : g.match.round));
        String title = linked ? "REPORT INCONTRO " + id : "REPORT MANCHE " + id + " · incontro non registrato";
        StringBuilder overview = new StringBuilder(title).append("\nAnalisi delle osservazioni · Laboratorio\n")
                .append("Fuso degli orari: ").append(TimeZone.getDefault().getID()).append("\n\n")
                .append(linked ? LabReports.matches(games) : LabReports.game(games.get(0)).split("CAMPIONI DELLA PARTITA", 2)[0]);
        overview.append("\nLe revisioni diverse dello stesso pilota sono analizzate separatamente. L’ammissione ai confronti è distinta dal recupero di osservazioni verificate; ogni recupero parziale è dichiarato.\n")
                .append("Le carte sono descritte attraverso eventi e campioni disponibili, senza un punteggio universale o una causa della vittoria dedotta.\n")
                .append("La prova riguarda mazzo, pilota e avversari: questi dati non separano da soli la forza del mazzo dalla qualità delle scelte dell’IA.\n");
        Map<String, PlayerReview> byRevision = new LinkedHashMap<>();
        Map<String, SortedMap<String, Integer>> deckCache = new HashMap<>();
        for (LabGame game : games) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Analisi annullata");
            LabTrace trace = LabTrace.read(game, store);
            for (LabGame.Participant participant : game.players) {
                String key = participant.id + ":" + (participant.revisionId == null ? "unknown:" + game.id : participant.revisionId) + ":" + participant.pilot;
                PlayerReview review = byRevision.get(key);
                if (review == null) {
                    review = new PlayerReview(); review.playerId = participant.id; review.name = participant.name;
                    review.revisionId = participant.revisionId; review.deckName = participant.deckName; review.pilot = participant.pilot;
                    byRevision.put(key, review);
                    if (participant.revisionId != null) try {
                        SortedMap<String, Integer> deck = deckCache.get(participant.revisionId);
                        if (deck == null) {
                            deck = LabDeck.counts(store.deck(participant.revisionId).deck.getCards());
                            deckCache.put(participant.revisionId, deck);
                        }
                        for (Map.Entry<String, Integer> entry : deck.entrySet()) review.card(entry.getKey()).deckCopies = entry.getValue();
                    } catch (IOException | RuntimeException e) { review.gaps.add("Lista archiviata non disponibile: " + e.getMessage()); }
                }
                review.games++;
                String ref = ref(game);
                review.observations.add(ref + ": " + outcome(participant.result) + "; " + game.startingLife + " vite iniziali.");
                if (game.eligible()) review.eligibleGames++;
                for (String note : trace.notes) review.gaps.add(ref + ": " + note);
                if (trace.completeEvents || trace.partialEvents) {
                    if (trace.completeEvents) review.eventGames++; else review.partialEventGames++;
                    events(game, review, trace.events);
                }
                samples(game, review, trace.samples);
                LabGame.Event exit = null;
                for (LabGame.Event e : trace.events) if (participant.id.equals(e.playerId) && "PLAYER_EXIT".equals(e.kind)) exit = e;
                if (exit != null) review.observations.add(ref + ": uscita registrata dal motore: " + LabDiagnostics.exit(exit.exitReason, exit.exitSource)
                        + (exit.life == null ? "" : "; vite " + exit.life) + "; " + LabDiagnostics.poison(exit.poison) + ".");
                if (game.telemetry != null) {
                    boolean hasTiming = false;
                    for (LabGame.Timing timing : game.telemetry.ai.values()) if (participant.id.equals(timing.playerId) && timing.calls > 0) {
                        hasTiming = true;
                        review.observations.add(ref + ": IA, " + timing.operation + ": " + LabDiagnostics.timing(timing));
                    }
                    if (hasTiming) review.observations.add(LabDiagnostics.TIMING_NOTE);
                }
                SortedMap<String, Integer> deck = deckCache.get(participant.revisionId);
                if (deck != null) for (CardReview card : review.cards.values()) if (card.deckCopies == null) card.deckCopies = 0;
            }
        }
        return new LabReview(title, overview.toString(), new ArrayList<>(byRevision.values()));
    }

    private static void samples(LabGame game, PlayerReview review, List<LabGame.Sample> samples) {
        Map<String, Integer> runs = new HashMap<>();
        int previousTurn = -1;
        LabGame.Position previous = null, lastOwn = null, departure = null;
        LabGame.Sample departureSample = null;
        Set<Integer> seen = new HashSet<>();
        int earlyNoLand = 0, empty = 0;
        List<String> noLandRefs = new ArrayList<>(), emptyRefs = new ArrayList<>();
        String bestGrowth = null;
        int growth = 0;
        for (LabGame.Sample sample : samples) {
            LabGame.Position position = null;
            for (LabGame.Position p : sample.players) if (review.playerId.equals(p.playerId)) { position = p; break; }
            if (position == null) continue;
            if ("PRE_ELIMINATION".equals(sample.phase) && review.playerId.equals(sample.departingPlayer)) { departure = position; departureSample = sample; }
            if (!"END_TURN".equals(sample.phase) || !review.playerId.equals(sample.activePlayer) || Boolean.FALSE.equals(position.inGame)
                    || position.ownTurns < 1 || !seen.add(position.ownTurns)) continue;
            if (position.ownTurns != previousTurn + 1) runs.clear();
            review.endSamples++;
            String ref = ref(game) + ", tuo turno " + position.ownTurns + " (globale " + sample.globalTurn + ")";
            for (String name : new ArrayList<>(runs.keySet())) if (!position.hand.containsKey(name) || position.hand.get(name) <= 0) runs.remove(name);
            for (Map.Entry<String, Integer> hand : position.hand.entrySet()) if (hand.getValue() > 0) {
                CardReview card = review.card(hand.getKey()); card.endSamples++;
                int run = runs.getOrDefault(card.name, 0) + 1; runs.put(card.name, run);
                if (run > card.longestRun) { card.longestRun = run; card.runReference = ref + "; serie dal tuo turno " + (position.ownTurns - run + 1) + "."; }
                card.add(ref + ": " + hand.getValue() + " in mano; terre " + position.lands + ", creature " + position.creatures + ".");
            }
            if (position.ownTurns <= 4 && position.landsPlayed == 0) { earlyNoLand++; noLandRefs.add("T" + position.ownTurns); }
            if (position.handSize == 0) { empty++; emptyRefs.add("T" + position.ownTurns); }
            if (previous != null && position.ownTurns == previousTurn + 1 && position.creatures - previous.creatures > growth) {
                growth = position.creatures - previous.creatures;
                bestGrowth = ref + ": creature sul campo " + previous.creatures + " → " + position.creatures + " rispetto al precedente campione proprio.";
            }
            review.observations.add(ref + ": vite " + position.life + ", terre " + position.lands + ", creature " + position.creatures
                    + ", mano " + position.handSize + ", biblioteca " + position.librarySize + "; terre giocate nel turno " + position.landsPlayed + ".");
            previousTurn = position.ownTurns; previous = position; lastOwn = position;
        }
        if (bestGrowth != null) review.observations.add("Sviluppo del campo: " + bestGrowth + " È un conteggio, non una misura di forza del campo.");
        if (earlyNoLand >= 2) review.checks.add("FATTO — " + ref(game) + ": nessuna terra giocata in " + noLandRefs
                + " nei primi quattro turni propri osservati. IPOTESI — disponibilità o scelta di gioco. PROVA — controlla se avevi terre utilizzabili e perché non le hai giocate; il numero di terre sul campo non misura il mana disponibile.");
        if (empty >= 2) review.checks.add("FATTO — " + ref(game) + ": mano vuota nei campioni " + emptyRefs
                + ". IPOTESI — risorse esaurite oppure piano rapido intenzionale. PROVA — verifica cosa potevi fare nei turni successivi prima di valutare una fonte di nuove carte.");
        if (departure != null) review.observations.add(ref(game) + ": prima dell’uscita, vite " + departure.life + ", terre " + departure.lands
                + ", creature " + departure.creatures + ", mano " + departure.handSize + " " + departure.hand + "; " + LabDiagnostics.poison(departure.poison)
                + "; " + LabDiagnostics.exit(departureSample.exitReason, departureSample.exitSource) + ". La causa dell’uscita non è dedotta.");
        else if (lastOwn != null) review.observations.add(ref(game) + ": nessuno stato prima dell’uscita disponibile; l’ultimo campione proprio è T" + lastOwn.ownTurns + ".");
    }

    private static void events(LabGame game, PlayerReview review, List<LabGame.Event> events) {
        Map<String, String> observedObjects = new HashMap<>();
        for (LabGame.Event event : events) if (review.playerId.equals(event.playerId)) {
            if (event.handCards != null) observedObjects.putAll(event.handCards);
            if ("DREW_CARD".equals(event.kind) || "LAND_PLAYED".equals(event.kind) || "DISCARDED_CARD".equals(event.kind)) {
                if (event.targetId != null && event.targetName != null) observedObjects.put(event.targetId, event.targetName);
            }
            if ("SPELL_CAST".equals(event.kind)) {
                String name = event.targetName == null ? event.sourceName : event.targetName;
                if (name != null) {
                    if (event.targetId != null) observedObjects.put(event.targetId, name);
                    if (event.sourceId != null) observedObjects.put(event.sourceId, name);
                }
            }
        }
        for (LabGame.Event event : events) if ("PLAY".equals(event.stage) && "ZONE_CHANGE".equals(event.kind) && observedObjects.containsKey(event.targetId)) {
            review.card(observedObjects.get(event.targetId)).add(ref(game) + ", evento #" + event.sequence + ", turno globale " + event.globalTurn
                    + ": oggetto osservato " + event.from + " → " + event.to + "; fonte " + value(event.sourceName)
                    + ". Il cambio di zona non ne stabilisce da solo la causa.");
        }
        for (LabGame.Event event : events) {
            if (!review.playerId.equals(event.playerId)) continue;
            String ref = ref(game) + ", evento #" + event.sequence + ", turno globale " + event.globalTurn + " " + value(event.phase);
            if ("MULLIGAN_REJECT".equals(event.kind)) review.mulligans++;
            if ("MULLIGAN_KEEP".equals(event.kind)) {
                review.keeps++;
                review.observations.add(ref + ": mano tenuta di " + event.amount + " carte " + event.hand + ".");
                if (event.hand != null) for (Map.Entry<String, Integer> entry : event.hand.entrySet()) {
                    CardReview card = review.card(entry.getKey()); card.openingCopies += entry.getValue();
                    card.add(ref + ": mano tenuta, " + entry.getValue() + " copie.");
                }
            }
            if (!"PLAY".equals(event.stage)) continue;
            if(event.combatAssignments!=null) {
                Map<String,String> labels=new HashMap<>();
                for(LabGame.Participant p:game.players)labels.put(p.id,p.name);
                if(event.battlefield!=null)for(LabGame.CombatPermanent p:event.battlefield)labels.put(p.id,p.name+" "+p.power+"/"+p.toughness);
                List<String> lines=new ArrayList<>();
                for(LabGame.CombatAssignment a:event.combatAssignments) {
                    List<String> blockers=new ArrayList<>();for(String b:a.blockers)blockers.add(labels.getOrDefault(b,b));
                    lines.add(labels.getOrDefault(a.attacker,a.attacker)+" → "+labels.getOrDefault(a.defender,a.defender)+"; bloccanti: "+blockers);
                }
                review.observations.add(ref+": "+("DECLARED_ATTACKERS".equals(event.kind)?"attaccanti dichiarati":"blocchi dichiarati")+" "+lines
                        +(Boolean.FALSE.equals(event.battlefieldComplete)?". Fotografia del campo parziale.":". Fotografia del campo disponibile nella traccia."));
                continue;
            }
            // Zone events describe a cause/controller, not necessarily a deck's owner; never assign them to that deck's card totals.
            if (!Arrays.asList("DREW_CARD", "SPELL_CAST", "LAND_PLAYED", "DISCARDED_CARD", "MILLED_CARD").contains(event.kind)) continue;
            String name = event.targetName != null ? event.targetName : "SPELL_CAST".equals(event.kind) ? event.sourceName : null;
            if (name == null) { review.gaps.add(ref + ": nome carta assente; evento non attribuito a una carta."); continue; }
            CardReview card = review.card(name);
            switch (event.kind) {
                case "DREW_CARD": card.drawn++; break;
                case "SPELL_CAST": card.cast++; break;
                case "LAND_PLAYED": card.landsPlayed++; break;
                case "DISCARDED_CARD": card.discarded++; break;
                case "MILLED_CARD": card.milled++; break;
                default: break;
            }
            card.add(ref + ": " + action(event.kind) + "; oggetto " + value(event.targetId) + ".");
            if(event.spellTargets!=null && !event.spellTargets.isEmpty())card.add(ref+": bersagli dichiarati "+event.spellTargets+"; controllori "+event.targetControllers+".");
        }
    }

    public String describe() {
        StringBuilder text = new StringBuilder(overview);
        for (PlayerReview player : players) {
            text.append("\n============================================================\n").append(player.summary());
            text.append("\nSCHEDE DELLE CARTE\n");
            for (CardReview card : player.cards.values()) text.append("\n").append(card.describe(player));
        }
        return text.toString();
    }
    public static String metric(int value, int availableGames) { return availableGames == 0 ? "non disponibile" : Integer.toString(value); }
    private static String pilotLabel(String profile) {
        if (profile == null) return "pilota non disponibile";
        int end = profile.indexOf(';');
        String name = end < 0 ? profile : profile.substring(0, end);
        return name.substring(name.lastIndexOf('.') + 1) + (end < 0 ? "" : profile.substring(end));
    }
    private static String value(String value) { return value == null ? "non disponibile" : value; }
    private static String shortId(String id) { return id == null ? "lista non disponibile" : id.substring(0, Math.min(8, id.length())); }
    private static String ref(LabGame game) { return "Manche " + (game.match == null ? "storica" : game.match.round) + " [" + game.id + "]"; }
    private static String seconds(long nanos) { return String.format(Locale.ITALY, "%.3f", nanos / 1e9); }
    private static String outcome(String value) { return "WIN".equals(value) ? "vittoria" : "LOSS".equals(value) ? "sconfitta" : "DRAW".equals(value) ? "pareggio" : "esito non disponibile"; }
    private static String action(String kind) {
        switch (kind) {
            case "DREW_CARD": return "pescata";
            case "SPELL_CAST": return "lanciata";
            case "LAND_PLAYED": return "terra giocata";
            case "DISCARDED_CARD": return "scartata";
            default: return "macinata";
        }
    }
}
