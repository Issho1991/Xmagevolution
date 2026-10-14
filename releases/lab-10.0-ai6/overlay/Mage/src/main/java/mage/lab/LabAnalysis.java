package mage.lab;

import mage.cards.decks.DeckCardInfo;
import mage.cards.repository.CardInfo;
import mage.constants.CardType;
import java.util.*;
import java.util.function.Function;

/** Descriptive mana analysis. No claim of castability or optimal mulligan policy. */
public final class LabAnalysis {
    public int total, sideboard, known, lands, creatures, flexible, costly;
    public double averageManaValue;
    public final SortedMap<Integer, Integer> curve = new TreeMap<>();
    public final SortedMap<String, Integer> unknown = new TreeMap<>();
    public final SortedMap<String, Integer> basicSymbols = new TreeMap<>();
    public final SortedMap<String, Integer> complexCosts = new TreeMap<>();

    public static LabAnalysis analyze(LabDeck revision, Function<String, CardInfo> lookup) {
        LabAnalysis result = new LabAnalysis();
        double manaSum = 0;
        for (DeckCardInfo entry : revision.deck.getSideboard()) result.sideboard += entry.getAmount();
        for (Map.Entry<String, Integer> entry : LabDeck.counts(revision.deck.getCards()).entrySet()) {
            int count = entry.getValue();
            result.total += count;
            CardInfo info = lookup.apply(entry.getKey());
            if (info == null) { result.unknown.put(entry.getKey(), count); continue; }
            result.known += count;
            if (info.isDoubleFacedCard() || info.isSplitCard() || info.isCardWithSpellOption()) result.flexible += count;
            if (info.getTypes().contains(CardType.LAND)) { result.lands += count; continue; }
            if (info.getTypes().contains(CardType.CREATURE)) result.creatures += count;
            int value = info.getManaValue();
            result.curve.merge(value, count, Integer::sum);
            manaSum += (double) value * count;
            if (value >= 5) result.costly += count;
            CardInfo.ManaCostSide costSide = info.isCardWithSpellOption() ? CardInfo.ManaCostSide.RIGHT : CardInfo.ManaCostSide.LEFT;
            for (String symbol : info.getManaCosts(costSide)) {
                String token = symbol.replace("{", "").replace("}", "");
                if (Arrays.asList("W", "U", "B", "R", "G", "C").contains(token)) result.basicSymbols.merge(token, count, Integer::sum);
                else if (!token.matches("\\d+")) result.complexCosts.merge(token, count, Integer::sum);
            }
        }
        result.averageManaValue = result.known == result.lands ? 0 : manaSum / (result.known - result.lands);
        return result;
    }

    /** Exact hypergeometric probability, evaluated by stable dynamic programming. */
    public static double probability(int population, int successes, int draws, int minimum, int maximum) {
        if (population < 0 || successes < 0 || successes > population || draws < 0 || draws > population) {
            throw new IllegalArgumentException("Parametri di pescata non validi.");
        }
        double[] probabilities = new double[draws + 1];
        probabilities[0] = 1;
        for (int i = 0; i < draws; i++) {
            double[] next = new double[draws + 1];
            for (int k = 0; k <= i; k++) {
                if (probabilities[k] == 0) continue;
                int hitsLeft = successes - k;
                int missesLeft = population - successes - (i - k);
                if (hitsLeft > 0) next[k + 1] += probabilities[k] * hitsLeft / (population - i);
                if (missesLeft > 0) next[k] += probabilities[k] * missesLeft / (population - i);
            }
            probabilities = next;
        }
        double result = 0;
        for (int k = Math.max(0, minimum); k <= Math.min(draws, maximum); k++) result += probabilities[k];
        return Math.min(1, result);
    }

    public String describe(int hand, int additionalDraws) {
        StringBuilder text = new StringBuilder();
        text.append("COMPOSIZIONE\n").append(total).append(" carte principali · ").append(sideboard).append(" in sideboard\n")
                .append(lands).append(" terre sul fronte · ").append(creatures).append(" creature non terra\n")
                .append(String.format(Locale.ITALY, "Valore di mana medio delle non terre note: %.2f\n", averageManaValue))
                .append("Curva (valore di mana → copie): ").append(curve).append('\n')
                .append("Simboli semplici nel costo sul fronte: ").append(basicSymbols).append('\n');
        if (!complexCosts.isEmpty()) text.append("Costi speciali, ibridi o variabili: ").append(complexCosts).append('\n');
        text.append("I simboli sono richieste stampate, non fonti di mana disponibili.\n\n");
        if (!unknown.isEmpty()) {
            text.append("ANALISI INCOMPLETA\nCarte non riconosciute: ").append(unknown)
                    .append("\nLe probabilità sulle terre sono sospese finché tutte le carte sono riconosciute.\n");
            return text.toString();
        }
        if (hand < 0 || hand > total || additionalDraws < 0 || hand + additionalDraws > total) {
            return text.append("Il numero di carte da osservare deve rientrare nella dimensione del mazzo.\n").toString();
        }
        text.append("REGOLARITÀ DELLE PESCATE\n");
        probabilityLine(text, "0–1 terre nelle prime " + hand + " carte", probability(total, lands, hand, 0, 1));
        probabilityLine(text, "2–4 terre nelle prime " + hand + " carte", probability(total, lands, hand, 2, 4));
        probabilityLine(text, "5 o più terre nelle prime " + hand + " carte", probability(total, lands, hand, 5, hand));
        probabilityLine(text, "Almeno 3 terre dopo " + additionalDraws + " pescate aggiuntive", probability(total, lands, hand + additionalDraws, 3, total));
        text.append("Calcolo esatto con mescolamento uniforme, senza mulligan, selezione, ricerca o pescate extra.\n")
                .append("Conta le carte terra sul fronte: non simula colori, ingressi tappati, acceleratori o costi ridotti.\n");
        if (flexible > 0) text.append("Presenti ").append(flexible).append(" carte con opzioni/facce multiple: la curva e le terre sul fronte sono una descrizione parziale.\n");
        text.append("\nPUNTI DA VERIFICARE\n");
        probabilityLine(text, "Rischio iniziale da confrontare con la variante: 0–1 terre", probability(total, lands, hand, 0, 1));
        text.append(costly).append(" non terre hanno valore di mana almeno 5: osservare quando diventano utilizzabili nelle partite.\n")
                .append("Nessuna sostituzione automatica: questi dati non dimostrano da soli la forza del mazzo.\n");
        return text.toString();
    }

    private static void probabilityLine(StringBuilder text, String title, double value) {
        text.append(String.format(Locale.ITALY, "%s: %.2f%%\n", title, 100 * value));
    }

    public static String differences(LabDeck a, LabDeck b) {
        StringBuilder text = new StringBuilder("SCAMBI DA A A B\n");
        for (boolean side : Arrays.asList(false, true)) {
            SortedMap<String, Integer> before = LabDeck.counts(side ? a.deck.getSideboard() : a.deck.getCards());
            SortedMap<String, Integer> after = LabDeck.counts(side ? b.deck.getSideboard() : b.deck.getCards());
            SortedSet<String> names = new TreeSet<>(before.keySet()); names.addAll(after.keySet());
            text.append(side ? "Sideboard:\n" : "Mazzo principale:\n");
            boolean changed = false;
            for (String name : names) {
                int delta = after.getOrDefault(name, 0) - before.getOrDefault(name, 0);
                if (delta != 0) { text.append(delta > 0 ? "+" : "").append(delta).append(' ').append(name).append('\n'); changed = true; }
            }
            if (!changed) text.append("Stessa composizione.\n");
        }
        return text.toString();
    }
}
