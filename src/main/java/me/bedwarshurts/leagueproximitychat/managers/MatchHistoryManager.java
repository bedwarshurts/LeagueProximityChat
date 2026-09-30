package me.bedwarshurts.leagueproximitychat.managers;

import me.bedwarshurts.leagueproximitychat.data.LeagueGame;
import me.bedwarshurts.leagueproximitychat.data.LeaguePlayer;
import me.bedwarshurts.leagueproximitychat.utils.ReplayApiConfig;
import me.bedwarshurts.leagueproximitychat.utils.RitoApiUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class MatchHistoryManager {

    private static final int MAX_MATCHES = 20;
    private static final long STATS_WAIT_MS = 120_000;
    private static final long STATS_POLL_MS = 5_000;
    private static final long UNFINISHED_MAX_AGE_MS = 24L * 60 * 60 * 1000;
    private static final String RECORD_FILE = "match.json";
    private static final Pattern SAFE_ID = Pattern.compile("[0-9A-Za-z_-]{1,64}");

    private static final long CLOCK_JUMP_MS = 250;
    private static final long CLOCK_REFRESH_MS = 60_000;
    private static final long RECORDING_GRACE_MS = 5 * 60_000;
    private static final long PENDING_WATCH_MS = 30 * 60_000;

    private static final Path ROOT = resolveRoot();
    private static final Object lock = new Object();

    private static JSONObject current;
    private static boolean currentFinished;
    private static long[] lastClockSample;
    private static boolean lastClockSampleKept;
    private static boolean lastClockSlipped;

    private static String pendingWatchId;
    private static long pendingWatchAt;

    private MatchHistoryManager() {
    }

    private static Path resolveRoot() {
        String appData = System.getenv("APPDATA");
        Path dir = (appData != null && !appData.isBlank())
                ? Paths.get(appData, "LeagueProximityChat")
                : Paths.get(System.getProperty("user.home"), ".leagueproximitychat");
        return dir.resolve("matches");
    }

    public static String beginMatch() {
        JSONObject session = RitoApiUtils.getGameflowSession();
        JSONObject gameData = session != null ? session.optJSONObject("gameData") : null;
        long gameId = gameData != null ? gameData.optLong("gameId", -1) : -1;
        JSONObject queue = gameData != null ? gameData.optJSONObject("queue") : null;
        JSONObject replays = RitoApiUtils.getReplaysConfiguration();

        synchronized (lock) {
            if (current != null && !currentFinished && current.optLong("gameId", -1) == gameId) return current.getString("id");

            long now = System.currentTimeMillis();
            current = new JSONObject()
                    .put("id", gameId > 0 ? now + "-" + gameId : String.valueOf(now))
                    .put("gameId", gameId)
                    .put("queueId", queue != null ? queue.optInt("id", -1) : -1)
                    .put("gameType", queue != null ? queue.optString("type", "") : "")
                    .put("gameVersion", replays != null ? replays.optString("gameVersion", "") : "")
                    .put("startedAt", now)
                    .put("clock", new JSONArray())
                    .put("highlights", new JSONArray());
            currentFinished = false;
            lastClockSample = null;
            lastClockSlipped = false;
            System.out.println("[History] Recording match " + current.getString("id") + ".");
            return current.getString("id");
        }
    }

    public static void noteGameClock(double gameTime) {
        long now = System.currentTimeMillis();
        long offset = now - Math.round(gameTime * 1000);
        synchronized (lock) {
            if (current == null || currentFinished) return;
            JSONArray clock = current.optJSONArray("clock");
            if (clock == null) {
                clock = new JSONArray();
                current.put("clock", clock);
            }

            boolean slipped = false;
            boolean keep = clock.isEmpty();
            if (!keep) {
                JSONArray last = clock.getJSONArray(clock.length() - 1);
                long lastOffset = last.getLong(0) - Math.round(last.getDouble(1) * 1000);
                slipped = Math.abs(offset - lastOffset) > CLOCK_JUMP_MS;
                if (slipped && lastClockSample != null && !lastClockSampleKept) {
                    clock.put(new JSONArray().put(lastClockSample[0]).put(lastClockSample[1] / 1000.0));
                }
                keep = slipped || lastClockSlipped || now - last.getLong(0) >= CLOCK_REFRESH_MS;
            }
            if (keep) clock.put(new JSONArray().put(now).put(gameTime));
            lastClockSample = new long[]{now, Math.round(gameTime * 1000)};
            lastClockSampleKept = keep;
            lastClockSlipped = slipped;
        }
    }

    static boolean acceptsRecording(String id) {
        synchronized (lock) {
            if (current == null || !id.equals(current.optString("id"))) return false;
            return !currentFinished || System.currentTimeMillis() - current.optLong("endedAt") < RECORDING_GRACE_MS;
        }
    }

    static Path matchDir(String id) {
        return ROOT.resolve(id);
    }

    static JSONObject recordCopy(String id) {
        synchronized (lock) {
            JSONObject record = recordFor(id);
            return record == null ? null : new JSONObject(record.toString());
        }
    }

    public static String finishMatch() {
        String id;
        synchronized (lock) {
            if (current == null || currentFinished) return null;
            currentFinished = true;
            current.put("endedAt", System.currentTimeMillis());
            id = current.getString("id");
            writeRecord(current);
        }

        Thread worker = new Thread(() -> collectStats(id), "match-history");
        worker.setDaemon(true);
        worker.start();
        return id;
    }

    private static void collectStats(String id) {
        JSONObject first = new JSONObject(PlayOfGameManager.statsJson());
        saveStats(id, first);
        JSONObject best = first;
        long deadline = System.currentTimeMillis() + STATS_WAIT_MS;
        while (!best.optBoolean("endOfGameStats", false) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(STATS_POLL_MS);
            } catch (InterruptedException e) {
                break;
            }
            JSONObject next = new JSONObject(PlayOfGameManager.statsJson());
            if (!sameGame(first, next)) break;
            best = next;
        }

        if (best != first) saveStats(id, best);
        prune();
    }

    private static void saveStats(String id, JSONObject stats) {
        if (!stats.optBoolean("available", false)) return;
        synchronized (lock) {
            JSONObject record = current != null && id.equals(current.optString("id")) ? current : readRecord(id);
            if (record == null) return;
            applyStats(record, stats);
            writeRecord(record);
        }
    }

    private static boolean sameGame(JSONObject a, JSONObject b) {
        if (!a.optBoolean("available", false)) return b.optBoolean("available", false);
        return playerIds(a).equals(playerIds(b));
    }

    private static List<String> playerIds(JSONObject stats) {
        List<String> ids = new ArrayList<>();
        JSONArray players = stats.optJSONArray("players");
        if (players == null) return ids;
        for (int i = 0; i < players.length(); i++) {
            JSONObject p = players.getJSONObject(i);
            ids.add(p.optString("riotId") + "/" + p.optString("champion"));
        }
        ids.sort(null);
        return ids;
    }

    private static void applyStats(JSONObject record, JSONObject stats) {
        if (!stats.optBoolean("available", false)) return;
        record.put("stats", stats)
                .put("result", stats.opt("result"))
                .put("gameMode", stats.optString("gameMode", ""))
                .put("mapNumber", stats.optInt("mapNumber", 0))
                .put("durationSeconds", Math.round(stats.optDouble("gameTime", 0)));

        String localId = stats.optString("localIdentity", "");
        JSONArray players = stats.optJSONArray("players");
        if (players == null) return;
        for (int i = 0; i < players.length(); i++) {
            JSONObject p = players.getJSONObject(i);
            if (!p.optString("riotId").equalsIgnoreCase(localId)) continue;
            record.put("local", new JSONObject()
                    .put("riotId", p.optString("riotId"))
                    .put("champion", p.optString("champion"))
                    .put("team", p.optString("team"))
                    .put("kills", p.optInt("kills"))
                    .put("deaths", p.optInt("deaths"))
                    .put("assists", p.optInt("assists"))
                    .put("creepScore", p.optInt("creepScore"))
                    .put("skinId", p.optInt("skinId"))
                    .put("items", p.optJSONArray("items") != null ? p.getJSONArray("items") : new JSONArray()));
        }
    }

    public static void addHighlight(String id, String highlightId, String headline, String gameClock, int score, byte[] webm)
            throws IOException {
        synchronized (lock) {
            JSONObject record = recordFor(id);
            if (record == null) throw new IOException("unknown match " + id);
            if (hasHighlight(record, highlightId)) return;

            JSONArray highlights = record.getJSONArray("highlights");
            String file = "highlights/" + (highlights.length() + 1) + ".webm";
            Path target = ROOT.resolve(id).resolve(file);
            Files.createDirectories(target.getParent());
            Files.write(target, webm);

            highlights.put(highlight(highlightId, headline, gameClock, score).put("file", file).put("managed", true));
            if (record.has("endedAt")) writeRecord(record);
        }
    }

    public static void linkSavedHighlight(String highlightId, String headline, String gameClock, Path file) {
        synchronized (lock) {
            if (current == null || hasHighlight(current, highlightId)) return;
            current.getJSONArray("highlights").put(highlight(highlightId, headline, gameClock, 0)
                    .put("file", file.toAbsolutePath().toString())
                    .put("managed", false));
            if (current.has("stats")) writeRecord(current);
        }
    }

    private static JSONObject highlight(String highlightId, String headline, String gameClock, int score) {
        return new JSONObject()
                .put("id", highlightId == null ? "" : highlightId)
                .put("headline", headline == null ? "Highlight" : headline)
                .put("gameClock", gameClock == null ? "" : gameClock)
                .put("score", score);
    }

    private static boolean hasHighlight(JSONObject record, String highlightId) {
        if (highlightId == null || highlightId.isEmpty()) return false;
        JSONArray highlights = record.getJSONArray("highlights");
        for (int i = 0; i < highlights.length(); i++) {
            if (highlightId.equals(highlights.getJSONObject(i).optString("id"))) return true;
        }
        return false;
    }

    public static String listJson() {
        JSONArray matches = new JSONArray();
        for (JSONObject record : finishedRecords()) {
            matches.put(new JSONObject()
                    .put("id", record.getString("id"))
                    .put("startedAt", record.optLong("startedAt"))
                    .put("durationSeconds", record.optLong("durationSeconds"))
                    .put("result", record.opt("result"))
                    .put("gameMode", record.optString("gameMode"))
                    .put("mapNumber", record.optInt("mapNumber"))
                    .put("queueId", record.optInt("queueId", -1))
                    .put("local", record.optJSONObject("local"))
                    .put("highlightCount", record.getJSONArray("highlights").length()));
        }
        return new JSONObject().put("matches", matches).toString();
    }

    public static String matchJson(String id) {
        JSONObject record;
        synchronized (lock) {
            record = recordFor(id);
            if (record == null) return null;
            record = new JSONObject(record.toString());
        }
        JSONArray highlights = record.getJSONArray("highlights");
        for (int i = 0; i < highlights.length(); i++) {
            JSONObject h = highlights.getJSONObject(i);
            Path file = highlightFile(id, i);
            h.put("available", file != null && Files.exists(file));
            h.put("url", "/matches/" + id + "/highlights/" + i);
        }
        record.remove("clock");
        JSONObject voice = VoiceArchiveManager.summary(id);
        if (voice != null) record.put("voice", voice);
        return record.toString();
    }

    public static Path highlightFile(String id, int index) {
        synchronized (lock) {
            JSONObject record = recordFor(id);
            if (record == null) return null;
            JSONArray highlights = record.getJSONArray("highlights");
            if (index < 0 || index >= highlights.length()) return null;
            JSONObject h = highlights.getJSONObject(index);
            String file = h.optString("file", "");
            if (file.isEmpty()) return null;
            return h.optBoolean("managed", false) ? ROOT.resolve(id).resolve(file).normalize() : Paths.get(file);
        }
    }

    public static String replayJson(String id) {
        JSONObject result = new JSONObject()
                .put("replayApiEnabled", ReplayApiConfig.isEnabled())
                .put("gameCfgPath", ReplayApiConfig.gameCfgPath().toString());

        long gameId;
        JSONObject record;
        synchronized (lock) {
            record = recordFor(id);
            if (record == null) return null;
            gameId = record.optLong("gameId", -1);
            record = new JSONObject(record.toString());
        }
        if (gameId <= 0) return result.put("state", "unsupported").toString();
        if (RitoApiUtils.getReplaysConfiguration() == null) return result.put("state", "clientClosed").toString();

        JSONObject metadata = RitoApiUtils.getReplayMetadata(gameId);
        if (metadata == null) {
            RitoApiUtils.createReplayMetadata(gameId, new JSONObject()
                    .put("gameEnd", record.optLong("endedAt"))
                    .put("gameType", record.optString("gameType"))
                    .put("gameVersion", record.optString("gameVersion"))
                    .put("queueId", record.optInt("queueId", -1)));
            metadata = RitoApiUtils.getReplayMetadata(gameId);
        }
        if (metadata == null) return result.put("state", "error").toString();
        String state = metadata.optString("state", "error");

        long progress = state.equals("downloading") ? metadata.optLong("downloadProgress", 0) : 0;
        return result.put("state", state)
                .put("downloadProgress", Math.clamp(progress, 0, 100))
                .toString();
    }

    public static boolean downloadReplay(String id) {
        long gameId = gameIdOf(id);
        return gameId > 0 && RitoApiUtils.downloadReplay(gameId);
    }

    public static boolean watchReplay(String id) {
        long gameId = gameIdOf(id);
        boolean ok = gameId > 0 && RitoApiUtils.watchReplay(gameId);
        if (ok) {
            synchronized (lock) {
                pendingWatchId = id;
                pendingWatchAt = System.currentTimeMillis();
            }
        }
        return ok;
    }

    public static String launchedReplayMatch(LeagueGame replay) {
        String pending;
        synchronized (lock) {
            pending = pendingWatchId != null && System.currentTimeMillis() - pendingWatchAt < PENDING_WATCH_MS
                    ? pendingWatchId : null;
        }
        JSONObject record = pending == null ? null : recordCopy(pending);
        if (record == null) return null;

        int players = replay == null ? 0 : replay.players().size();
        return players == 0 || rosterMatches(record, replay) * 2 >= players ? pending : null;
    }

    public static void forgetLaunchedReplay() {
        synchronized (lock) {
            pendingWatchId = null;
        }
    }

    private static int rosterMatches(JSONObject record, LeagueGame replay) {
        if (replay == null) return 0;
        JSONObject stats = record.optJSONObject("stats");
        JSONArray saved = stats != null ? stats.optJSONArray("players") : null;
        if (saved == null) return 0;
        List<String> left = new ArrayList<>();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject p = saved.getJSONObject(i);
            left.add(p.optString("champion").toLowerCase() + "/" + p.optString("team").toLowerCase());
        }
        int matches = 0;
        for (LeaguePlayer p : replay.players()) {
            String key = String.valueOf(p.getChampionName()).toLowerCase() + "/" + String.valueOf(p.getTeam()).toLowerCase();
            if (left.remove(key)) matches++;
        }
        return matches;
    }

    private static long gameIdOf(String id) {
        synchronized (lock) {
            JSONObject record = recordFor(id);
            return record == null ? -1 : record.optLong("gameId", -1);
        }
    }

    public static boolean isValidId(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }

    private static JSONObject recordFor(String id) {
        if (!isValidId(id)) return null;
        if (current != null && id.equals(current.optString("id"))) return current;
        return readRecord(id);
    }

    private static JSONObject readRecord(String id) {
        Path file = ROOT.resolve(id).resolve(RECORD_FILE);
        if (!Files.exists(file)) return null;
        try {
            JSONObject record = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            if (!record.has("highlights")) record.put("highlights", new JSONArray());
            return record;
        } catch (Exception e) {
            DebugManager.logFailure("[History] Could not read " + file, e);
            return null;
        }
    }

    private static void writeRecord(JSONObject record) {
        Path file = ROOT.resolve(record.getString("id")).resolve(RECORD_FILE);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(RECORD_FILE + ".tmp");
            Files.writeString(temp, record.toString(2), StandardCharsets.UTF_8);
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[History] Could not save " + file + ": " + e.getMessage());
        }
    }

    private static List<JSONObject> finishedRecords() {
        List<JSONObject> records = new ArrayList<>();
        synchronized (lock) {
            for (Path dir : matchDirs()) {
                JSONObject record = readRecord(dir.getFileName().toString());
                if (record != null && record.has("stats")) records.add(record);
            }
        }
        records.sort(Comparator.comparingLong((JSONObject r) -> r.optLong("startedAt")).reversed());
        return records.size() > MAX_MATCHES ? records.subList(0, MAX_MATCHES) : records;
    }

    private static List<Path> matchDirs() {
        if (!Files.isDirectory(ROOT)) return List.of();
        try (Stream<Path> dirs = Files.list(ROOT)) {
            return dirs.filter(Files::isDirectory).filter(d -> isValidId(d.getFileName().toString())).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void prune() {
        synchronized (lock) {
            List<JSONObject> finished = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (Path dir : matchDirs()) {
                String id = dir.getFileName().toString();
                if (current != null && id.equals(current.optString("id")) && !current.has("stats")) continue;
                JSONObject record = readRecord(id);
                if (record != null && record.has("stats")) {
                    finished.add(record);
                } else if (now - lastModified(dir) > UNFINISHED_MAX_AGE_MS) {
                    deleteDir(dir);
                }
            }
            finished.sort(Comparator.comparingLong((JSONObject r) -> r.optLong("startedAt")).reversed());
            for (int i = MAX_MATCHES; i < finished.size(); i++) {
                deleteDir(ROOT.resolve(finished.get(i).getString("id")));
            }
        }
    }

    private static long lastModified(Path dir) {
        try {
            return Files.getLastModifiedTime(dir).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteDir(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            System.out.println("[History] Removed old match " + dir.getFileName() + ".");
        } catch (IOException e) {
            System.err.println("[History] Could not remove " + dir + ": " + e.getMessage());
        }
    }
}
