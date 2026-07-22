package me.bedwarshurts.leagueproximitychat.utils;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.win32.W32APIOptions;
import java.awt.Rectangle;

public final class WindowUtils {

    public interface CustomUser32 extends User32 {
        CustomUser32 INSTANCE = Native.load("user32", CustomUser32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean ClientToScreen(HWND hWnd, POINT lpPoint);
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