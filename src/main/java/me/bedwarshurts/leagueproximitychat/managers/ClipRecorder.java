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
    }

    private static final long KEEP_MS = 90_000;
    private static final int TARGET_WIDTH = 960;
    private static final int JPEG_QUALITY = 70;

    private static final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private static final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            9, 9, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(60), r -> {
        Thread t = new Thread(r, "clip-recorder");
        t.setDaemon(true);
        return t;
    });

    private ClipRecorder() {
    }

    public static void record(Mat screenBgr) {
        if (worker.getQueue().size() >= 60) return;

        Mat copy = screenBgr.clone();
        long now = System.currentTimeMillis();
        try {
            worker.execute(() -> encode(copy, now));
        } catch (Exception e) {
            copy.release();
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
            byte[] jpeg = buffer.toArray();

            synchronized (frames) {
                frames.addLast(new Frame(epochMs, jpeg));
                long cutoff = System.currentTimeMillis() - KEEP_MS;
                frames.removeIf(f -> f.epochMs() < cutoff);
            }
        } catch (Exception ignored) {
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
