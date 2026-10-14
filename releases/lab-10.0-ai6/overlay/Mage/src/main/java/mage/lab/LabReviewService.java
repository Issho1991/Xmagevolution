package mage.lab;

import org.apache.log4j.Logger;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** One bounded, low-priority worker. Jobs retain only paths and IDs, never a Game or a Match. */
public final class LabReviewService {
    private static final Logger LOG = Logger.getLogger(LabReviewService.class);
    private static final Map<String, CompletableFuture<Path>> PENDING = new HashMap<>();
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> {
                Thread thread = new Thread(task, "LAB-REPORT"); thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); return thread;
            });
    private LabReviewService() { }

    public static synchronized CompletableFuture<Path> scheduleMatch(LabStore store, String matchId, List<String> gameIds) {
        UUID.fromString(matchId);
        String key = store.root + ":" + matchId;
        if (PENDING.containsKey(key)) return PENDING.get(key);
        List<String> ids = new ArrayList<>(gameIds);
        CompletableFuture<Path> result = new CompletableFuture<>(); PENDING.put(key, result);
        try {
            WORKER.execute(() -> {
                try { result.complete(generate(store, matchId, ids)); }
                catch (Exception e) { LOG.error("Laboratorio: report non generato; è possibile rigenerarlo dal pannello", e); result.completeExceptionally(e); }
                finally { synchronized (LabReviewService.class) { PENDING.remove(key); } }
            });
        } catch (RejectedExecutionException e) {
            PENDING.remove(key); result.completeExceptionally(e);
            LOG.warn("Laboratorio: coda report piena; rigenerare il report dal pannello.");
        }
        return result;
    }

    public static Path generate(LabStore store, String matchId, List<String> gameIds) throws IOException {
        UUID.fromString(matchId);
        List<LabGame> games = new ArrayList<>();
        for (String id : gameIds) {
            LabGame game = store.game(id);
            if (game.match == null || !matchId.equals(game.match.id)) throw new IOException("Manche di un incontro diverso: " + id);
            games.add(game);
        }
        LabReview review = LabReview.create(games, store);
        Path path = store.root.resolve("reports").resolve("incontro-" + matchId + ".txt");
        LabStore.writeAtomic(path, review.describe(), true);
        LOG.info("Laboratorio: report incontro salvato in " + path);
        return path;
    }
}
