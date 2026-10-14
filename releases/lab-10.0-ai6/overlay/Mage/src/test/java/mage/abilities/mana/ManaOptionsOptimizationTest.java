package mage.abilities.mana;

import mage.ConditionalMana;
import mage.Mana;
import mage.abilities.condition.Condition;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ManaOptionsOptimizationTest {
    private static final Condition CREATURES = (game, source) -> true;
    private static final Condition SPELLS = (game, source) -> false;

    @Test
    void retainsAllFixedColorDistributionsAndTheirOrder() {
        for (int total = 0; total <= 8; total++) {
            List<Mana> values = new ArrayList<>();
            distributions(values, new int[6], 0, total);
            Collections.shuffle(values, new Random(7183 + total));
            ManaOptions options = options(values);
            List<Mana> expected = new ArrayList<>(options);
            assertMatchesOriginal(options);
            assertEquals(expected, new ArrayList<>(options));
        }
    }

    @Test
    void reindexesValuesChangedWhileInsideTheSet() {
        Mana first = Mana.RedMana(1);
        Mana second = Mana.BlueMana(1);
        ManaOptions options = options(Arrays.asList(first, second));
        options.addMana(Mana.GreenMana(1));
        options.removeFullyIncludedVariations();
        assertTrue(options.contains(first.copy()));
        assertTrue(options.contains(second.copy()));
        assertEquals(Arrays.asList(first, second), new ArrayList<>(options));
    }

    @Test
    void retainsFirstInstanceWhenMutationsCreateExactDuplicates() {
        Mana first = Mana.RedMana(1);
        Mana second = Mana.BlueMana(1);
        ManaOptions options = options(Arrays.asList(first, second));
        second.setToMana(first);
        options.removeFullyIncludedVariations();
        assertEquals(1, options.size());
        assertSame(first, options.iterator().next());
        assertTrue(options.contains(first.copy()));
    }

    @Test
    void preservesMixedAmountsAnyGenericFlagsAndConditionalMana() {
        Random random = new Random(385431);
        for (int iteration = 0; iteration < 1500; iteration++) {
            List<Mana> values = new ArrayList<>();
            int size = 1 + random.nextInt(25);
            for (int i = 0; i < size; i++) {
                Mana mana = new Mana(random.nextInt(4), random.nextInt(4), random.nextInt(4),
                        random.nextInt(4), random.nextInt(4), random.nextInt(3),
                        random.nextInt(3), random.nextInt(4));
                if (random.nextInt(4) == 0) {
                    ConditionalMana conditional = new ConditionalMana(mana);
                    conditional.addCondition(random.nextBoolean() ? CREATURES : SPELLS);
                    mana = conditional;
                }
                mana.setFlag(random.nextBoolean());
                values.add(mana);
            }
            assertMatchesOriginal(options(values));
        }
    }

    @Test
    void preservesColorlessAndAnyAsDistinctResources() {
        ManaOptions options = options(Arrays.asList(Mana.ColorlessMana(2), Mana.AnyMana(2)));
        assertMatchesOriginal(options);
        assertEquals(2, options.size());
    }

    @Test
    void preservesLargeTotalsWithoutIntegerOverflow() {
        int max = Integer.MAX_VALUE;
        ManaOptions options = options(Arrays.asList(
                new Mana(max, max, 0, 0, 0, 0, 0, 0),
                new Mana(max, 0, max, 0, 0, 0, 0, 0),
                new Mana(max, 0, 0, 0, 0, 0, 0, max)));
        assertMatchesOriginal(options);
        assertEquals(3, options.size());
    }

    private static ManaOptions options(List<Mana> values) {
        ManaOptions result = new ManaOptions();
        result.addAll(values);
        return result;
    }

    private static void distributions(List<Mana> out, int[] values, int dimension, int remaining) {
        if (dimension == values.length - 1) {
            values[dimension] = remaining;
            out.add(new Mana(values[0], values[1], values[2], values[3], values[4], 0, 0, values[5]));
            return;
        }
        for (int amount = 0; amount <= remaining; amount++) {
            values[dimension] = amount;
            distributions(out, values, dimension + 1, remaining - amount);
        }
    }

    private static void assertMatchesOriginal(ManaOptions actual) {
        ManaOptions expected = actual.copy();
        originalNormalize(expected);
        actual.removeFullyIncludedVariations();
        assertEquals(new ArrayList<>(expected), new ArrayList<>(actual),
                "Values, restrictions, flags and iteration order must match the original algorithm");
    }

    /** Snapshot of the original algorithm, used only as an independent differential oracle. */
    private static void originalNormalize(ManaOptions options) {
        List<Mana> that = new ArrayList<>(options);
        Set<String> list = new HashSet<>();
        for (int i = options.size() - 1; i >= 0; i--) {
            String s = that.get(i) instanceof ConditionalMana
                    ? that.get(i).toString() + ((ConditionalMana) that.get(i)).getConditionString()
                    : that.get(i).toString();
            if (s.isEmpty()) {
                options.remove(i);
            } else if (list.contains(s)) {
                options.remove(i);
            } else {
                list.add(s);
            }
        }
        for (int i = options.size() - 1; i >= 0; i--) {
            for (int ii = 0; ii < i; ii++) {
                Mana moreValuable = Mana.getMoreValuableMana(that.get(i), that.get(ii));
                if (moreValuable != null) {
                    that.get(ii).setToMana(moreValuable);
                    that.remove(i);
                    break;
                }
            }
        }
        options.clear();
        options.addAll(that);
    }
}
