package me.bedwarshurts.leagueproximitychat.managers;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ClipRecorder {

    public record Frame(long epochMs, byte[] jpeg) {
        public boolean hasImage() {
            return jpeg != null;
        }
    }

    private static final long KEEP_MS = 45_000;
    private static final long MIN_FRAME_GAP_MS = 50;
    private static final int TARGET_WIDTH = 1280;
    private static final int JPEG_QUALITY = 80;
    private static final int ENCODER_THREADS = 2;

    private static final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private static final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            ENCODER_THREADS, ENCODER_THREADS, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(40), r -> {
        Thread t = new Thread(r, "clip-recorder");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean screenRecordingActive = false;
    private static long lastFrameAtMs = 0;

    private ClipRecorder() {
    }

    public static void setScreenRecordingActive(boolean active) {
        if (screenRecordingActive != active) {
            System.out.println("[Clips] Screen recording " + (active ? "running - backend frames paused." : "stopped - backend frames resumed."));
        }
        screenRecordingActive = active;
    }

    public static void record(Mat screenBgr) {
        if (ConfigManager.isLowPerformanceMode()) return;
        long now = System.currentTimeMillis();
        if (now - lastFrameAtMs < MIN_FRAME_GAP_MS) return;

        if (screenRecordingActive) {
            lastFrameAtMs = now;
            add(new Frame(now, null));
            return;
        }
        if (worker.getQueue().size() >= 40) return;
        lastFrameAtMs = now;

        Mat copy = screenBgr.clone();
        try {
            worker.execute(() -> encode(copy, now));
        } catch (Exception e) {
            copy.release();
        }
    }

    private static void add(Frame frame) {
        synchronized (frames) {
            frames.addLast(frame);
            long cutoff = System.currentTimeMillis() - KEEP_MS;
            frames.removeIf(f -> f.epochMs() < cutoff);
        }
    }

    private static void encode(Mat src, long epochMs) {
        Mat small = new Mat();
        MatOfByte buffer = new MatOfByte();
        MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY);
        try {
            int h = Math.max(2, (int) Math.round(src.rows() * (TARGET_WIDTH / (double) src.cols())));
            Imgproc.resize(src, small, new Size(TARGET_WIDTH, h), 0, 0, Imgproc.INTER_AREA);

            if (!Imgcodecs.imencode(".jpg", small, buffer, params)) {
                return;
            }
            add(new Frame(epochMs, buffer.toArray()));
        } catch (Exception e) {
            DebugManager.logFailure("[Clips] Could not encode a frame", e);
        } finally {
            src.release();
            small.release();
            buffer.release();
            params.release();
        }
    }

    public static List<Frame> snapshot(long fromMs, long toMs) {
        List<Frame> out = new ArrayList<>();
        synchronized (frames) {
            for (Frame f : frames) {
                if (f.epochMs() >= fromMs && f.epochMs() <= toMs) {
                    out.add(f);
                }
            }
        }
        out.sort(Comparator.comparingLong(Frame::epochMs));
        return out;
    }

    public static void clear() {
        synchronized (frames) {
            frames.clear();
        }
    }
}
