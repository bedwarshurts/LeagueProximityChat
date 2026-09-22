package me.bedwarshurts.leagueproximitychat.app;

import java.io.InputStream;
import java.util.Properties;

public final class AppInfo {

    private static final Properties PROPERTIES = loadProperties();
    private static final String VERSION = loadVersion();
    private static final String BUILD_LABEL = loadBuildLabel();

    private AppInfo() {
    }

    public static String version() {
        return VERSION;
    }

    public static String buildLabel() {
        return BUILD_LABEL;
    }

    public static String fullVersion() {
        return VERSION + " " + BUILD_LABEL.toLowerCase();
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        try (InputStream in = AppInfo.class.getResourceAsStream("/app.properties")) {
            if (in != null) properties.load(in);
        } catch (Exception ignored) {
        }
        return properties;
    }

    private static String value(String key) {
        String value = PROPERTIES.getProperty(key, "").trim();
        return (value.isEmpty() || value.startsWith("${")) ? null : value;
    }

    private static String loadVersion() {
        String version = value("version");
        return version == null ? "unknown" : version.replace("-SNAPSHOT", "");
    }

    private static String loadBuildLabel() {
        String build = value("build");
        String commit = value("commit");
        if (build == null || commit == null) return "Dev build";
        boolean dirty = "true".equalsIgnoreCase(value("dirty"));
        return "Build " + build + " (" + commit + (dirty ? "-dirty" : "") + ")";
    }
}
