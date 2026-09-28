package me.bedwarshurts.leagueproximitychat.managers;

import lombok.Getter;
import me.bedwarshurts.leagueproximitychat.utils.ReplayApiConfig;
import me.bedwarshurts.leagueproximitychat.utils.RitoApiUtils;
import me.bedwarshurts.leagueproximitychat.websocket.CoordinateServer;
import org.json.JSONObject;

import java.util.function.BooleanSupplier;

public final class ReplayVoiceManager {

    private static final long IDLE_POLL_MS = 2000;
    private static final long ACTIVE_POLL_MS = 100;
    private static final long NO_API_POLL_MS = 1000;
    private static final long SPECTATE_RECHECK_MS = 2000;
    private static final long IDENTIFY_RETRY_MS = 3000;
    private static final long ANNOUNCE_MS = 5000;
    private static final int SPECTATE_CONFIRMATIONS = 2;
    private static final int END_CONFIRMATIONS = 3;

    @Getter private static volatile boolean replayRunning = false;

    private static int spectateStreak = 0;
    private static int notSpectatingStreak = 0;
    private static String matchId = null;
    private static long lastIdentifyMs = 0;
    private static long lastSpectateCheckMs = 0;
    private static long lastAnnounceMs = 0;
    private static Boolean apiAvailable = null;
    private static String loggedCameraMode = null;

    private ReplayVoiceManager() {
    }

    public static void start(CoordinateServer server, BooleanSupplier liveMatchActive) {
        Thread thread = new Thread(() -> {
            while (true) {
                long wait;
                try {
                    wait = tick(server, liveMatchActive.getAsBoolean());
                } catch (Exception e) {
                    DebugManager.logFailure("[Replay] Replay check failed", e);
                    wait = IDLE_POLL_MS;
                }
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "replay-voice");
        thread.setDaemon(true);
        thread.start();
    }

    private static long tick(CoordinateServer server, boolean liveMatch) {
        long now = System.currentTimeMillis();
        if (liveMatch) {
            if (replayRunning) end(server);
            spectateStreak = 0;
            return IDLE_POLL_MS;
        }

        if (!replayRunning) {
            if (!RitoApiUtils.isSpectating()) {
                spectateStreak = 0;
                return IDLE_POLL_MS;
            }
            if (++spectateStreak < SPECTATE_CONFIRMATIONS) return IDLE_POLL_MS;
            begin();
            lastSpectateCheckMs = now;
        } else if (now - lastSpectateCheckMs >= SPECTATE_RECHECK_MS) {
            lastSpectateCheckMs = now;
            if (RitoApiUtils.isSpectating()) {
                notSpectatingStreak = 0;
            } else if (++notSpectatingStreak >= END_CONFIRMATIONS) {
                end(server);
                return IDLE_POLL_MS;
            }
        }

        if (matchId == null && now - lastIdentifyMs >= IDENTIFY_RETRY_MS) identify(server);

        JSONObject playback = RitoApiUtils.getReplayApi("/replay/playback");
        if (playback == null) {
            setApiAvailable(false, server);
            announce(server, now);
            return NO_API_POLL_MS;
        }
        setApiAvailable(true, server);
        announce(server, now);
        if (matchId == null) return NO_API_POLL_MS;

        JSONObject render = RitoApiUtils.getReplayApi("/replay/render");
        JSONObject state = new JSONObject()
                .put("type", "REPLAY_STATE")
                .put("at", System.currentTimeMillis())
                .put("time", playback.optDouble("time", 0))
                .put("length", playback.optDouble("length", 0))
                .put("speed", playback.optDouble("speed", 1))
                .put("paused", playback.optBoolean("paused", false))
                .put("seeking", playback.optBoolean("seeking", false));
        if (render != null) {
            JSONObject position = render.optJSONObject("cameraPosition");
            JSONObject rotation = render.optJSONObject("cameraRotation");
            if (position != null && rotation != null) {
                String mode = render.optString("cameraMode", "");
                state.put("cam", new JSONObject()
                        .put("mode", mode)
                        .put("x", position.optDouble("x", 0))
                        .put("y", position.optDouble("y", 0))
                        .put("z", position.optDouble("z", 0))
                        .put("yaw", rotation.optDouble("x", 0))
                        .put("pitch", rotation.optDouble("y", 0))
                        .put("attached", render.optBoolean("cameraAttached", false))
                        .put("selection", render.optString("selectionName", "")));
                if (!mode.equals(loggedCameraMode)) {
                    loggedCameraMode = mode;
                    System.out.printf("[Replay] Camera mode %s at (%.0f, %.0f, %.0f), rotation (%.1f, %.1f), replay time %.1f of %.1f s.%n",
                            mode, position.optDouble("x"), position.optDouble("y"), position.optDouble("z"),
                            rotation.optDouble("x"), rotation.optDouble("y"),
                            playback.optDouble("time"), playback.optDouble("length"));
                }
            }
        }
        server.sendToActive(state.toString());
        return ACTIVE_POLL_MS;
    }

    private static void begin() {
        replayRunning = true;
        notSpectatingStreak = 0;
        matchId = null;
        apiAvailable = null;
        lastIdentifyMs = 0;
        lastAnnounceMs = 0;
        loggedCameraMode = null;
        System.out.println("[Replay] A replay is running.");
    }

    private static void identify(CoordinateServer server) {
        lastIdentifyMs = System.currentTimeMillis();
        JSONObject session = RitoApiUtils.getGameflowSession();
        JSONObject gameData = session != null ? session.optJSONObject("gameData") : null;
        long gameId = gameData != null ? gameData.optLong("gameId", -1) : -1;

        String found = MatchHistoryManager.launchedReplayMatch(RitoApiUtils.getLivePlayerList(), gameId);
        if (found == null) return;
        matchId = found;
        JSONObject voice = VoiceArchiveManager.summary(found);
        System.out.println("[Replay] Watching saved match " + found + (voice == null
                ? " - no voice was recorded in it."
                : " - " + voice.getInt("utterances") + " voice clips from " + voice.getInt("speakers") + " players."));
        lastAnnounceMs = 0;
        announce(server, System.currentTimeMillis());
    }

    private static void setApiAvailable(boolean available, CoordinateServer server) {
        if (apiAvailable != null && apiAvailable == available) return;
        apiAvailable = available;
        if (!available) {
            System.out.println("[Replay] The Replay API is not answering"
                    + (ReplayApiConfig.isEnabled() ? "." : " - it is off in game.cfg, so voice can't follow the replay."));
        }
        lastAnnounceMs = 0;
        announce(server, System.currentTimeMillis());
    }

    private static void announce(CoordinateServer server, long now) {
        if (matchId == null || now - lastAnnounceMs < ANNOUNCE_MS) return;
        lastAnnounceMs = now;
        server.sendToActive(new JSONObject()
                .put("type", "REPLAY_STARTED")
                .put("matchId", matchId)
                .put("apiAvailable", apiAvailable == null || apiAvailable)
                .put("hasVoice", VoiceArchiveManager.summary(matchId) != null)
                .toString());
    }

    private static void end(CoordinateServer server) {
        if (!replayRunning) return;
        replayRunning = false;
        spectateStreak = 0;
        matchId = null;
        MatchHistoryManager.forgetLaunchedReplay();
        System.out.println("[Replay] The replay closed.");
        server.sendToActive("{\"type\":\"REPLAY_ENDED\"}");
    }
}
