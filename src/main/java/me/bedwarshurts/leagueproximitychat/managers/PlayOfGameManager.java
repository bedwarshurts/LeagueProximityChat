package me.bedwarshurts.leagueproximitychat.managers;

import me.bedwarshurts.leagueproximitychat.data.LeagueGame;
import me.bedwarshurts.leagueproximitychat.data.LeaguePlayer;
import me.bedwarshurts.leagueproximitychat.utils.RitoApiUtils;
import me.bedwarshurts.leagueproximitychat.websocket.CoordinateServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class PlayOfGameManager {

    private static final double CLIP_BEFORE_S = 8.0;
    private static final double CLIP_AFTER_S = 4.0;
    private static final int MIN_CLIP_FRAMES = 8;
    private static final int MAX_HIGHLIGHTS = 5;

    private static final double MULTIKILL_LEAD_S = 10.0;
    private static final double MULTIKILL_GAP_S = 12.0;
    private static final double MAX_CLIP_S = 24.0;

    private record Candidate(double eventTime, double clipStartS, double clipEndS, int score, String headline) {
    }

    private enum Credit {KILL, ASSIST, OBJECTIVE}

    private record LocalIdentity(String riotId, String gameName, boolean hasDoppelganger,
                                 boolean localGainedKills, boolean rivalGainedKills,
                                 boolean localGainedAssists, boolean rivalGainedAssists) {
    }

    private record Clip(List<ClipRecorder.Frame> frames, long startEpochMs, long endEpochMs,
                        String headline, int score, double gameTime) {
    }

    private static final Object lock = new Object();
    private static int lastEventId = -1;
    private static double lastGameTime = -1;
    private static final List<Candidate> pendings = new ArrayList<>();
    private static final List<Clip> highlights = new ArrayList<>();
    private static boolean loggedFeedOnce = false;
    private static final Map<String, int[]> prevScores = new HashMap<>();
    private static final List<Double> recentLocalKills = new ArrayList<>();

    private static LeagueGame statsGame = null;
    private static String statsLocalName = null;
    private static double statsGameTime = 0;
    private static String gameResult = null;
    private static int statsMapNumber = 0;
    private static String statsGameMode = "";
    private static boolean capturedMapInfo = false;

    private static Map<String, long[]> eogStats = null;
    private static String eogResult = null;

    private PlayOfGameManager() {
    }

    public static void poll(CoordinateServer server) {
        double gameTime = RitoApiUtils.getGameTime();
        if (gameTime < 0) return;

        synchronized (lock) {
            if (lastGameTime > 60 && gameTime < lastGameTime - 60) {
                resetForNewGame();
            }
            lastGameTime = gameTime;
        }

        String localName = RitoApiUtils.getLocalSummonerName();
        if (localName == null || localName.isEmpty()) return;

        LeagueGame gameData = RitoApiUtils.getLivePlayerList();
        LocalIdentity ctx = buildIdentity(localName, gameData);

        if (gameData != null && gameData.players() != null && !gameData.players().isEmpty()) {
            synchronized (lock) {
                statsGame = gameData;
                statsLocalName = localName;
                statsGameTime = gameTime;
            }
            if (!capturedMapInfo) {
                JSONObject gs = RitoApiUtils.getGameStats();
                if (gs != null) {
                    synchronized (lock) {
                        statsMapNumber = gs.optInt("mapNumber", 0);
                        statsGameMode = gs.optString("gameMode", "");
                        capturedMapInfo = true;
                    }
                }
            }
        }

        String raw = RitoApiUtils.fetchAPI("https://127.0.0.1:2999/liveclientdata/eventdata");
        if (raw == null || raw.isEmpty()) return;

        long epochOffset = System.currentTimeMillis() - (long) (gameTime * 1000);

        try {
            JSONArray events = new JSONObject(raw).optJSONArray("Events");
            if (events == null) return;

            if (!loggedFeedOnce) {
                loggedFeedOnce = true;
                System.out.printf("[PotG] Event feed online (%d events at %s), watching for plays by %s.%n",
                        events.length(), gameClock(gameTime), localName);
            }

            for (int i = 0; i < events.length(); i++) {
                JSONObject e = events.getJSONObject(i);
                int id = e.optInt("EventID", -1);

                boolean fresh;
                synchronized (lock) {
                    fresh = id > lastEventId;
                    if (fresh) lastEventId = id;
                }
                if (!fresh) continue;

                if ("GameEnd".equals(e.optString("EventName"))) {
                    String result = e.optString("Result", "");
                    synchronized (lock) {
                        gameResult = result.isEmpty() ? null : result;
                    }
                    continue;
                }

                Candidate c = scoreEvent(e, ctx);
                if (c == null) continue;

                System.out.printf("[PotG] Candidate play: %s (score %d) at %s.%n",
                        c.headline(), c.score(), gameClock(c.eventTime()));

                synchronized (lock) {
                    offerPending(c);
                }
            }

            List<Candidate> ripe = new ArrayList<>();
            synchronized (lock) {
                for (Iterator<Candidate> it = pendings.iterator(); it.hasNext(); ) {
                    Candidate p = it.next();
                    if (gameTime > p.clipEndS() + 0.5) {
                        it.remove();
                        ripe.add(p);
                    }
                }
            }
            for (Candidate p : ripe) {
                long startMs = epochOffset + (long) (p.clipStartS() * 1000);
                long endMs = epochOffset + (long) (p.clipEndS() * 1000);
                List<ClipRecorder.Frame> frames = ClipRecorder.snapshot(startMs, endMs);

                if (frames.size() < MIN_CLIP_FRAMES) {
                    System.out.printf("[PotG] Discarding %s clip - only %d frames were captured in its window "
                            + "(position tracking not running / game unfocused?).%n", p.headline(), frames.size());
                    continue;
                }
                synchronized (lock) {
                    insertHighlight(new Clip(frames, startMs, endMs, p.headline(), p.score(), p.eventTime()), server);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void offerPending(Candidate c) {
        for (int i = 0; i < pendings.size(); i++) {
            Candidate p = pendings.get(i);
            if (c.clipStartS() < p.clipEndS() && c.clipEndS() > p.clipStartS()) {
                if (c.score() > p.score()) pendings.set(i, c);
                return;
            }
        }
        if (highlights.size() >= MAX_HIGHLIGHTS
                && c.score() <= highlights.getLast().score()) {
            return;
        }
        pendings.add(c);
    }

    private static void insertHighlight(Clip clip, CoordinateServer server) {
        for (int i = 0; i < highlights.size(); i++) {
            Clip h = highlights.get(i);
            if (clip.startEpochMs() < h.endEpochMs() && clip.endEpochMs() > h.startEpochMs()) {
                if (clip.score() <= h.score()) return;
                highlights.set(i, clip);
                announceHighlight(clip, server);
                return;
            }
        }
        if (highlights.size() >= MAX_HIGHLIGHTS
                && clip.score() <= highlights.getLast().score()) {
            return;
        }
        highlights.add(clip);
        announceHighlight(clip, server);
    }

    private static void announceHighlight(Clip clip, CoordinateServer server) {
        highlights.sort(Comparator.comparingInt(Clip::score).reversed());
        while (highlights.size() > MAX_HIGHLIGHTS) highlights.removeLast();

        int rank = highlights.indexOf(clip);
        if (rank < 0) return;
        System.out.printf("[PotG] Highlight locked: %s (score %d) at %s - %d frames (#%d of %d).%n",
                clip.headline(), clip.score(), gameClock(clip.gameTime()), clip.frames().size(),
                rank + 1, highlights.size());

        JSONArray marks = new JSONArray();
        for (Clip h : highlights) {
            marks.put(new JSONObject()
                    .put("id", String.valueOf(h.startEpochMs()))
                    .put("startEpochMs", h.startEpochMs())
                    .put("endEpochMs", h.endEpochMs())
                    .put("headline", h.headline()));
        }
        server.sendToActive(new JSONObject().put("type", "POTG_MARKS").put("marks", marks).toString());
    }

    private static boolean isLocalActor(String actor, LocalIdentity ctx, Credit credit) {
        if (actor == null || actor.isEmpty()) return false;
        if (actor.indexOf('#') >= 0) return actor.equalsIgnoreCase(ctx.riotId());
        if (!actor.equalsIgnoreCase(ctx.gameName())) return false;
        if (!ctx.hasDoppelganger()) return true;

        return switch (credit) {
            case KILL -> ctx.localGainedKills() && !ctx.rivalGainedKills();
            case ASSIST -> ctx.localGainedAssists() && !ctx.rivalGainedAssists();
            case OBJECTIVE -> {
                System.out.println("[PotG] Ambiguous actor '" + actor
                        + "' (another player shares this game name) - skipping objective credit.");
                yield false;
            }
        };
    }

    private static LocalIdentity buildIdentity(String localRiotId, LeagueGame gameData) {
        int hash = localRiotId.indexOf('#');
        String gameName = hash > 0 ? localRiotId.substring(0, hash) : localRiotId;

        boolean doppelganger = false;
        boolean localKills = false;
        boolean rivalKills = false;
        boolean localAssists = false;
        boolean rivalAssists = false;

        if (gameData != null) {
            for (LeaguePlayer p : gameData.players()) {
                String rid = p.getRiotId();
                if (rid == null || rid.isEmpty()) continue;
                int h = rid.indexOf('#');
                String gn = h > 0 ? rid.substring(0, h) : rid;

                int kills = p.getScore() != null ? p.getScore().kills() : 0;
                int assists = p.getScore() != null ? p.getScore().assists() : 0;
                int[] prev = prevScores.get(rid);
                boolean gainedKills = prev != null && kills > prev[0];
                boolean gainedAssists = prev != null && assists > prev[1];
                prevScores.put(rid, new int[]{kills, assists});

                if (rid.equalsIgnoreCase(localRiotId)) {
                    localKills = gainedKills;
                    localAssists = gainedAssists;
                } else if (gn.equalsIgnoreCase(gameName)) {
                    doppelganger = true;
                    rivalKills |= gainedKills;
                    rivalAssists |= gainedAssists;
                }
            }
        }
        return new LocalIdentity(localRiotId, gameName, doppelganger,
                localKills, rivalKills, localAssists, rivalAssists);
    }

    private static Candidate scoreEvent(JSONObject e, LocalIdentity ctx) {
        String name = e.optString("EventName", "");
        double t = e.optDouble("EventTime", -1);
        if (t < 0) return null;

        String killer = e.optString("KillerName", "");

        switch (name) {
            case "Multikill" -> {
                if (!isLocalActor(killer, ctx, Credit.KILL)) return null;
                recordLocalKill(t);
                int streak = e.optInt("KillStreak", 2);
                int score = switch (streak) {
                    case 2 -> 40;
                    case 3 -> 80;
                    case 4 -> 160;
                    default -> 300;
                };
                String headline = switch (streak) {
                    case 2 -> "DOUBLE KILL";
                    case 3 -> "TRIPLE KILL";
                    case 4 -> "QUADRA KILL";
                    default -> "PENTAKILL";
                };
                double clipStart = streakStart(t, streak) - MULTIKILL_LEAD_S;
                return makeCandidate(t, clipStart, score, headline);
            }
            case "ChampionKill" -> {
                if (isLocalActor(killer, ctx, Credit.KILL)) {
                    recordLocalKill(t);
                    return makeCandidate(t, t - CLIP_BEFORE_S, 10, "CHAMPION KILL");
                }
                JSONArray assisters = e.optJSONArray("Assisters");
                if (assisters != null) {
                    for (int i = 0; i < assisters.length(); i++) {
                        if (isLocalActor(assisters.optString(i, ""), ctx, Credit.ASSIST)) {
                            return makeCandidate(t, t - CLIP_BEFORE_S, 5, "TEAM FIGHT");
                        }
                    }
                }
                return null;
            }
            case "FirstBlood" -> {
                if (isLocalActor(e.optString("Recipient", ""), ctx, Credit.KILL)) {
                    recordLocalKill(t);
                    return makeCandidate(t, t - CLIP_BEFORE_S, 25, "FIRST BLOOD");
                }
                return null;
            }
            case "DragonKill" -> {
                if (!isLocalActor(killer, ctx, Credit.OBJECTIVE)) return null;
                boolean stolen = "True".equalsIgnoreCase(e.optString("Stolen", "False"));
                return makeCandidate(t, t - CLIP_BEFORE_S, stolen ? 90 : 45, stolen ? "DRAGON STEAL" : "DRAGON KILL");
            }
            case "HeraldKill" -> {
                if (isLocalActor(killer, ctx, Credit.OBJECTIVE)) return makeCandidate(t, t - CLIP_BEFORE_S, 30, "HERALD KILL");
                return null;
            }
            case "BaronKill" -> {
                if (!isLocalActor(killer, ctx, Credit.OBJECTIVE)) return null;
                boolean stolen = "True".equalsIgnoreCase(e.optString("Stolen", "False"));
                return makeCandidate(t, t - CLIP_BEFORE_S, stolen ? 150 : 90, stolen ? "BARON STEAL" : "BARON KILL");
            }
            case "Ace" -> {
                if (isLocalActor(e.optString("Acer", ""), ctx, Credit.KILL)) {
                    return makeCandidate(t, t - CLIP_BEFORE_S, 55, "ACE");
                }
                return null;
            }
            case "TurretKilled" -> {
                if (isLocalActor(killer, ctx, Credit.OBJECTIVE)) return makeCandidate(t, t - CLIP_BEFORE_S, 12, "TURRET DESTROYED");
                return null;
            }
            case "InhibKilled" -> {
                if (isLocalActor(killer, ctx, Credit.OBJECTIVE)) return makeCandidate(t, t - CLIP_BEFORE_S, 20, "INHIBITOR DESTROYED");
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private static Candidate makeCandidate(double eventTime, double clipStartS, int score, String headline) {
        double clipEndS = eventTime + CLIP_AFTER_S;
        if (clipEndS - clipStartS > MAX_CLIP_S) clipStartS = clipEndS - MAX_CLIP_S;
        return new Candidate(eventTime, clipStartS, clipEndS, score, headline);
    }

    private static void recordLocalKill(double t) {
        synchronized (lock) {
            for (double k : recentLocalKills) {
                if (Math.abs(k - t) < 0.1) return;
            }
            recentLocalKills.add(t);
            recentLocalKills.removeIf(k -> k < t - 90.0);
        }
    }

    private static double streakStart(double lastKillTime, int streakN) {
        List<Double> kills;
        synchronized (lock) {
            kills = new ArrayList<>(recentLocalKills);
        }
        Collections.sort(kills);
        double start = lastKillTime;
        int counted = 1;
        for (int i = kills.size() - 1; i >= 0; i--) {
            double k = kills.get(i);
            if (k >= start - 0.05) continue;
            if (start - k <= MULTIKILL_GAP_S) {
                start = k;
                if (++counted >= streakN) break;
            } else {
                break;
            }
        }
        return start;
    }

    public static String metaJson() {
        synchronized (lock) {
            JSONArray clips = new JSONArray();
            for (Clip h : highlights) {
                JSONArray rel = new JSONArray();
                for (ClipRecorder.Frame f : h.frames()) {
                    rel.put(f.epochMs() - h.startEpochMs());
                }
                clips.put(new JSONObject()
                        .put("headline", h.headline())
                        .put("score", h.score())
                        .put("gameClock", gameClock(h.gameTime()))
                        .put("startEpochMs", h.startEpochMs())
                        .put("endEpochMs", h.endEpochMs())
                        .put("frames", rel));
            }
            return new JSONObject()
                    .put("available", !highlights.isEmpty())
                    .put("clips", clips)
                    .toString();
        }
    }

    public static byte[] frameBytes(int clip, int index) {
        synchronized (lock) {
            if (clip < 0 || clip >= highlights.size()) return null;
            List<ClipRecorder.Frame> frames = highlights.get(clip).frames();
            if (index < 0 || index >= frames.size()) return null;
            return frames.get(index).jpeg();
        }
    }

    public static String statsJson() {
        boolean haveGame;
        synchronized (lock) {
            haveGame = statsGame != null;
        }
        if (haveGame) loadEndOfGameStats();

        synchronized (lock) {
            if (statsGame == null) {
                return new JSONObject().put("available", false).toString();
            }
            JSONArray players = new JSONArray();
            for (LeaguePlayer p : statsGame.players()) {
                JSONArray items = new JSONArray();
                if (p.getItems() != null) {
                    List<LeaguePlayer.Item> sorted = new ArrayList<>(p.getItems());
                    sorted.sort(Comparator.comparingInt(LeaguePlayer.Item::slot));
                    for (LeaguePlayer.Item it : sorted) items.put(it.itemID());
                }
                LeaguePlayer.Score s = p.getScore();
                long[] gd = lookupEog(p);
                players.put(new JSONObject()
                        .put("riotId", p.getRiotId() == null ? "" : p.getRiotId())
                        .put("champion", p.getChampionName() == null ? "" : p.getChampionName())
                        .put("team", p.getTeam() == null ? "" : p.getTeam())
                        .put("level", p.getLevel())
                        .put("isBot", p.isBot())
                        .put("skinId", p.getEffectiveSkinId())
                        .put("kills", s != null ? s.kills() : 0)
                        .put("deaths", s != null ? s.deaths() : 0)
                        .put("assists", s != null ? s.assists() : 0)
                        .put("creepScore", s != null ? s.creepScore() : 0)
                        .put("wardScore", s != null ? Math.round(s.wardScore()) : 0)
                        .put("gold", gd != null ? gd[0] : -1)
                        .put("damage", gd != null ? gd[1] : -1)
                        .put("items", items));
            }
            String result = eogResult != null ? eogResult : gameResult;
            return new JSONObject()
                    .put("available", true)
                    .put("result", result == null ? JSONObject.NULL : result)
                    .put("gameClock", gameClock(statsGameTime))
                    .put("mapNumber", statsMapNumber)
                    .put("gameMode", statsGameMode == null ? "" : statsGameMode)
                    .put("localIdentity", statsLocalName == null ? "" : statsLocalName)
                    .put("players", players)
                    .toString();
        }
    }

    private static long[] lookupEog(LeaguePlayer p) {
        if (eogStats == null) return null;
        String rid = p.getRiotId();
        if (rid != null && eogStats.containsKey(rid.toLowerCase())) return eogStats.get(rid.toLowerCase());
        String gn = p.getRiotIdGameName();
        if (gn != null && eogStats.containsKey(gn.toLowerCase())) return eogStats.get(gn.toLowerCase());
        String champ = p.getChampionName();
        if (champ != null && eogStats.containsKey("champ:" + champ.toLowerCase())) return eogStats.get("champ:" + champ.toLowerCase());
        return null;
    }

    private static void loadEndOfGameStats() {
        synchronized (lock) {
            if (eogStats != null) return;
        }
        String raw = RitoApiUtils.getEndOfGameStatsBlock();
        if (raw == null || raw.isEmpty()) return;

        Map<String, long[]> map = new HashMap<>();
        String result = null;
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray teams = root.optJSONArray("teams");
            if (teams == null) return;
            for (int t = 0; t < teams.length(); t++) {
                JSONObject team = teams.getJSONObject(t);
                if (team.optBoolean("isPlayerTeam", false)) {
                    result = team.optBoolean("isWinningTeam", false) ? "Win" : "Lose";
                }
                JSONArray tp = team.optJSONArray("players");
                if (tp == null) continue;
                for (int i = 0; i < tp.length(); i++) {
                    JSONObject pl = tp.getJSONObject(i);
                    JSONObject st = pl.optJSONObject("stats");
                    long gold = eogStat(pl, st, "GOLD_EARNED", "goldEarned");
                    long dmg = eogStat(pl, st, "TOTAL_DAMAGE_DEALT_TO_CHAMPIONS", "totalDamageDealtToChampions");

                    String gn = pl.optString("riotIdGameName", pl.optString("summonerName", ""));
                    String tl = pl.optString("riotIdTagLine", "");
                    String champ = pl.optString("championName", pl.optString("skinName", ""));
                    long[] v = new long[]{gold, dmg};
                    if (!gn.isEmpty() && !tl.isEmpty()) map.put((gn + "#" + tl).toLowerCase(), v);
                    if (!gn.isEmpty()) map.putIfAbsent(gn.toLowerCase(), v);
                    if (!champ.isEmpty()) map.putIfAbsent("champ:" + champ.toLowerCase(), v);
                }
            }
        } catch (Exception e) {
            System.out.println("[PotG] Could not parse end-of-game stats: " + e.getMessage());
            return;
        }
        if (map.isEmpty()) return;

        synchronized (lock) {
            if (eogStats == null) {
                eogStats = map;
                if (result != null) eogResult = result;
            }
        }
        System.out.printf("[PotG] End-of-game stats loaded (%d entries)%s.%n",
                map.size(), result != null ? " - result " + result : "");
    }

    private static long eogStat(JSONObject player, JSONObject stats, String screamingKey, String camelKey) {
        if (stats != null) {
            if (stats.has(screamingKey)) return (long) stats.optDouble(screamingKey, 0);
            if (stats.has(camelKey)) return (long) stats.optDouble(camelKey, 0);
        }
        if (player.has(screamingKey)) return (long) player.optDouble(screamingKey, 0);
        if (player.has(camelKey)) return (long) player.optDouble(camelKey, 0);
        return 0;
    }

    private static void resetForNewGame() {
        lastEventId = -1;
        pendings.clear();
        highlights.clear();
        loggedFeedOnce = false;
        prevScores.clear();
        recentLocalKills.clear();
        statsGame = null;
        statsLocalName = null;
        statsGameTime = 0;
        gameResult = null;
        statsMapNumber = 0;
        statsGameMode = "";
        capturedMapInfo = false;
        eogStats = null;
        eogResult = null;
        ClipRecorder.clear();
    }

    private static String gameClock(double t) {
        int s = (int) t;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
