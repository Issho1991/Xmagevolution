package mage.player.ai.score;

import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import org.apache.log4j.Logger;

import java.util.UUID;
import mage.abilities.Ability;
import mage.abilities.effects.Effect;
import mage.constants.Outcome;

/**
 * @author nantuko
 * <p>
 * Evaluates duels and all opponents in multiplayer, respecting range and teams.
 */
public final class GameStateEvaluator2 {

    private static final Logger logger = Logger.getLogger(GameStateEvaluator2.class);

    public static final int WIN_GAME_SCORE = 100000000;
    public static final int LOSE_GAME_SCORE = -WIN_GAME_SCORE;

    public static final int HAND_CARD_SCORE = 5;

    public static PlayerEvaluateScore evaluate(UUID playerId, Game game) {
        return evaluate(playerId, game, true);
    }

    public static PlayerEvaluateScore evaluate(UUID playerId, Game game, boolean useCombatPermanentScore) {
        Player player = game.getPlayer(playerId);
        if (player == null || player.hasLost() || player.hasLeft()) {
            return new PlayerEvaluateScore(playerId, LOSE_GAME_SCORE);
        }
        game.checkIfGameIsOver();
        if (player.hasWon()) return new PlayerEvaluateScore(playerId, WIN_GAME_SCORE);
        if (player.hasLost() || player.hasLeft()) return new PlayerEvaluateScore(playerId, LOSE_GAME_SCORE);
        // Evaluate every relevant opponent, including a bounded reward for elimination.
        // A third player's death is never a terminal win in a multiplayer game.
        int count = 0;
        long lifeSum = 0, handSum = 0, permanentSum = 0;
        int maxLife = Integer.MIN_VALUE, maxHand = Integer.MIN_VALUE, maxPermanents = Integer.MIN_VALUE;
        for (UUID opponentId : game.getOpponents(playerId, false)) {
            if (!player.hasOpponent(opponentId, game)) continue;
            Player opponent = game.getPlayer(opponentId);
            if (opponent == null) continue;
            count++;
            if (!opponent.isInGame()) {
                maxLife=Math.max(maxLife,0);maxHand=Math.max(maxHand,0);maxPermanents=Math.max(maxPermanents,0);
                continue;
            }
            int life = ArtificialScoringSystem.getLifeScore(opponent.getLife());
            int hand = opponent.getHand().size() * HAND_CARD_SCORE;
            int permanents = permanentScore(opponentId, game, useCombatPermanentScore);
            lifeSum += life; handSum += hand; permanentSum += permanents;
            maxLife = Math.max(maxLife, life); maxHand = Math.max(maxHand, hand);
            maxPermanents = Math.max(maxPermanents, permanents);
        }
        int playerLife = ArtificialScoringSystem.getLifeScore(player.getLife());
        if (player.getLife() <= 0 && player.canLose(game)) playerLife = LOSE_GAME_SCORE;
        return new PlayerEvaluateScore(playerId, playerLife,
                player.getHand().size() * HAND_CARD_SCORE, permanentScore(playerId, game, useCombatPermanentScore),
                aggregate(lifeSum, maxLife, count), aggregate(handSum, maxHand, count),
                aggregate(permanentSum, maxPermanents, count));
    }

    // Half mean, half maximum: all opponents matter, while a dominant board is
    // not diluted by several weak players. Identical to the duel score for n=1.
    private static int aggregate(long sum, int maximum, int count) {
        return count == 0 ? 0 : (int) ((sum / count + maximum) / 2);
    }

    private static int permanentScore(UUID playerId, Game game, boolean combat) {
        int score = 0;
        for (Permanent permanent : game.getBattlefield().getAllActivePermanents(playerId)) {
            score += evaluatePermanent(permanent, game, combat);
        }
        return score;
    }

    public static int evaluatePermanent(Permanent permanent, Game game, boolean useCombatPermanentScore) {
        // prevent AI from attaching bad auras to its own permanents ex: Brainwash and Demonic Torment (no immediate penalty on the battlefield)
        int value = 0;
        if (!permanent.getAttachments().isEmpty()) {
            for (UUID attachmentId : permanent.getAttachments()) {
                Permanent attachment = game.getPermanent(attachmentId);
                if (attachment == null) continue;
                for (Ability a : attachment.getAbilities(game)) {
                    for (Effect e : a.getEffects()) {
                        if (e.getOutcome().equals(Outcome.Detriment)
                                && attachment.getControllerId().equals(permanent.getControllerId())) {
                            value -= 1000;  // seems to work well ; -300 is not effective enough
                        }
                    }
                }
            }
        }
        value += ArtificialScoringSystem.getFixedPermanentScore(game, permanent);
        value += ArtificialScoringSystem.getDynamicPermanentScore(game, permanent);
        if (useCombatPermanentScore) {
            value += ArtificialScoringSystem.getCombatPermanentScore(game, permanent);
        }
        return value;
    }

    public static class PlayerEvaluateScore {

        private UUID playerId;
        private int playerLifeScore = 0;
        private int playerHandScore = 0;
        private int playerPermanentsScore = 0;

        private int opponentLifeScore = 0;
        private int opponentHandScore = 0;
        private int opponentPermanentsScore = 0;

        private int specialScore = 0; // special score (ignore all others, e.g. for win/lose game states)

        public PlayerEvaluateScore(UUID playerId, int specialScore) {
            this.playerId = playerId;
            this.specialScore = specialScore;
        }

        public PlayerEvaluateScore(UUID playerId,
                                   int playerLifeScore, int playerHandScore, int playerPermanentsScore,
                                   int opponentLifeScore, int opponentHandScore, int opponentPermanentsScore) {
            this.playerId = playerId;
            this.playerLifeScore = playerLifeScore;
            this.playerHandScore = playerHandScore;
            this.playerPermanentsScore = playerPermanentsScore;
            this.opponentLifeScore = opponentLifeScore;
            this.opponentHandScore = opponentHandScore;
            this.opponentPermanentsScore = opponentPermanentsScore;
        }

        public UUID getPlayerId() {
            return this.playerId;
        }

        public int getPlayerScore() {
            return playerLifeScore + playerHandScore + playerPermanentsScore;
        }

        public int getOpponentScore() {
            return opponentLifeScore + opponentHandScore + opponentPermanentsScore;
        }

        public int getTotalScore() {
            if (specialScore != 0) {
                return specialScore;
            } else {
                return getPlayerScore() - getOpponentScore();
            }
        }

        public int getPlayerLifeScore() {
            return playerLifeScore;
        }

        public int getPlayerHandScore() {
            return playerHandScore;
        }

        public int getPlayerPermanentsScore() {
            return playerPermanentsScore;
        }

        public String getPlayerInfoFull() {
            return "Life:" + playerLifeScore
                    + ", Hand:" + playerHandScore
                    + ", Perm:" + playerPermanentsScore;
        }

        public String getPlayerInfoShort() {
            return "L:" + playerLifeScore
                    + ",H:" + playerHandScore
                    + ",P:" + playerPermanentsScore;
        }

        public String getOpponentInfoFull() {
            return "Life:" + opponentLifeScore
                    + ", Hand:" + opponentHandScore
                    + ", Perm:" + opponentPermanentsScore;
        }

        public String getOpponentInfoShort() {
            return "L:" + opponentLifeScore
                    + ",H:" + opponentHandScore
                    + ",P:" + opponentPermanentsScore;
        }
    }
}
