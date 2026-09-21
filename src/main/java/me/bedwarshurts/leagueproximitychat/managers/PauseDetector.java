package me.bedwarshurts.leagueproximitychat.managers;

import me.bedwarshurts.leagueproximitychat.utils.RitoApiUtils;
import me.bedwarshurts.leagueproximitychat.websocket.CoordinateServer;
import org.json.JSONObject;

public final class PauseDetector {

    private static final long FREEZE_MS = 1500;
    private static final double EPSILON = 0.001;

    private static double lastGameTime = -1;
    private static long lastChangeMs = 0;
    private static boolean seenAdvance = false;
    private static volatile boolean paused = false;

    private PauseDetector() {
    }

    public static boolean isPaused() {
        return paused;
    }

    public static synchronized void poll(CoordinateServer server) {
        double gameTime = RitoApiUtils.getGameTime();
        long now = System.currentTimeMillis();

        if (gameTime < 0) {
            lastGameTime = -1;
            seenAdvance = false;
            setPaused(false, server);
            return;
        }

        if (lastGameTime < 0 || Math.abs(gameTime - lastGameTime) > EPSILON) {
            if (lastGameTime >= 0 && gameTime > lastGameTime) {
                seenAdvance = true;
            } else if (lastGameTime >= 0) {
                seenAdvance = false;
            }
            lastGameTime = gameTime;
            lastChangeMs = now;
            setPaused(false, server);
            return;
        }

        if (seenAdvance && now - lastChangeMs >= FREEZE_MS) {
            setPaused(true, server);
        }
    }

    private static void setPaused(boolean value, CoordinateServer server) {
        if (paused == value) return;
        paused = value;
        System.out.println(value
                ? "[Pause] Game paused - everyone can hear each other, position tracking suspended."
                : "[Pause] Game resumed - proximity voice and position tracking restored.");
        if (server != null) {
            server.sendToActive(new JSONObject().put("type", "GAME_PAUSED").put("paused", value).toString());
        }
    }
}
