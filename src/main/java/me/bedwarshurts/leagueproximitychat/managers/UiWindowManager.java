package me.bedwarshurts.leagueproximitychat.managers;

import me.bedwarshurts.leagueproximitychat.utils.WindowUtils;
import me.friwi.jcefmaven.CefAppBuilder;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Paths;

public class UiWindowManager {

    private static final String WINDOW_TITLE = "League of Legends Proximity Chat";
    private static final String GAME_WINDOW_TITLE = "League of Legends (TM) Client";
    private static final String APP_URL = "http://localhost:8000";

    private CefApp cefApp;
    private CefBrowser browser;
    private JFrame frame;

    public boolean launch() {
        try {
            CefAppBuilder builder = new CefAppBuilder();
            File installDir = resolveInstallDir();
            builder.setInstallDir(installDir);
            builder.getCefSettings().windowless_rendering_enabled = false;

            String profileDir = new File(installDir.getParentFile(), "browser-profile").getAbsolutePath();
            builder.getCefSettings().cache_path = profileDir;
            builder.getCefSettings().root_cache_path = profileDir;
            builder.addJcefArgs(
                    "--enable-media-stream",
                    "--use-fake-ui-for-media-stream",
                    "--autoplay-policy=no-user-gesture-required",
                    "--disable-background-timer-throttling",
                    "--disable-renderer-backgrounding",
                    "--disable-backgrounding-occluded-windows",
                    "--auto-select-desktop-capture-source=Entire screen",
                    "--enable-usermedia-screen-capturing",
                    "--disable-features=AllowWgcScreenCapturer,AllowWgcWindowCapturer,WebRtcAllowWgcDesktopCapturer,WebRtcAllowWgcScreenCapturer,WebRtcAllowWgcWindowCapturer");

            cefApp = builder.build();
            CefClient client = cefApp.createClient();
            browser = client.createBrowser(APP_URL, false, false);

            SwingUtilities.invokeAndWait(() -> {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {
                }

                frame = new JFrame(WINDOW_TITLE);
                frame.add(browser.getUIComponent());
                frame.setSize(1180, 760);
                frame.setLocationRelativeTo(null);
                frame.setExtendedState(frame.getExtendedState() | JFrame.MAXIMIZED_BOTH);

                frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                frame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        handleCloseRequest();
                    }
                });
                frame.setVisible(true);
            });

            System.out.println("[UiWindow] Standalone UI window started (embedded Chromium).");
            return true;
        } catch (Throwable t) {
            System.err.println("[UiWindow] Could not start the embedded UI window: " + t.getMessage());
            shutdown();
            return false;
        }
    }

    private static File resolveInstallDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Paths.get(appData, "LeagueProximityChat", "jcef-bundle").toFile();
        }
        return Paths.get(System.getProperty("user.home"), ".leagueproximitychat", "jcef-bundle").toFile();
    }

    private void handleCloseRequest() {
        String[] options = {"Keep in Background", "Close Completely"};
        int choice = JOptionPane.showOptionDialog(
                frame,
                """
                        Keep League Proximity Chat running in the background?
                        You can bring this window back at any time with Shift+F8.
                        
                        """,
                WINDOW_TITLE,
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);

        if (choice == 1) {
            System.out.println("[UiWindow] Closing the app.");
            frame.setVisible(false);
            new Thread(() -> System.exit(0), "app-close").start();
        } else if (choice == 0) {
            frame.setVisible(false);
        }
    }

    public void toggleOverlay() {
        if (frame == null) return;

        SwingUtilities.invokeLater(() -> {
            if (frame.isVisible()) {
                frame.setVisible(false);
                WindowUtils.focusWindow(GAME_WINDOW_TITLE);
                return;
            }

            Rectangle bounds = WindowUtils.getGameWindowBounds(GAME_WINDOW_TITLE);
            int w, h, x, y;
            if (bounds != null && bounds.width > 0) {
                w = Math.clamp((int) (bounds.width * 0.62), 900, bounds.width);
                h = Math.clamp((int) (bounds.height * 0.70), 560, bounds.height);
                x = bounds.x + (bounds.width - w) / 2;
                y = bounds.y + (bounds.height - h) / 2;
            } else {
                Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                w = 1180;
                h = 760;
                x = (screen.width - w) / 2;
                y = (screen.height - h) / 2;
            }

            frame.setFocusableWindowState(false);
            frame.setAlwaysOnTop(true);
            frame.setBounds(x, y, w, h);
            frame.setVisible(true);
            frame.setFocusableWindowState(true);
        });
    }

    public void shutdown() {
        try {
            if (frame != null) {
                JFrame f = frame;
                frame = null;
                SwingUtilities.invokeLater(f::dispose);
            }
            if (cefApp != null) {
                cefApp.dispose();
                cefApp = null;
            }
        } catch (Throwable ignored) {
        }
    }
}
