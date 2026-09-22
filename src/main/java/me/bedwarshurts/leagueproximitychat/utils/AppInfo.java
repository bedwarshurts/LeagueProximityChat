package me.bedwarshurts.leagueproximitychat.utils;

import java.io.InputStream;
import java.util.Properties;

public final class AppInfo {

    private static final String VERSION = loadVersion();

    private AppInfo() {
    }

    public static String version() {
        return VERSION;
    }

    private static String loadVersion() {
        try (InputStream in = AppInfo.class.getResourceAsStream("/app.properties")) {
            if (in == null) return "unknown";
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version", "").trim();
            if (version.isEmpty() || version.startsWith("${")) return "unknown";
            return version.replace("-SNAPSHOT", "");
        } catch (Exception e) {
            return "unknown";
        }
    }
}
