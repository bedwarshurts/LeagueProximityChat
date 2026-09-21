package me.bedwarshurts.leagueproximitychat.managers;

import lombok.Getter;
import retrofit2.http.GET;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class DebugManager {

    private static final boolean ENV_UNLOCKED = "true".equalsIgnoreCase(System.getenv("LPC_DEBUG"))
            || "1".equals(System.getenv("LPC_DEBUG"));

    private static volatile boolean debugDirReady = false;

    @Getter private static String debugDir = null;

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
            String appData = System.getenv("APPDATA");
            Path dir = (appData != null && !appData.isBlank())
                    ? Paths.get(appData, "LeagueProximityChat", "debug")
                    : Paths.get(System.getProperty("user.home"), ".leagueproximitychat", "debug");
            
            Files.createDirectories(dir);
            debugDir = dir.toString();

            Path debug = Paths.get("debug");
            Files.createDirectories(debug);
            // debugDir = debug.toAbsolutePath().toString();
        } catch (Exception e) {
            System.err.println("[Debug] Could not create the debug directory: " + e.getMessage());
        }
    }
}
