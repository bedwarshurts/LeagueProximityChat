package me.bedwarshurts.leagueproximitychat.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReplayApiConfig {

    private static final String DEFAULT_CONFIG_DIR = "C:/Riot Games/League of Legends/Config";
    private static final String SETTING = "EnableReplayApi=1";
    private static final Pattern SETTING_LINE = Pattern.compile("^[ \\t]*EnableReplayApi[ \\t]*=[ \\t]*(\\S*)[ \\t]*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERAL_SECTION = Pattern.compile("^[ \\t]*\\[General][ \\t]*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    private ReplayApiConfig() {
    }

    public static Path gameCfgPath() {
        Path installDir = RitoApiUtils.getLeagueInstallDir();
        Path configDir = installDir != null ? installDir.resolve("Config") : Paths.get(DEFAULT_CONFIG_DIR);
        return configDir.resolve("game.cfg");
    }

    public static boolean isEnabled() {
        return isEnabled(gameCfgPath());
    }

    static boolean isEnabled(Path cfg) {
        try {
            Matcher matcher = SETTING_LINE.matcher(read(cfg));
            return matcher.find() && "1".equals(matcher.group(1));
        } catch (IOException e) {
            return false;
        }
    }

    public static void enable() throws IOException {
        enable(gameCfgPath());
    }

    static void enable(Path cfg) throws IOException {
        String content = Files.exists(cfg) ? read(cfg) : "";
        String newline = content.contains("\r\n") ? "\r\n" : "\n";

        Matcher existing = SETTING_LINE.matcher(content);
        Matcher general = GENERAL_SECTION.matcher(content);
        String updated;
        if (existing.find()) {
            updated = content.substring(0, existing.start()) + SETTING + content.substring(existing.end());
        } else if (general.find()) {
            updated = content.substring(0, general.end()) + newline + SETTING + content.substring(general.end());
        } else {
            String separator = content.isEmpty() || content.endsWith("\n") ? "" : newline;
            updated = content + separator + "[General]" + newline + SETTING + newline;
        }

        Files.createDirectories(cfg.getParent());
        Files.write(cfg, updated.getBytes(StandardCharsets.ISO_8859_1));
        System.out.println("[Replay] Enabled the Replay API in " + cfg);
    }

    private static String read(Path cfg) throws IOException {
        return Files.readString(cfg, StandardCharsets.ISO_8859_1);
    }
}
