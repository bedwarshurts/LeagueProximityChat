package me.bedwarshurts.leagueproximitychat.managers;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import me.bedwarshurts.leagueproximitychat.utils.WindowUtils;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class OverlayManager {

    private static final String OVERLAY_TITLE = "League of Legends Proximity Chat";
    private static final String GAME_WINDOW_TITLE = "League of Legends (TM) Client";
    private static final String APP_URL = "http://localhost:8000";

    private static final int MOD_SHIFT = 0x0004;
    private static final int VK_F8 = 0x77;
    private static final int VK_F9 = 0x78;
    private static final int VK_F10 = 0x79;

    private static final int HOTKEY_ID_OVERLAY = 0xBEE5; // Shift+F8
    private static final int HOTKEY_ID_MUTE = 0xBEE6;    // Shift+F9
    private static final int HOTKEY_ID_DEAFEN = 0xBEE7;  // Shift+F10

    private static final int WM_HOTKEY = 0x0312;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_SHOWWINDOW = 0x0040;
    private static final HWND HWND_TOPMOST = new HWND(Pointer.createConstant(-1L));

    private final Runnable onMuteHotkey;
    private final Runnable onDeafenHotkey;
    private Path browserPath = null;
    private UiWindowManager nativeWindow = null;

    public OverlayManager(Runnable onMuteHotkey, Runnable onDeafenHotkey) {
        this.onMuteHotkey = onMuteHotkey;
        this.onDeafenHotkey = onDeafenHotkey;
    }

    /** Standalone-window mode: hotkeys drive the JCEF window directly, no browser involved. */
    public void launchWithNativeWindow(UiWindowManager window) {
        this.nativeWindow = window;
        startHotkeyListener();
        System.out.println("[Overlay] Hotkeys armed. Shift+F8 toggles the overlay, Shift+F9 mute, Shift+F10 deafen.");
    }

    public boolean launch() {
        browserPath = findBrowserExecutable();
        if (browserPath == null) {
            System.err.println("[Overlay] Chrome/Edge not found - falling back to a normal browser tab.");
            return false;
        }

        if (!spawnAppWindow()) {
            return false;
        }

        startHotkeyListener();
        if (DebugManager.isENABLED()) System.out.println("[Overlay] UI launched as an app window. Shift+F8 toggles the overlay, Shift+F9 mute, Shift+F10 deafen.");
        return true;
    }

    private static Path findBrowserExecutable() {
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        String localAppData = System.getenv("LOCALAPPDATA");

        String[] candidates = {
                programFiles + "\\Google\\Chrome\\Application\\chrome.exe",
                programFilesX86 + "\\Google\\Chrome\\Application\\chrome.exe",
                localAppData + "\\Google\\Chrome\\Application\\chrome.exe",
                programFilesX86 + "\\Microsoft\\Edge\\Application\\msedge.exe",
                programFiles + "\\Microsoft\\Edge\\Application\\msedge.exe"
        };

        for (String candidate : candidates) {
            if (!candidate.startsWith("null")) {
                Path path = Paths.get(candidate);
                if (Files.exists(path)) {
                    return path;
                }
            }
        }
        return null;
    }

    private boolean spawnAppWindow() {
        try {
            new ProcessBuilder(browserPath.toString(),
                    "--app=" + APP_URL,
                    "--window-size=1180,760",
                    "--disable-background-timer-throttling").start();
            return true;
        } catch (IOException e) {
            System.err.println("[Overlay] Failed to launch the app window: " + e.getMessage());
            return false;
        }
    }

    private void startHotkeyListener() {
        Thread listener = new Thread(() -> {
            if (!User32.INSTANCE.RegisterHotKey(null, HOTKEY_ID_OVERLAY, MOD_SHIFT, VK_F8)) {
                System.err.println("[Overlay] Could not register the Shift+F8 overlay hotkey (already in use?).");
            }
            if (!User32.INSTANCE.RegisterHotKey(null, HOTKEY_ID_MUTE, MOD_SHIFT, VK_F9)) {
                System.err.println("[Overlay] Could not register the Shift+F9 mute hotkey (already in use?).");
            }
            if (!User32.INSTANCE.RegisterHotKey(null, HOTKEY_ID_DEAFEN, MOD_SHIFT, VK_F10)) {
                System.err.println("[Overlay] Could not register the Shift+F10 deafen hotkey (already in use?).");
            }

            WinUser.MSG msg = new WinUser.MSG();
            while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                if (msg.message != WM_HOTKEY) continue;
                try {
                    switch (msg.wParam.intValue()) {
                        case HOTKEY_ID_OVERLAY -> toggleOverlay();
                        case HOTKEY_ID_MUTE -> {
                            if (onMuteHotkey != null) onMuteHotkey.run();
                        }
                        case HOTKEY_ID_DEAFEN -> {
                            if (onDeafenHotkey != null) onDeafenHotkey.run();
                        }
                        default -> {
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[Overlay] Hotkey action failed: " + e.getMessage());
                }
            }
        }, "overlay-hotkey");
        listener.setDaemon(true);
        listener.start();
    }

    private void toggleOverlay() {
        if (nativeWindow != null) {
            nativeWindow.toggleOverlay();
            return;
        }

        HWND overlay = findOverlayWindow();

        if (overlay == null) {
            spawnAppWindow();
            return;
        }

        if (User32.INSTANCE.IsWindowVisible(overlay)) {
            User32.INSTANCE.ShowWindow(overlay, WinUser.SW_HIDE);
            HWND game = User32.INSTANCE.FindWindow(null, GAME_WINDOW_TITLE);
            if (game != null) {
                User32.INSTANCE.SetForegroundWindow(game);
            }
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

        User32.INSTANCE.SetWindowPos(overlay, HWND_TOPMOST, x, y, w, h, SWP_SHOWWINDOW | SWP_NOACTIVATE);
    }

    private HWND findOverlayWindow() {
        HWND[] found = new HWND[1];
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            char[] buffer = new char[256];
            User32.INSTANCE.GetWindowText(hwnd, buffer, 256);
            if (OVERLAY_TITLE.equals(Native.toString(buffer).trim())) {
                found[0] = hwnd;
                return false;
            }
            return true;
        }, null);
        return found[0];
    }
}
