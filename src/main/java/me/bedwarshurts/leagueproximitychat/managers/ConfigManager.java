package me.bedwarshurts.leagueproximitychat.managers;

import lombok.Getter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public final class ConfigManager {

    private static final Path CONFIG_FILE = resolveConfigFile();

    @Getter private static volatile String livekitUrl = "";
    @Getter private static volatile String apiKey = "";
    @Getter private static volatile String apiSecret = "";
    /** Skips all play-of-the-game clip recording (screen capture + frame ring) to save CPU. */
    @Getter private static volatile boolean lowPerformanceMode = false;

    static {
        load();
    }

    private ConfigManager() {
    }

    private static Path resolveConfigFile() {
        String appData = System.getenv("APPDATA");
        Path dir = (appData != null && !appData.isBlank())
                ? Paths.get(appData, "LeagueProximityChat")
                : Paths.get(System.getProperty("user.home"), ".leagueproximitychat");
        return dir.resolve("livekit.properties");
    }

    public static synchronized void load() {
        if (!Files.exists(CONFIG_FILE)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(CONFIG_FILE)) {
            props.load(in);
            livekitUrl = normalizeUrl(props.getProperty("livekit.url", ""));
            apiKey = props.getProperty("livekit.apiKey", "").trim();
            apiSecret = props.getProperty("livekit.apiSecret", "").trim();
            lowPerformanceMode = Boolean.parseBoolean(props.getProperty("app.lowPerformanceMode", "false"));
        } catch (IOException e) {
            System.err.println("[Config] Failed to read " + CONFIG_FILE + ": " + e.getMessage());
        }
    }

    public static synchronized boolean save(String url, String key, String secret, boolean lowPerformance) {
        String normalizedUrl = normalizeUrl(url);
        if (normalizedUrl.isBlank() || key.isBlank() || secret.isBlank()) {
            return false;
        }

        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            Properties props = new Properties();
            props.setProperty("livekit.url", normalizedUrl);
            props.setProperty("livekit.apiKey", key.trim());
            props.setProperty("livekit.apiSecret", secret.trim());
            props.setProperty("app.lowPerformanceMode", String.valueOf(lowPerformance));
            try (OutputStream out = Files.newOutputStream(CONFIG_FILE)) {
                props.store(out, "League Proximity Chat - LiveKit credentials (must match your teammates')");
            }

            livekitUrl = normalizedUrl;
            apiKey = key.trim();
            apiSecret = secret.trim();
            lowPerformanceMode = lowPerformance;
            System.out.println("[Config] Settings saved to " + CONFIG_FILE
                    + (lowPerformance ? " (low performance mode ON - clip recording disabled)" : ""));
            return true;
        } catch (IOException e) {
            System.err.println("[Config] Failed to save settings: " + e.getMessage());
            return false;
        }
    }

    private static String normalizeUrl(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) {
            return u;
        }
        if (u.startsWith("wss://")) {
            u = "https://" + u.substring(6);
        } else if (u.startsWith("ws://")) {
            u = "http://" + u.substring(5);
        } else if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://" + u;
        }
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    public static boolean isConfigured() {
        return !livekitUrl.isBlank() && !apiKey.isBlank() && !apiSecret.isBlank();
    }

}
