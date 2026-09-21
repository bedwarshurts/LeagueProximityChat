package me.bedwarshurts.leagueproximitychat.position;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import me.bedwarshurts.leagueproximitychat.utils.WindowUtils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.awt.Rectangle;

public final class ScreenCapture {

    private static final Pointer DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = Pointer.createConstant(-4);

    private static volatile boolean dpiContextSupported = true;

    private Memory buffer;

    public Mat captureWindowClient(String exactWindowTitle) {
        Pointer previousDpiContext = usePhysicalPixels();
        try {
            Rectangle bounds = WindowUtils.getGameWindowBounds(exactWindowTitle);
            if (bounds == null || bounds.width <= 0 || bounds.height <= 0) return null;
            return capture(bounds);
        } finally {
            restoreDpiContext(previousDpiContext);
        }
    }

    private Mat capture(Rectangle bounds) {
        HDC screenDc = User32.INSTANCE.GetDC(null);
        if (screenDc == null) return null;

        HDC memDc = null;
        HBITMAP bitmap = null;
        try {
            memDc = GDI32.INSTANCE.CreateCompatibleDC(screenDc);
            bitmap = GDI32.INSTANCE.CreateCompatibleBitmap(screenDc, bounds.width, bounds.height);
            if (memDc == null || bitmap == null) return null;

            HANDLE previous = GDI32.INSTANCE.SelectObject(memDc, bitmap);
            boolean copied = GDI32.INSTANCE.BitBlt(memDc, 0, 0, bounds.width, bounds.height,
                    screenDc, bounds.x, bounds.y, GDI32.SRCCOPY);
            GDI32.INSTANCE.SelectObject(memDc, previous);
            if (!copied) return null;

            WinGDI.BITMAPINFO info = new WinGDI.BITMAPINFO();
            info.bmiHeader.biSize = info.bmiHeader.size();
            info.bmiHeader.biWidth = bounds.width;
            info.bmiHeader.biHeight = -bounds.height;
            info.bmiHeader.biPlanes = 1;
            info.bmiHeader.biBitCount = 32;
            info.bmiHeader.biCompression = WinGDI.BI_RGB;

            long size = (long) bounds.width * bounds.height * 4;
            if (buffer == null || buffer.size() < size) buffer = new Memory(size);

            int lines = GDI32.INSTANCE.GetDIBits(screenDc, bitmap, 0, bounds.height, buffer, info, WinGDI.DIB_RGB_COLORS);
            if (lines != bounds.height) return null;

            Mat bgra = new Mat(bounds.height, bounds.width, CvType.CV_8UC4, buffer.getByteBuffer(0, size));
            Mat bgr = new Mat();
            Imgproc.cvtColor(bgra, bgr, Imgproc.COLOR_BGRA2BGR);
            bgra.release();
            return bgr;
        } finally {
            if (bitmap != null) GDI32.INSTANCE.DeleteObject(bitmap);
            if (memDc != null) GDI32.INSTANCE.DeleteDC(memDc);
            User32.INSTANCE.ReleaseDC(null, screenDc);
        }
    }

    private static Pointer usePhysicalPixels() {
        if (!dpiContextSupported) return null;
        try {
            return WindowUtils.CustomUser32.INSTANCE.SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
        } catch (UnsatisfiedLinkError e) {
            dpiContextSupported = false;
            return null;
        }
    }

    private static void restoreDpiContext(Pointer previous) {
        if (previous == null) return;
        try {
            WindowUtils.CustomUser32.INSTANCE.SetThreadDpiAwarenessContext(previous);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }
}
