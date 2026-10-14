package mage.player.ai;

import mage.abilities.Ability;
import mage.game.combat.Combat;

import java.util.LinkedList;
import java.util.List;

/** Publishes detached decisions; a cancelled worker never exposes its mutable tree. */
final class SearchProgress {
    static final class Decision {
        private final List<Ability> abilities;
        private final Combat combat;
        private final int score;
        final boolean completed;

        Decision(SimulationNode2 node, int score, boolean completed) {
            this.abilities = copyAbilities(node.abilities);
            this.combat = node.combat == null ? null : node.combat.copy();
            this.score = score;
            this.completed = completed;
        }

        LinkedList<Ability> abilities() {
            return copyAbilities(abilities);
        }

        Combat combat() {
            return combat == null ? null : combat.copy();
        }
    }

    private Decision completed;
    private Decision land;
    private volatile boolean closed;

    boolean isClosed() {
        return closed;
    }

    synchronized void completed(SimulationNode2 node) {
        if (!closed) {
            completed = new Decision(node, node.getScore(), true);
        }
    }

    synchronized void land(SimulationNode2 node, int score, int initialScore) {
        if (!closed && score >= initialScore && (land == null || score > land.score)) {
            land = new Decision(node, score, false);
        }
    }

    synchronized Decision close() {
        closed = true;
        return completed == null ? land : completed;
    }

    static LinkedList<Ability> copyAbilities(List<Ability> source) {
        LinkedList<Ability> result = new LinkedList<>();
        if (source != null) {
            for (Ability ability : source) {
                result.add(ability.copy());
            }
        }
        return result;
    }
}
