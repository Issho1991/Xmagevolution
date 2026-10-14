package mage.lab;

import java.io.IOException;
import java.util.*;

/** Read-only interpretation of raw traces. Undo boundaries remove observations, never rewrite history. */
public final class LabTrace {
    public final List<LabGame.Event> events = new ArrayList<>();
    public final List<LabGame.Sample> samples = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();
    public boolean completeEvents, partialEvents;
    public int removedEvents, removedSamples;

    public static LabTrace read(LabGame game, LabStore store) {
        try { return select(game, game.telemetry == null ? Collections.emptyList() : store.events(game.id)); }
        catch (IOException e) {
            LabTrace result = new LabTrace(); result.notes.add("Traccia non leggibile: " + e.getMessage());
            if (game.eligible() && (game.telemetry == null || !game.telemetry.stateRestored)) result.samples.addAll(game.samples);
            return result;
        }
    }

    public static LabTrace select(LabGame game, List<LabGame.Event> raw) {
        LabTrace result = new LabTrace();
        boolean reviewable = "COMPLETED".equals(game.status) && game.exclusions.stream().allMatch(LabTrace::restoreExclusion);
        if (!reviewable) {
            result.notes.add("Manche esclusa dai segnali: " + LabReports.status(game) + " " + game.exclusions);
            return result;
        }
        if (game.telemetry == null) {
            result.notes.add("Eventi non registrati in questa versione.");
            if (game.eligible()) result.samples.addAll(game.samples);
            return result;
        }
        LabGame.Telemetry data = game.telemetry;
        boolean intact = data.events == raw.size();
        for (int i = 0; i < raw.size(); i++) intact &= raw.get(i).sequence == i + 1L;
        List<LabGame.Event> restores = new ArrayList<>();
        for (LabGame.Event event : raw) if ("STATE_RESTORED".equals(event.kind)) restores.add(event);
        if (!intact) {
            result.notes.add("Traccia mancante o numero di eventi incoerente.");
            if (game.eligible() && !data.stateRestored) result.samples.addAll(game.samples);
            return result;
        }
        if (!data.stateRestored && restores.isEmpty() && game.eligible()) {
            result.samples.addAll(game.samples);
            if (data.complete && data.warnings.isEmpty()) { result.completeEvents = true; result.events.addAll(raw); }
            else result.notes.add("Traccia parziale: conteggi degli eventi omessi; campioni disponibili separatamente.");
            return result;
        }
        if (game.schema >= 3 && data.complete && data.warnings.isEmpty() && data.stateRestored && !restores.isEmpty()) {
            BitSet removedEvents = new BitSet(), removedSamples = new BitSet();
            boolean valid = true;
            for (int i = 0; i < game.samples.size(); i++) valid &= game.samples.get(i).sequence == i + 1L;
            for (LabGame.Event marker : restores) {
                if (marker.restoredAfterEvent == null || marker.restoredAfterSample == null || marker.samplesBeforeRestore == null
                        || marker.restoredAfterEvent < 0 || marker.restoredAfterEvent >= marker.sequence
                        || marker.restoredAfterSample < 0 || marker.samplesBeforeRestore < marker.restoredAfterSample
                        || marker.samplesBeforeRestore > game.samples.size()) { valid = false; break; }
                removedEvents.set(marker.restoredAfterEvent.intValue(), (int) marker.sequence - 1);
                removedSamples.set(marker.restoredAfterSample, marker.samplesBeforeRestore);
            }
            if (valid) {
                for (int i = 0; i < raw.size(); i++) {
                    LabGame.Event event = raw.get(i);
                    if ("STATE_RESTORED".equals(event.kind) || event.kind.startsWith("AI_")) continue;
                    if (removedEvents.get(i)) result.removedEvents++; else result.events.add(event);
                }
                for (int i = 0; i < game.samples.size(); i++) {
                    if (removedSamples.get(i)) result.removedSamples++; else result.samples.add(game.samples.get(i));
                }
                result.completeEvents = true;
                result.notes.add("Annullamenti ricostruiti con confini esatti: " + result.removedEvents + " eventi di gioco e "
                        + result.removedSamples + " campioni annullati esclusi. I dati originali restano conservati.");
                return result;
            }
            result.notes.add("Confini di annullamento incoerenti: impossibile confermare la cronologia.");
            return result;
        }
        // Schema 2 did not record checkpoints. Only the suffix after the LAST restore is certain.
        boolean oldUndoWarnings = data.warnings.stream().allMatch(s -> s.equals("La cronologia comprende azioni poi annullate: conteggi delle carte non utilizzabili."));
        boolean localOnly = game.exclusions.stream().allMatch(s -> s.equals("Partita con ripristino di stato: azioni annullate"));
        if (game.schema == 2 && data.stateRestored && !restores.isEmpty() && oldUndoWarnings && localOnly) {
            LabGame.Event last = restores.get(restores.size() - 1);
            int previousTurn = last.globalTurn;
            boolean monotonic = previousTurn >= 1;
            for (LabGame.Event event : raw) if (event.sequence > last.sequence) {
                monotonic &= event.globalTurn >= previousTurn; previousTurn = event.globalTurn;
            }
            if (monotonic) {
                for (LabGame.Event event : raw) if (event.sequence > last.sequence && "PLAY".equals(event.stage)) result.events.add(event);
                for (LabGame.Sample sample : game.samples) if (sample.globalTurn > last.globalTurn) result.samples.add(sample);
                result.partialEvents = !result.events.isEmpty();
                result.notes.add("Recupero parziale dello storico: eventi successivi all’ultimo ripristino #" + last.sequence
                        + " (turno globale " + last.globalTurn + ") e campioni dei turni successivi. Conteggi riferiti solo a questo tratto finale;"
                        + " nessuna ricostruzione delle azioni precedenti. Le manche restano escluse dai confronti.");
                return result;
            }
        }
        result.notes.add("Annullamenti senza confini verificabili: campioni e conteggi delle carte omessi. Gli esiti e i tempi IA restano consultabili.");
        return result;
    }

    private static boolean restoreExclusion(String reason) {
        return Arrays.asList("Partita con rollback", "Partita con ripristino di stato: azioni annullate",
                "Partita con undo del giocatore", "Ripristino senza punto di registrazione disponibile").contains(reason);
    }
}
