package me.bedwarshurts.leagueproximitychat.managers;

import lombok.Getter;

import java.nio.file.Files;
import java.nio.file.Paths;

public final class DebugManager {

    @Getter private static final boolean ENABLED = "true".equalsIgnoreCase(System.getenv("LPC_DEBUG"))
            || "1".equals(System.getenv("LPC_DEBUG"));

    static {
        if (ENABLED) {
            try {
                System.out.println("Debug mode is enabled. This reduces performance.");
                Files.createDirectories(Paths.get("debug"));
            } catch (Exception e) {
                System.err.println("[Debug] Could not create the debug directory: " + e.getMessage());
            }
        }
    }

    private DebugManager() {
    }
}
