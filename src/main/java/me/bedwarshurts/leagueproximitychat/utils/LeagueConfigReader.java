package me.bedwarshurts.leagueproximitychat.utils;

import lombok.Getter;

import javax.swing.JFileChooser;
import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LeagueConfigReader {

    private static final String DEFAULT_CONFIG_PATH = "C:/Riot Games/League of Legends/Config";

    public static class LeagueSettings {
        @Getter private int width = 1920;
        @Getter private int height = 1080;
        @Getter private float minimapScale = 1.0f;
        @Getter private boolean isColorblind = false;
    }

    public static LeagueSettings loadSettings() {
        LeagueSettings settings = new LeagueSettings();
        File configDir = new File(DEFAULT_CONFIG_PATH);

        if (!configDir.exists() || !configDir.isDirectory()) {
            System.out.println("[loadSettings] Default League config not found. Prompting user for folder...");
            configDir = promptUserForConfigDirectory();

            if (configDir == null) {
                System.err.println("[loadSettings] No folder selected. Using fallback defaults.");
                return settings;
            }
        }

        File gameCfg = new File(configDir, "game.cfg");
        if (gameCfg.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(gameCfg))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("Width=")) {
                        settings.width = Integer.parseInt(line.split("=")[1].trim());
                    } else if (line.startsWith("Height=")) {
                        settings.height = Integer.parseInt(line.split("=")[1].trim());
                    } else if (line.startsWith("ColorPalette=")) {
                        settings.isColorblind = line.split("=")[1].trim().equals("1");
                    }
                }
            } catch (Exception e) {
                System.err.println("[loadSettings] Failed to parse game.cfg. Using defaults.");
            }
        }

        File persistedJson = new File(configDir, "PersistedSettings.json");
        if (persistedJson.exists()) {
            try {
                String content = new String(Files.readAllBytes(Paths.get(persistedJson.toURI())));
                Pattern pattern = Pattern.compile("\"name\"\\s*:\\s*\"MinimapScale\"\\s*,\\s*\"value\"\\s*:\\s*\"([^\"]+)\"");
                Matcher matcher = pattern.matcher(content);
                if (matcher.find()) {
                    settings.minimapScale = Float.parseFloat(matcher.group(1));
                }
            } catch (Exception e) {
                System.err.println("[loadSettings] Failed to parse PersistedSettings.json. Defaulting scale to 1.0.");
            }
        }

        return settings;
    }

    private static File promptUserForConfigDirectory() {
        JFileChooser fileChooser = new JFileChooser();
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