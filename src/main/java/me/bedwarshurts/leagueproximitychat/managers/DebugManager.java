package me.bedwarshurts.leagueproximitychat.managers;

import lombok.Getter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class DebugManager {

    private static final boolean ENV_UNLOCKED = "true".equalsIgnoreCase(System.getenv("LPC_DEBUG"))
            || "1".equals(System.getenv("LPC_DEBUG"));

    private static final boolean RUNNING_FROM_SOURCE = isRunningFromSource();

    private static volatile boolean debugDirReady = false;

    @Getter private static String debugDir = null;

    private DebugManager() {
    }

    public static boolean isENABLED() {
        if (!ENV_UNLOCKED || !ConfigManager.isDebugMode()) return false;
        if (!debugDirReady) ensureDebugDir();
        return true;
    }

    private static boolean isRunningFromSource() {
        try {
            return Files.isDirectory(Paths.get(DebugManager.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        } catch (Exception e) {
            return false;
        }
    }

    private static synchronized void ensureDebugDir() {
        if (debugDirReady) return;
        debugDirReady = true;
        try {
            Path dir;
            if (RUNNING_FROM_SOURCE) {
                dir = Paths.get("debug").toAbsolutePath();
            } else {
                String appData = System.getenv("APPDATA");
                dir = (appData != null && !appData.isBlank())
                        ? Paths.get(appData, "LeagueProximityChat", "debug")
                        : Paths.get(System.getProperty("user.home"), ".leagueproximitychat", "debug");
            }

            Files.createDirectories(dir);
            debugDir = dir.toString();
        } catch (Exception e) {
            System.err.println("[Debug] Could not create the debug directory: " + e.getMessage());
        }
    }
}
