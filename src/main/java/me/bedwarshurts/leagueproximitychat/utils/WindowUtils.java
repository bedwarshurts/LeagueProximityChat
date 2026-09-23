package me.bedwarshurts.leagueproximitychat.utils;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.win32.W32APIOptions;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Toolkit;

public final class WindowUtils {

    public interface CustomUser32 extends User32 {
        CustomUser32 INSTANCE = Native.load("user32", CustomUser32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean ClientToScreen(HWND hWnd, POINT lpPoint);

        Pointer SetThreadDpiAwarenessContext(Pointer dpiContext);
    }

    public static boolean isWindowFocused(String windowTitleTarget) {
        char[] windowText = new char[512];
        HWND hwnd = User32.INSTANCE.GetForegroundWindow();

        if (hwnd != null) {
            User32.INSTANCE.GetWindowText(hwnd, windowText, 512);
            String activeWindowTitle = Native.toString(windowText).trim();
            return activeWindowTitle.toLowerCase().contains(windowTitleTarget.toLowerCase());
        }
        return false;
    }

    public static void focusWindow(String exactWindowTitle) {
        HWND hwnd = User32.INSTANCE.FindWindow(null, exactWindowTitle);
        if (hwnd != null) {
            User32.INSTANCE.SetForegroundWindow(hwnd);
        }
    }

    public static Rectangle overlayBounds(String gameWindowTitle) {
        Rectangle bounds = getGameWindowBounds(gameWindowTitle);
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
        return new Rectangle(x, y, w, h);
    }

    public static Rectangle getGameWindowBounds(String exactWindowTitle) {
        HWND hwnd = User32.INSTANCE.FindWindow(null, exactWindowTitle);
        if (hwnd == null) return null;

        RECT clientRect = new RECT();
        User32.INSTANCE.GetClientRect(hwnd, clientRect);
        int width = clientRect.right - clientRect.left;
        int height = clientRect.bottom - clientRect.top;

        POINT topLeft = new POINT(0, 0);

        CustomUser32.INSTANCE.ClientToScreen(hwnd, topLeft);

        return new Rectangle(topLeft.x, topLeft.y, width, height);
    }
}