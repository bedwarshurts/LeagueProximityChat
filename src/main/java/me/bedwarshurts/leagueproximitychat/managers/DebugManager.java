package me.bedwarshurts.leagueproximitychat.managers;

import java.nio.file.Files;
import java.nio.file.Paths;

public final class DebugManager {

    private static final boolean ENV_UNLOCKED = "true".equalsIgnoreCase(System.getenv("LPC_DEBUG"))
            || "1".equals(System.getenv("LPC_DEBUG"));

    private static volatile boolean debugDirReady = false;

    private DebugManager() {
    }

    public static boolean isENABLED() {
        if (!ENV_UNLOCKED || !ConfigManager.isDebugMode()) return false;
        if (!debugDirReady) ensureDebugDir();
        return true;
    }

    private static synchronized void ensureDebugDir() {
        if (debugDirReady) return;
        debugDirReady = true;
        try {
            Files.createDirectories(Paths.get("debug"));
        } catch (Exception e) {
            System.err.println("[Debug] Could not create the debug directory: " + e.getMessage());
        }
    }
}
