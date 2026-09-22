package me.bedwarshurts.leagueproximitychat.utils;

import lombok.Getter;
import me.bedwarshurts.leagueproximitychat.app.AppIcon;

import javax.swing.JDialog;
import javax.swing.JFileChooser;
import java.awt.Component;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LeagueConfigReader {

    private static final String DEFAULT_CONFIG_PATH = "C:/Riot Games/League of Legends/Config";

    private static final Pattern COLOR_PALETTE_CFG = Pattern.compile("^\\s*ColorPalette\\s*=\\s*(\\d+)", Pattern.MULTILINE);
    private static final Pattern COLOR_PALETTE_JSON = Pattern.compile("\"name\"\\s*:\\s*\"ColorPalette\"\\s*,\\s*\"value\"\\s*:\\s*\"(\\d+)\"");
    private static final Pattern MINIMAP_SCALE_JSON = Pattern.compile("\"name\"\\s*:\\s*\"MinimapScale\"\\s*,\\s*\"value\"\\s*:\\s*\"([^\"]+)\"");

    private static final String ASSUMED_COLORBLIND = "Colorblind Mode: Off";

    public record Warning(String message, List<String> settingsToChange, String footer) {
        public String toLogLine() {
            return message + " " + String.join(", ", settingsToChange) + (footer != null ? " - " + footer : "");
        }
    }

    public static class LeagueSettings {
        @Getter private float minimapScale = 1.0f;
        @Getter private boolean isColorblind = false;
        @Getter private Warning warning = null;
    }

    public static LeagueSettings loadSettings() {
        LeagueSettings settings = new LeagueSettings();
        File configDir = new File(DEFAULT_CONFIG_PATH);

        if (!configDir.exists() || !configDir.isDirectory()) {
            System.out.println("[loadSettings] Default League config not found. Prompting user for folder...");
            configDir = promptUserForConfigDirectory();

            if (configDir == null) {
                System.err.println("[loadSettings] No folder selected. Using fallback defaults.");
                settings.warning = new Warning(
                        "Couldn't find your League settings folder. Please change the following setting in League:",
                        List.of(ASSUMED_COLORBLIND),
                        "or restart LeagueProximityChat and select your League 'Config' folder when asked.");
                return settings;
            }
        }

        return readSettings(configDir);
    }

    static LeagueSettings readSettings(File configDir) {
        LeagueSettings settings = new LeagueSettings();
        Path gameCfg = configDir.toPath().resolve("game.cfg");
        Path persistedJson = configDir.toPath().resolve("PersistedSettings.json");

        String cfgContent = null;
        String cfgError = null;
        try {
            cfgContent = new String(Files.readAllBytes(gameCfg), StandardCharsets.ISO_8859_1);
        } catch (Exception e) {
            cfgError = describe(e);
            System.err.println("[loadSettings] Could not read " + gameCfg + " (" + cfgError + ").");
        }

        String jsonContent = null;
        String jsonError = null;
        try {
            jsonContent = new String(Files.readAllBytes(persistedJson), StandardCharsets.UTF_8);
        } catch (Exception e) {
            jsonError = describe(e);
            System.err.println("[loadSettings] Could not read " + persistedJson + " (" + jsonError + ").");
        }

        Boolean colorblind = null;
        if (cfgContent != null) {
            Matcher matcher = COLOR_PALETTE_CFG.matcher(cfgContent);
            if (matcher.find()) colorblind = "1".equals(matcher.group(1));
        }
        if (colorblind == null && jsonContent != null) {
            Matcher matcher = COLOR_PALETTE_JSON.matcher(jsonContent);
            if (matcher.find()) {
                colorblind = "1".equals(matcher.group(1));
                System.out.println("[loadSettings] Colorblind setting taken from PersistedSettings.json.");
            }
        }

        if (colorblind != null) {
            settings.isColorblind = colorblind;
        } else {
            String reason = cfgError != null ? cfgError : "setting not found";
            System.err.println("[loadSettings] Colorblind setting unknown (" + reason + ") - assuming it is off.");
            boolean unreadable = cfgError != null && !cfgError.equals("file not found");
            settings.warning = unreadable
                    ? new Warning("Couldn't read your League settings file. Please change the following setting in League:",
                    List.of(ASSUMED_COLORBLIND), "or launch LeagueProximityChat as administrator.")
                    : new Warning("Couldn't find the colorblind setting in your League settings. Please change the following setting in League:",
                    List.of(ASSUMED_COLORBLIND), null);
        }

        if (jsonContent != null) {
            Matcher matcher = MINIMAP_SCALE_JSON.matcher(jsonContent);
            if (matcher.find()) {
                try {
                    settings.minimapScale = Float.parseFloat(matcher.group(1));
                } catch (NumberFormatException e) {
                    System.err.println("[loadSettings] Invalid MinimapScale value. Defaulting scale to 1.0.");
                }
            }
        }

        return settings;
    }

    private static String describe(Exception e) {
        if (e instanceof AccessDeniedException) return "access denied";
        if (e instanceof NoSuchFileException) return "file not found";
        return e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
    }

    private static File promptUserForConfigDirectory() {
        JFileChooser fileChooser = new JFileChooser() {
            @Override
            protected JDialog createDialog(Component parent) {
                JDialog dialog = super.createDialog(parent);
                dialog.setIconImage(AppIcon.image());
                return dialog;
            }
        };
        fileChooser.setDialogTitle("Select your League of Legends 'Config' folder");
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setAcceptAllFileFilterUsed(false);

        int userSelection = fileChooser.showOpenDialog(null);

        if (userSelection == JFileChooser.APPROVE_OPTION) {
            return fileChooser.getSelectedFile();
        }
        return null;
    }
}
