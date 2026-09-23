package me.bedwarshurts.leagueproximitychat.managers;

import me.bedwarshurts.leagueproximitychat.app.AppConstants;
import me.bedwarshurts.leagueproximitychat.app.AppIcon;
import me.bedwarshurts.leagueproximitychat.app.AppInfo;
import me.bedwarshurts.leagueproximitychat.utils.WindowUtils;
import me.friwi.jcefmaven.CefAppBuilder;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefMenuModel;
import org.cef.handler.CefContextMenuHandlerAdapter;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.Frame;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Paths;

public class UiWindowManager {

    private static final long EXIT_TIMEOUT_MS = 5000;

    private CefApp cefApp;
    private CefBrowser browser;
    private JFrame frame;
    private TrayIcon trayIcon;

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
                    "--do-not-de-elevate",
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
            client.addContextMenuHandler(new CefContextMenuHandlerAdapter() {
                @Override
                public void onBeforeContextMenu(CefBrowser browser, CefFrame frame,
                                                CefContextMenuParams params, CefMenuModel model) {
                    model.clear();
                }
            });
            browser = client.createBrowser(AppConstants.APP_URL, false, false);

            SwingUtilities.invokeAndWait(() -> {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {
                }

                frame = new JFrame(AppConstants.APP_WINDOW_TITLE);
                frame.setIconImage(AppIcon.image());
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

            installTrayIcon();
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

    private void installTrayIcon() {
        Image image = AppIcon.image();
        if (!SystemTray.isSupported() || image == null) return;
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem show = new MenuItem("Show LeagueProximityChat");
            show.addActionListener(e -> showWindow());
            MenuItem quit = new MenuItem("Quit");
            quit.addActionListener(e -> quitApp());
            menu.add(show);
            menu.addSeparator();
            menu.add(quit);

            trayIcon = new TrayIcon(image, AppConstants.APP_WINDOW_TITLE + " " + AppInfo.fullVersion(), menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1) showWindow();
                }
            });
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception e) {
            System.err.println("[UiWindow] Could not add the tray icon: " + e.getMessage());
        }
    }

    public void showWindow() {
        SwingUtilities.invokeLater(() -> {
            if (frame == null) return;
            frame.setAlwaysOnTop(false);
            if ((frame.getExtendedState() & Frame.ICONIFIED) != 0) {
                frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
            }
            frame.setVisible(true);
            frame.toFront();
            frame.requestFocus();
        });
    }

    private void quitApp() {
        System.out.println("[UiWindow] Closing the app.");
        removeTrayIcon();
        if (frame != null) frame.setVisible(false);
        new Thread(() -> {
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(EXIT_TIMEOUT_MS);
                } catch (InterruptedException ignored) {
                }
                System.err.println("[UiWindow] Shutdown is taking too long - forcing exit.");
                Runtime.getRuntime().halt(0);
            }, "exit-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            System.exit(0);
        }, "app-close").start();
    }

    private void removeTrayIcon() {
        if (trayIcon == null) return;
        try {
            SystemTray.getSystemTray().remove(trayIcon);
        } catch (Exception ignored) {
        }
        trayIcon = null;
    }

    private void handleCloseRequest() {
        String[] options = {"Keep in Background", "Close Completely"};
        int choice = JOptionPane.showOptionDialog(
                frame,
                """
                        Keep League Proximity Chat running in the background?
                        You can bring this window back at any time with Shift+F8
                        or by clicking its icon in the system tray.

                        """,
                AppConstants.APP_WINDOW_TITLE,
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);

        if (choice == 1) {
            quitApp();
        } else if (choice == 0) {
            frame.setVisible(false);
        }
    }

    public void toggleOverlay() {
        if (frame == null) return;

        SwingUtilities.invokeLater(() -> {
            if (frame.isVisible()) {
                frame.setVisible(false);
                WindowUtils.focusWindow(AppConstants.GAME_WINDOW_TITLE);
                return;
            }

            Rectangle bounds = WindowUtils.overlayBounds(AppConstants.GAME_WINDOW_TITLE);

            frame.setFocusableWindowState(false);
            frame.setAlwaysOnTop(true);
            frame.setBounds(bounds);
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
