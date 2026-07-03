package me.bedwarshurts.leagueproximitychat.position;

import me.bedwarshurts.leagueproximitychat.managers.DebugManager;
import me.bedwarshurts.leagueproximitychat.utils.RitoApiUtils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.net.URI;

public class MinimapLocator {

    public record MinimapRect(int x, int y, int size, double score) {
    }

    private static final double SWEEP_MIN = 0.75;
    private static final double SWEEP_MAX = 1.28;
    private static final double SWEEP_STEP = 0.02;

    private static final double MIN_LOCK_SCORE = 0.20;
    private static final int CONFIRM_FRAMES = 3;
    private static final int CONFIRM_TOLERANCE_PX = 4;

    private static final int CANNY_LOW = 60;
    private static final int CANNY_HIGH = 140;
    private static final int EDGE_BLUR = 5;

    private final Mat referenceGray;
    private MinimapRect locked = null;
    private int lockedScreenWidth = -1;
    private MinimapRect lastCandidate = null;
    private int confirmStreak = 0;
    private boolean announced = false;

    private MinimapLocator(Mat referenceGray) {
        this.referenceGray = referenceGray;
    }

    public static MinimapLocator create() {
        try {
            String patch = RitoApiUtils.getLatestDataDragonVersion();
            String url = "https://ddragon.leagueoflegends.com/cdn/" + patch + "/img/map/map11.png";
            BufferedImage raw = ImageIO.read(new URI(url).toURL());

            BufferedImage bgr = new BufferedImage(raw.getWidth(), raw.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = bgr.createGraphics();
            g.drawImage(raw, 0, 0, null);
            g.dispose();

            byte[] pixels = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
            Mat color = new Mat(bgr.getHeight(), bgr.getWidth(), CvType.CV_8UC3);
            color.put(0, 0, pixels);

            Mat gray = new Mat();
            Imgproc.cvtColor(color, gray, Imgproc.COLOR_BGR2GRAY);
            color.release();

            System.out.println("[minimap] Reference map texture loaded (patch " + patch + ").");
            return new MinimapLocator(gray);
        } catch (Exception e) {
            System.err.println("[minimap] Could not load the reference map texture — using config-based minimap bounds. (" + e.getMessage() + ")");
            return null;
        }
    }

    public MinimapRect update(Mat fullScreenBgr, int estimatedSize) {
        if (locked != null) {
            if (fullScreenBgr.width() == lockedScreenWidth) {
                return locked;
            }
            locked = null;
            lastCandidate = null;
            confirmStreak = 0;
            announced = false;
        }

        MinimapRect candidate = probe(fullScreenBgr, referenceGray, estimatedSize);
        if (candidate == null || candidate.score() < MIN_LOCK_SCORE) {
            lastCandidate = null;
            confirmStreak = 0;
            return null;
        }

        if (lastCandidate != null
                && Math.abs(candidate.x() - lastCandidate.x()) <= CONFIRM_TOLERANCE_PX
                && Math.abs(candidate.y() - lastCandidate.y()) <= CONFIRM_TOLERANCE_PX
                && Math.abs(candidate.size() - lastCandidate.size()) <= CONFIRM_TOLERANCE_PX) {
            confirmStreak++;
        } else {
            confirmStreak = 1;
        }
        lastCandidate = candidate;

        if (confirmStreak < CONFIRM_FRAMES) {
            return null;
        }

        locked = candidate;
        lockedScreenWidth = fullScreenBgr.width();
        if (!announced) {
            announced = true;
            System.out.printf("[minimap] Locked true minimap bounds: (%d, %d) %dpx, score=%.3f (config estimate was %dpx).%n",
                    locked.x(), locked.y(), locked.size(), locked.score(), estimatedSize);
            saveDebug(fullScreenBgr, locked);
        }
        return locked;
    }

    public static MinimapRect probe(Mat screen, Mat referenceGray, int estimatedSize) {
        if (referenceGray == null || estimatedSize <= 0) return null;

        int regionSize = (int) Math.min(Math.min(screen.width(), screen.height()), Math.round(estimatedSize * 1.35));
        int rx = screen.width() - regionSize;
        int ry = screen.height() - regionSize;

        Mat region = new Mat(screen, new Rect(rx, ry, regionSize, regionSize));
        Mat regionGray = new Mat();
        Mat regionEdges = new Mat();

        MinimapRect best = null;

        try {
            Imgproc.cvtColor(region, regionGray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.Canny(regionGray, regionEdges, CANNY_LOW, CANNY_HIGH);
            Imgproc.GaussianBlur(regionEdges, regionEdges, new Size(EDGE_BLUR, EDGE_BLUR), 0);

            for (double f = SWEEP_MIN; f <= SWEEP_MAX + 1e-9; f += SWEEP_STEP) {
                int s = (int) Math.round(estimatedSize * f);
                best = matchAtSize(regionEdges, referenceGray, s, regionSize, rx, ry, best);
            }

            if (best != null) {
                int coarseSize = best.size();
                int span = Math.max(2, (int) Math.round(coarseSize * 0.025));
                for (int s = coarseSize - span; s <= coarseSize + span; s += 2) {
                    if (s == coarseSize) continue;
                    best = matchAtSize(regionEdges, referenceGray, s, regionSize, rx, ry, best);
                }
            }
        } finally {
            region.release();
            regionGray.release();
            regionEdges.release();
        }

        return best;
    }

    private static MinimapRect matchAtSize(Mat regionEdges, Mat referenceGray, int s,
                                           int regionSize, int rx, int ry, MinimapRect best) {
        if (s < 100 || s > regionSize) return best;

        Mat refScaled = new Mat();
        Mat refEdges = new Mat();
        Mat result = new Mat();
        try {
            Imgproc.resize(referenceGray, refScaled, new Size(s, s), 0, 0, Imgproc.INTER_AREA);
            Imgproc.Canny(refScaled, refEdges, CANNY_LOW, CANNY_HIGH);
            Imgproc.GaussianBlur(refEdges, refEdges, new Size(EDGE_BLUR, EDGE_BLUR), 0);

            Imgproc.matchTemplate(regionEdges, refEdges, result, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult mmr = Core.minMaxLoc(result);

            if (best == null || mmr.maxVal > best.score()) {
                return new MinimapRect(rx + (int) Math.round(mmr.maxLoc.x),
                        ry + (int) Math.round(mmr.maxLoc.y), s, mmr.maxVal);
            }
            return best;
        } finally {
            refScaled.release();
            refEdges.release();
            result.release();
        }
    }

    private static void saveDebug(Mat screen, MinimapRect rect) {
        if (!DebugManager.isENABLED()) return;
        Mat ctx = null;
        try {
            int pad = 40;
            int x0 = Math.max(0, rect.x() - pad);
            int y0 = Math.max(0, rect.y() - pad);
            int w = Math.min(screen.width() - x0, rect.size() + pad * 2);
            int h = Math.min(screen.height() - y0, rect.size() + pad * 2);
            ctx = new Mat(screen, new Rect(x0, y0, w, h)).clone();
            Imgproc.rectangle(ctx,
                    new Point(rect.x() - x0, rect.y() - y0),
                    new Point(rect.x() - x0 + rect.size(), rect.y() - y0 + rect.size()),
                    new Scalar(0, 255, 0), 2);
            Imgcodecs.imwrite("debug/debug_minimap_lock.png", ctx);
        } catch (Exception ignored) {
        } finally {
            if (ctx != null) ctx.release();
        }
    }
}
