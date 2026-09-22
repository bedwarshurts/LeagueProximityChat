package me.bedwarshurts.leagueproximitychat.app;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.io.InputStream;

public final class AppIcon {

    private static final Image IMAGE = load();

    private AppIcon() {
    }

    public static Image image() {
        return IMAGE;
    }

    private static Image load() {
        try (InputStream in = AppIcon.class.getResourceAsStream("/app-icon.png")) {
            return in == null ? null : ImageIO.read(in);
        } catch (Exception e) {
            System.err.println("[AppIcon] Could not load the app icon: " + e.getMessage());
            return null;
        }
    }
}
