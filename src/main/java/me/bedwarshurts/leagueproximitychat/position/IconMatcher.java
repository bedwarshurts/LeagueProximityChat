package me.bedwarshurts.leagueproximitychat.position;

import me.bedwarshurts.leagueproximitychat.managers.DebugManager;
import me.bedwarshurts.leagueproximitychat.utils.ImageUtils;
import me.bedwarshurts.leagueproximitychat.utils.MathUtils;
import org.jetbrains.annotations.Nullable;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class IconMatcher {

    record TemplateMatch(Point center, double score, double rawScore) {
    }

    record CandidateMatch(Point center, int width, int height, double score, double rawScore) {
    }

    private record EvalResult(Point center, double score) {
    }

    record ComparedWindow(Point center, List<IconCircle> coveringIcons) {
    }

    private record ScoredRing(IconCircle ring, boolean uncovered, EvalResult eval) {
    }

    private enum PickHealth { UNKNOWN, HEALTHY, WEAK_ALONE }

    private record ScaledTemplate(int size, Mat resized, Mat core, Mat enhancedCore) {
        void release() {
            core.release();
            resized.release();
            if (enhancedCore != null) enhancedCore.release();
        }
    }

    private static final double LOW_CONTRAST_STDDEV_THRESHOLD = 28.0;

    private static final int BOOTSTRAP_CONFIRM_FRAMES = 3;
    private static final double BOOTSTRAP_MAX_DIST = 12.0;
    private static final double BOOTSTRAP_MIN_CHAMPION_MATCH = 0.30;
    private static final double BOOTSTRAP_MATCH_MARGIN = 0.10;
    private static final double ALLY_ISOLATION_FACTOR = 1.1;

    private static final int MAX_LOCKED_MATCH_FAILURES = 25;
    private static final int MAX_WRONG_LOCK_STREAK = 60;
    private static final double TEMPLATE_HEALTHY_MIN_SCORE = 0.70;
    private static final int MAX_WEAK_TEMPLATE_STREAK = 60;

    private static final int MATCH_BLUR_KERNEL = 3;

    private static final double PROXIMITY_BOOST_MAX = 0.50;
    private static final double LAST_KNOWN_BOOST_MAX = 0.25;
    private static final double PROXIMITY_BOOST_RADIUS = 25.0;
    private static final double CLONE_DETECT_THRESHOLD = 0.78;

    private static final double BLIP_RADIUS_PER_MINIMAP_PX = 16.0 / 280.0;

    private static final double OCCLUSION_MIN_VISIBLE_FRACTION = 0.25;
    private static final int ICON_CENTER_JITTER_PX = 3;
    private static final int COVERING_ICON_BORDER_PX = 1;

    private static final double ICON_CORE_CROP = 0.65;
    private static final double ICON_CORE_MARGIN = (1.0 - ICON_CORE_CROP) / 2.0;

    private static final int SEARCH_SIZE_MAX = 120;
    private static final int SEARCH_SIZE_MIN = 20;

    private Mat championTemplate;
    private List<ScaledTemplate> scaledTemplates = null;

    private Mat lockedCoreTemplate = null;
    private Mat lockedCoreTemplateEnhanced = null;
    private boolean isScaleLocked = false;
    private boolean isBootstrapped = false;
    private int lockedBlipRadius = 0;

    private int bootstrapConfidence = 0;
    private Point bootstrapLastPick = null;
    private int lockedMatchFailures = 0;
    private int wrongLockStreak = 0;
    private int weakTemplateStreak = 0;
    private PickHealth lastPickHealth = PickHealth.UNKNOWN;
    private double lastPickRawScore = 0.0;
    private int lastStrongMatchCount = 0;

    IconMatcher(Mat championTemplate) {
        this.championTemplate = championTemplate;
    }

    boolean isBootstrapped() {
        return isBootstrapped;
    }

    int lockedBlipRadius() {
        return lockedBlipRadius;
    }

    int lastStrongMatchCount() {
        return lastStrongMatchCount;
    }

    @Nullable
    TemplateMatch locate(Mat minimap, List<IconCircle> allyCircles, List<IconCircle> enemyCircles,
                         double anchorX, double anchorY, boolean anchorFromHealthBar) {
        int borderMarginX = (int) (minimap.width() * 0.03);
        int borderMarginY = (int) (minimap.height() * 0.03);
        lastStrongMatchCount = 0;
        lastPickHealth = PickHealth.UNKNOWN;

        if (allyCircles.isEmpty()) {
            if (DebugManager.isENABLED()) System.out.println("[locateChampionViaTemplate] FAILED: 0 blue ally circles found on the minimap.");
            return null;
        }

        if ((isScaleLocked || isBootstrapped) && lockedCoreTemplate != null) {
            double bestScore = -1.0;
            Point bestCenter = null;
            double rawScoreLog = 0.0;
            ScoredRing bestRing = null;
            int cw = lockedCoreTemplate.width();
            int ch = lockedCoreTemplate.height();
            List<ScoredRing> scoredRings = new ArrayList<>();
            List<CandidateMatch> candidates = new ArrayList<>();
            List<ComparedWindow> comparedWindows = DebugManager.isENABLED() ? new ArrayList<>() : null;

            int strongMatches = 0;
            for (IconCircle ally : allyCircles) {
                List<IconCircle> coveringIcons = overlappingNeighbors(ally, allyCircles, enemyCircles);
                EvalResult eval = coveringIcons.isEmpty() ? null
                        : evaluateUncoveredPart(minimap, ally, coveringIcons, lockedCoreTemplate);
                boolean comparedUncoveredPart = eval != null;
                if (eval == null) {
                    eval = evaluateTemplateAtAlly(minimap, ally.center(), lockedCoreTemplate, lockedCoreTemplateEnhanced, ICON_CENTER_JITTER_PX);
                }
                if (eval == null) continue;

                if (comparedWindows != null) {
                    comparedWindows.add(new ComparedWindow(eval.center(), comparedUncoveredPart ? coveringIcons : List.of()));
                }

                if (eval.score() > CLONE_DETECT_THRESHOLD) strongMatches++;
                scoredRings.add(new ScoredRing(ally, coveringIcons.isEmpty(), eval));
            }

            double boostMax = (anchorFromHealthBar || strongMatches >= 2) ? PROXIMITY_BOOST_MAX : LAST_KNOWN_BOOST_MAX;

            for (ScoredRing scored : scoredRings) {
                double rawScore = scored.eval().score();
                Point candidateCenter = scored.eval().center();

                double candidateX = MapCoordinates.percentXPrecise(candidateCenter.x, minimap.width());
                double candidateY = MapCoordinates.percentYPrecise(candidateCenter.y, minimap.height());

                double dist = Math.hypot(candidateX - anchorX, candidateY - anchorY);
                double boost = boostMax * Math.max(0.0, 1.0 - (dist / PROXIMITY_BOOST_RADIUS));
                double score = rawScore + boost;

                candidates.add(new CandidateMatch(candidateCenter, cw, ch, score, rawScore));

                if (score > bestScore) {
                    bestScore = score;
                    bestCenter = candidateCenter;
                    rawScoreLog = rawScore;
                    bestRing = scored;
                }
            }

            lastStrongMatchCount = strongMatches;
            if (isBootstrapped && bestRing != null) lastPickHealth = pickHealth(minimap, bestRing);

            if (DebugManager.isENABLED() && strongMatches >= 2) {
                System.out.printf("[locateChampionViaTemplate] %d strong icon matches - clone likely present; anchoring to (%.1f, %.1f).%n",
                        strongMatches, anchorX, anchorY);
            }

            DebugImages.top10(minimap, candidates);
            DebugImages.comparedPixels(minimap, comparedWindows, bestCenter, cw, ch);

            if (bestScore > 0.45 && bestCenter.x > borderMarginX && bestCenter.x < minimap.width() - borderMarginX && bestCenter.y > borderMarginY && bestCenter.y < minimap.height() - borderMarginY) {

                DebugImages.matchBox(minimap, bestCenter.x, bestCenter.y,
                        lockedCoreTemplate.width(), lockedCoreTemplate.height(), new Scalar(0, 255, 0));
                if (DebugManager.isENABLED()) System.out.printf("[locateChampionViaTemplate] Locked Scale Match -> Raw: %.2f%% | Boosted: %.2f%%%n",
                        rawScoreLog * 100, bestScore * 100);
                return new TemplateMatch(bestCenter, bestScore, rawScoreLog);
            }

            return null;
        }

        if (championTemplate == null) {
            return null;
        }

        double globalBestScore = 0;
        Point globalBestCenter = null;
        Mat globalBestTemplate = null;
        int globalBestSize = 0;

        Map<Point, CandidateMatch> globalCandidatesMap = new HashMap<>();
        List<Mat> crops = new ArrayList<>();

        for (ScaledTemplate scaled : scaledTemplates()) {
            if (scaled.size() > minimap.width() || scaled.size() > minimap.height()) continue;

            Mat coreTemplate = scaled.core();
            int cw = coreTemplate.width();
            int ch = coreTemplate.height();

            if (DebugManager.isENABLED()) crops.add(coreTemplate.clone());

            for (IconCircle ally : allyCircles) {
                EvalResult eval = evaluateTemplateAtAlly(minimap, ally.center(), coreTemplate, scaled.enhancedCore(), 4);
                if (eval == null) continue;

                Point matchCenter = eval.center();
                double matchScore = eval.score();

                if (matchCenter.x > borderMarginX && matchCenter.x < minimap.width() - borderMarginX
                        && matchCenter.y > borderMarginY && matchCenter.y < minimap.height() - borderMarginY) {

                    if (matchScore > globalBestScore) {
                        globalBestScore = matchScore;
                        globalBestCenter = matchCenter;
                        if (globalBestTemplate != null) globalBestTemplate.release();
                        globalBestTemplate = coreTemplate.clone();
                        globalBestSize = scaled.size();
                    }

                    CandidateMatch current = globalCandidatesMap.get(ally.center());
                    if (current == null || matchScore > current.score()) {
                        globalCandidatesMap.put(ally.center(), new CandidateMatch(matchCenter, cw, ch, matchScore, matchScore));
                    }
                }
            }
        }

        DebugImages.croppedTemplates(crops);
        DebugImages.top10(minimap, new ArrayList<>(globalCandidatesMap.values()));

        if (globalBestScore > 0.67) {
            System.out.printf("[locateChampionViaTemplate] Scale locked at %dpx! Match: %.2f%%%n",
                    globalBestSize, globalBestScore * 100);
            this.lockedCoreTemplate = globalBestTemplate;

            double sigma = ImageUtils.getStdDev(this.lockedCoreTemplate);
            if (sigma < LOW_CONTRAST_STDDEV_THRESHOLD) {
                this.lockedCoreTemplateEnhanced = ImageUtils.applyEnhancement(this.lockedCoreTemplate);
                System.out.printf("[locateChampionViaTemplate] Low-contrast template detected (σ=%.1f) - CLAHE enabled.%n", sigma);
            } else {
                this.lockedCoreTemplateEnhanced = null;
            }

            if (DebugManager.isENABLED()) DebugImages.write("debug_locked_template.png", lockedCoreTemplate);
            this.lockedBlipRadius = Math.max(1, globalBestSize / 2);
            this.isScaleLocked = true;
            this.lockedMatchFailures = 0;

            DebugImages.matchBox(minimap, globalBestCenter.x, globalBestCenter.y,
                    lockedCoreTemplate.width(), lockedCoreTemplate.height(), new Scalar(0, 165, 255));
            return new TemplateMatch(globalBestCenter, globalBestScore, globalBestScore);
        }

        if (DebugManager.isENABLED()) System.out.printf("[locateChampionViaTemplate] FAILED: Best match was only %.2f%%%n", globalBestScore * 100);
        if (globalBestTemplate != null) globalBestTemplate.release();
        return null;
    }

    private List<ScaledTemplate> scaledTemplates() {
        if (scaledTemplates != null) return scaledTemplates;

        scaledTemplates = new ArrayList<>();
        for (int targetSize = SEARCH_SIZE_MAX; targetSize >= SEARCH_SIZE_MIN; targetSize--) {
            Mat resizedTemplate = new Mat();
            Imgproc.resize(championTemplate, resizedTemplate, new Size(targetSize, targetSize), 0, 0, Imgproc.INTER_AREA);

            int cx = (int) (resizedTemplate.width() * 0.125);
            int cy = (int) (resizedTemplate.height() * 0.125);
            int cw = (int) (resizedTemplate.width() * 0.75);
            int ch = (int) (resizedTemplate.height() * 0.75);

            if (cw <= 0 || ch <= 0) {
                resizedTemplate.release();
                continue;
            }

            Mat coreTemplate = new Mat(resizedTemplate, new Rect(cx, cy, cw, ch));
            Mat enhancedCore = (ImageUtils.getStdDev(coreTemplate) < LOW_CONTRAST_STDDEV_THRESHOLD)
                    ? ImageUtils.applyEnhancement(coreTemplate)
                    : null;
            scaledTemplates.add(new ScaledTemplate(targetSize, resizedTemplate, coreTemplate, enhancedCore));
        }
        return scaledTemplates;
    }

    void bootstrapFromHealthBar(Mat minimap, List<IconCircle> allies, float projX, float projY) {
        IconCircle nearest = null;
        double bestScore = -1.0;
        double secondScore = -1.0;

        for (IconCircle a : allies) {
            double ax = MapCoordinates.percentXPrecise(a.center().x, minimap.width());
            double ay = MapCoordinates.percentYPrecise(a.center().y, minimap.height());
            if (Math.hypot(ax - projX, ay - projY) > BOOTSTRAP_MAX_DIST) continue;

            Mat patch = extractIconTemplate(minimap, a.center(), a.radius());
            if (patch == null) continue;
            double score = championMatchScore(patch);
            DebugImages.saveTemplate("debug_extracted_icon_template_last.png", patch, score);
            patch.release();

            if (DebugManager.isENABLED()) System.out.printf("[bootstrap] candidate @(%.0f,%.0f) championMatch=%.2f%n",
                    a.center().x, a.center().y, score);

            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                nearest = a;
            } else if (score > secondScore) {
                secondScore = score;
            }
        }

        if (nearest == null || bestScore < BOOTSTRAP_MIN_CHAMPION_MATCH) {
            bootstrapConfidence = 0;
            bootstrapLastPick = null;
            return;
        }
        if (secondScore >= 0 && (bestScore - secondScore) < BOOTSTRAP_MATCH_MARGIN) {
            if (DebugManager.isENABLED()) System.out.printf("[bootstrap] Ambiguous appearance match (%.2f vs %.2f) - waiting for separation.%n",
                    bestScore, secondScore);
            bootstrapConfidence = 0;
            bootstrapLastPick = null;
            return;
        }

        if (isRegionContaminatedByEnemy(minimap, nearest.center(), nearest.radius())) {
            if (DebugManager.isENABLED()) System.out.printf("[bootstrap] Target circle isolated, but contaminated by enemy indicators. Skipping frame.%n");
            bootstrapConfidence = 0;
            bootstrapLastPick = null;
            return;
        }

        if (!isAllyCircleIsolated(nearest, allies) || MinimapRingDetector.overlappedByAllyRing(minimap, nearest)) {
            if (DebugManager.isENABLED()) System.out.printf("[bootstrap] Target overlapped by another ally icon - waiting for a clean frame.%n");
            bootstrapConfidence = 0;
            bootstrapLastPick = null;
            return;
        }

        if (bootstrapLastPick != null
                && Math.hypot(nearest.center().x - bootstrapLastPick.x, nearest.center().y - bootstrapLastPick.y) < 5.0) {
            bootstrapConfidence++;
        } else {
            bootstrapConfidence = 1;
        }
        bootstrapLastPick = nearest.center();

        if (bootstrapConfidence < BOOTSTRAP_CONFIRM_FRAMES) return;

        Mat core = extractIconTemplate(minimap, nearest.center(), nearest.radius());
        if (core == null) return;

        double validationScore = championMatchScore(core);
        System.out.printf("[bootstrap] Learned-vs-DDragon validation score: %.2f%n", validationScore);
        DebugImages.saveTemplate("debug_extracted_icon_template_lock.png", core, validationScore);
        DebugImages.saveLockContext(minimap, nearest, validationScore);

        if (lockedCoreTemplate != null) lockedCoreTemplate.release();
        if (lockedCoreTemplateEnhanced != null) lockedCoreTemplateEnhanced.release();
        lockedCoreTemplate = core;
        lockedBlipRadius = nearest.radius();
        isScaleLocked = true;
        isBootstrapped = true;
        lockedMatchFailures = 0;

        double sigma = ImageUtils.getStdDev(lockedCoreTemplate);
        lockedCoreTemplateEnhanced = (sigma < LOW_CONTRAST_STDDEV_THRESHOLD)
                ? ImageUtils.applyEnhancement(lockedCoreTemplate)
                : null;

        System.out.printf("[bootstrap] Self-learned template from live minimap (σ=%.1f)%s - scale lock acquired.%n",
                sigma, lockedCoreTemplateEnhanced != null ? " [CLAHE]" : "");
    }

    void checkLock(TemplateMatch match, float hpX, float hpY, float lastKnownX, float lastKnownY, int mapSize) {
        if (!isScaleLocked && !isBootstrapped) return;

        if (match == null) {
            double distanceMoved = Math.hypot(hpX - lastKnownX, hpY - lastKnownY);

            if (!(distanceMoved < 1.5)) {
                lockedMatchFailures++;
                if (lockedMatchFailures >= MAX_LOCKED_MATCH_FAILURES) {
                    if (DebugManager.isENABLED()) System.out.println("[bootstrap] Locked template failing repeatedly while moving - resetting to re-learn.");
                    resetScaleLock();
                }
            }
            return;
        }

        lockedMatchFailures = 0;

        float matchX = MapCoordinates.percentX(match.center().x, mapSize);
        float matchY = MapCoordinates.percentY(match.center().y, mapSize);
        double matchToHpDist = Math.hypot(matchX - hpX, matchY - hpY);

        if (matchToHpDist > HealthBarCalibration.MAX_HEALTHBAR_MATCH_DIST && lastStrongMatchCount < 2) {
            wrongLockStreak++;
            if (DebugManager.isENABLED()) System.out.printf("[bootstrap] Locked match %.1f%% from health bar with only %d strong match(es) - possible wrong lock (%d/%d).%n",
                    matchToHpDist, lastStrongMatchCount, wrongLockStreak, MAX_WRONG_LOCK_STREAK);
            if (wrongLockStreak >= MAX_WRONG_LOCK_STREAK) {
                if (DebugManager.isENABLED()) System.out.println("[bootstrap] Wrong lock confirmed (lone far match, not a clone) - resetting to re-learn.");
                resetScaleLock();
            }
        } else {
            wrongLockStreak = 0;
            if (isBootstrapped && matchToHpDist <= HealthBarCalibration.MAX_HEALTHBAR_MATCH_DIST) trackTemplateHealth();
        }
    }

    private PickHealth pickHealth(Mat minimap, ScoredRing pick) {
        lastPickRawScore = pick.eval().score();
        if (lastPickRawScore >= TEMPLATE_HEALTHY_MIN_SCORE) return PickHealth.HEALTHY;
        if (!pick.uncovered() || MinimapRingDetector.overlappedByAllyRing(minimap, pick.ring())) return PickHealth.UNKNOWN;
        return PickHealth.WEAK_ALONE;
    }

    private void trackTemplateHealth() {
        if (lastPickHealth == PickHealth.HEALTHY) {
            weakTemplateStreak = 0;
        } else if (lastPickHealth == PickHealth.WEAK_ALONE && ++weakTemplateStreak >= MAX_WEAK_TEMPLATE_STREAK) {
            if (DebugManager.isENABLED()) System.out.printf("[bootstrap] Learned icon only scores %.2f on the player's uncovered icon - learning it again.%n",
                    lastPickRawScore);
            weakTemplateStreak = 0;
            isBootstrapped = false;
            bootstrapConfidence = 0;
            bootstrapLastPick = null;
        }
    }

    private boolean isAllyCircleIsolated(IconCircle target, List<IconCircle> allies) {
        for (IconCircle other : allies) {
            if (other == target) continue;
            double dist = Math.hypot(other.center().x - target.center().x,
                    other.center().y - target.center().y);
            double minSeparation = (target.radius() + other.radius()) * ALLY_ISOLATION_FACTOR;
            if (dist < minSeparation) return false;
        }
        return true;
    }

    private double championMatchScore(Mat learnedCore) {
        if (championTemplate == null || learnedCore.empty()) return -1.0;
        Mat ddragonCore = null;
        Mat ref = new Mat();
        Mat result = new Mat();
        try {
            int cx = (int) (championTemplate.width() * ICON_CORE_MARGIN);
            int cy = (int) (championTemplate.height() * ICON_CORE_MARGIN);
            int cw = (int) (championTemplate.width() * ICON_CORE_CROP);
            int ch = (int) (championTemplate.height() * ICON_CORE_CROP);
            if (cw < 4 || ch < 4) return -1.0;
            ddragonCore = new Mat(championTemplate, new Rect(cx, cy, cw, ch));

            int refW = Math.max(4, (int) (learnedCore.width() * 0.85));
            int refH = Math.max(4, (int) (learnedCore.height() * 0.85));
            if (refW > learnedCore.width() || refH > learnedCore.height()) return -1.0;
            Imgproc.resize(ddragonCore, ref, new Size(refW, refH), 0, 0, Imgproc.INTER_AREA);

            Imgproc.matchTemplate(learnedCore, ref, result, Imgproc.TM_CCOEFF_NORMED);
            return Core.minMaxLoc(result).maxVal;
        } catch (Exception e) {
            DebugManager.logFailure("[bootstrap] Could not compare the learned icon", e);
            return -1.0;
        } finally {
            if (ddragonCore != null) ddragonCore.release();
            ref.release();
            result.release();
        }
    }

    private boolean isRegionContaminatedByEnemy(Mat minimap, Point center, int radius) {
        int expectedRadius = (int) Math.round(minimap.width() * BLIP_RADIUS_PER_MINIMAP_PX);
        int checkRadius = Math.max((int) (radius * 1.6), (int) (expectedRadius * 1.6));

        int x0 = (int) Math.max(0, center.x - checkRadius);
        int y0 = (int) Math.max(0, center.y - checkRadius);
        int x1 = (int) Math.min(minimap.width(), center.x + checkRadius);
        int y1 = (int) Math.min(minimap.height(), center.y + checkRadius);
        int w = x1 - x0;
        int h = y1 - y0;

        if (w <= 0 || h <= 0) return true;

        Mat roi = new Mat(minimap, new Rect(x0, y0, w, h));
        Mat redMask = new Mat();
        MinimapRingDetector.enemyRedMask(roi, redMask);

        Point roiCenter = new Point(center.x - x0, center.y - y0);
        int ignoreRadius = (int) (radius * 1.05);
        Imgproc.circle(redMask, roiCenter, ignoreRadius, new Scalar(0), -1);

        int redPixelCount = Core.countNonZero(redMask);

        double totalCheckedPixels = Math.max(1.0, (w * (double) h) - (Math.PI * ignoreRadius * (double) ignoreRadius));
        double redRatio = redPixelCount / totalCheckedPixels;

        if (DebugManager.isENABLED()) {
            DebugImages.write("debug_enemy_red_mask.png", redMask);
        }

        roi.release();
        redMask.release();

        return redRatio > 0.01;
    }

    private Mat extractIconTemplate(Mat minimap, Point center, int radius) {
        int boxHalf = Math.max(4, radius);
        int x = (int) Math.max(0, center.x - boxHalf);
        int y = (int) Math.max(0, center.y - boxHalf);
        int w = Math.min(minimap.width() - x, boxHalf * 2);
        int h = Math.min(minimap.height() - y, boxHalf * 2);
        if (w < 6 || h < 6) return null;

        Mat region = new Mat(minimap, new Rect(x, y, w, h)).clone();

        int cx = (int) (region.width() * ICON_CORE_MARGIN);
        int cy = (int) (region.height() * ICON_CORE_MARGIN);
        int cw = (int) (region.width() * ICON_CORE_CROP);
        int ch = (int) (region.height() * ICON_CORE_CROP);
        if (cw < 4 || ch < 4) {
            region.release();
            return null;
        }

        Mat core = new Mat(region, new Rect(cx, cy, cw, ch)).clone();
        region.release();
        return core;
    }

    private void resetScaleLock() {
        if (lockedCoreTemplate != null) {
            lockedCoreTemplate.release();
            lockedCoreTemplate = null;
        }
        if (lockedCoreTemplateEnhanced != null) {
            lockedCoreTemplateEnhanced.release();
            lockedCoreTemplateEnhanced = null;
        }
        isScaleLocked = false;
        isBootstrapped = false;
        lockedMatchFailures = 0;
        wrongLockStreak = 0;
        weakTemplateStreak = 0;
        lockedBlipRadius = 0;
        bootstrapConfidence = 0;
        bootstrapLastPick = null;
    }

    private EvalResult evaluateTemplateAtAlly(Mat minimap, Point ally, Mat template,
                                              Mat enhancedTemplate, int padding) {
        int cw = (enhancedTemplate != null ? enhancedTemplate : template).width();
        int ch = (enhancedTemplate != null ? enhancedTemplate : template).height();

        int startX = (int) Math.max(0, ally.x - (cw / 2.0) - padding);
        int startY = (int) Math.max(0, ally.y - (ch / 2.0) - padding);
        int roiW = cw + (padding * 2);
        int roiH = ch + (padding * 2);

        if (startX + roiW > minimap.width()) roiW = minimap.width() - startX;
        if (startY + roiH > minimap.height()) roiH = minimap.height() - startY;
        if (roiW < cw || roiH < ch) return null;

        Mat localRoi = new Mat(minimap, new Rect(startX, startY, roiW, roiH));
        Mat result = new Mat();
        Mat matchRoi = localRoi;
        Mat enhancedRoi = null;
        Mat blurredTemplate = null;
        Mat blurredRoi = null;

        try {
            Mat matchTemplate = template;

            if (enhancedTemplate != null) {
                enhancedRoi = ImageUtils.applyEnhancement(localRoi);
                matchRoi = enhancedRoi;
                matchTemplate = enhancedTemplate;
            }

            if (MATCH_BLUR_KERNEL >= 3
                    && matchTemplate.width() >= MATCH_BLUR_KERNEL && matchTemplate.height() >= MATCH_BLUR_KERNEL
                    && matchRoi.width() >= MATCH_BLUR_KERNEL && matchRoi.height() >= MATCH_BLUR_KERNEL) {
                blurredTemplate = new Mat();
                blurredRoi = new Mat();
                Imgproc.GaussianBlur(matchTemplate, blurredTemplate, new Size(MATCH_BLUR_KERNEL, MATCH_BLUR_KERNEL), 0);
                Imgproc.GaussianBlur(matchRoi, blurredRoi, new Size(MATCH_BLUR_KERNEL, MATCH_BLUR_KERNEL), 0);
                matchTemplate = blurredTemplate;
                matchRoi = blurredRoi;
            }

            Imgproc.matchTemplate(matchRoi, matchTemplate, result, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult mmr = Core.minMaxLoc(result);

            double subX = 0.0;
            double subY = 0.0;
            int px = (int) mmr.maxLoc.x;
            int py = (int) mmr.maxLoc.y;
            if (px > 0 && px < result.cols() - 1) {
                double l = result.get(py, px - 1)[0];
                double r = result.get(py, px + 1)[0];
                double d = l - 2 * mmr.maxVal + r;
                if (d < -1e-9) subX = Math.clamp((l - r) / (2 * d), -0.5, 0.5);
            }
            if (py > 0 && py < result.rows() - 1) {
                double t = result.get(py - 1, px)[0];
                double b = result.get(py + 1, px)[0];
                double d = t - 2 * mmr.maxVal + b;
                if (d < -1e-9) subY = Math.clamp((t - b) / (2 * d), -0.5, 0.5);
            }

            double matchCenterX = startX + mmr.maxLoc.x + subX + (cw / 2.0);
            double matchCenterY = startY + mmr.maxLoc.y + subY + (ch / 2.0);

            return new EvalResult(new Point(matchCenterX, matchCenterY), mmr.maxVal);
        } finally {
            localRoi.release();
            result.release();
            if (enhancedRoi != null) enhancedRoi.release();
            if (blurredTemplate != null) blurredTemplate.release();
            if (blurredRoi != null) blurredRoi.release();
        }
    }

    private List<IconCircle> overlappingNeighbors(IconCircle self, List<IconCircle> allies, List<IconCircle> enemies) {
        List<IconCircle> neighbors = new ArrayList<>();
        for (IconCircle other : allies) {
            if (other == self) continue;
            if (circlesOverlap(self, other)) neighbors.add(other);
        }
        for (IconCircle other : enemies) {
            if (circlesOverlap(self, other)) neighbors.add(other);
        }
        return neighbors;
    }

    private static boolean circlesOverlap(IconCircle a, IconCircle b) {
        double sep = Math.hypot(a.center().x - b.center().x, a.center().y - b.center().y);
        return sep < a.radius() + b.radius();
    }

    private EvalResult evaluateUncoveredPart(Mat minimap, IconCircle self, List<IconCircle> coveringIcons, Mat template) {
        int searchPad = ICON_CENTER_JITTER_PX;
        int cw = template.width();
        int ch = template.height();
        int channels = template.channels();
        if (channels != minimap.channels()) return null;

        int baseX = (int) Math.round(self.center().x - cw / 2.0);
        int baseY = (int) Math.round(self.center().y - ch / 2.0);
        int regionX = Math.max(0, baseX - searchPad);
        int regionY = Math.max(0, baseY - searchPad);
        int regionW = Math.min(minimap.width(), baseX + searchPad + cw) - regionX;
        int regionH = Math.min(minimap.height(), baseY + searchPad + ch) - regionY;
        if (regionW < cw || regionH < ch) return null;

        byte[] region = new byte[regionW * regionH * channels];
        Mat regionMat = new Mat(minimap, new Rect(regionX, regionY, regionW, regionH)).clone();
        regionMat.get(0, 0, region);
        regionMat.release();

        boolean[] regionUncovered = new boolean[regionW * regionH];
        for (int y = 0; y < regionH; y++) {
            for (int x = 0; x < regionW; x++) {
                regionUncovered[y * regionW + x] = !isCovered(regionX + x + 0.5, regionY + y + 0.5, coveringIcons);
            }
        }

        byte[] tpl = new byte[cw * ch * channels];
        template.get(0, 0, tpl);
        byte[] patch = new byte[cw * ch * channels];
        boolean[] visible = new boolean[cw * ch];
        int minVisible = (int) (cw * ch * OCCLUSION_MIN_VISIBLE_FRACTION);

        double bestScore = -1.0;
        Point bestCenter = null;

        for (int dy = -searchPad; dy <= searchPad; dy++) {
            for (int dx = -searchPad; dx <= searchPad; dx++) {
                int x0 = baseX + dx;
                int y0 = baseY + dy;
                if (x0 < 0 || y0 < 0 || x0 + cw > minimap.width() || y0 + ch > minimap.height()) continue;

                int localX = x0 - regionX;
                int localY = y0 - regionY;
                int visibleCount = 0;
                for (int y = 0; y < ch; y++) {
                    int regionRow = (localY + y) * regionW + localX;
                    System.arraycopy(region, regionRow * channels, patch, y * cw * channels, cw * channels);
                    for (int x = 0; x < cw; x++) {
                        boolean v = regionUncovered[regionRow + x];
                        visible[y * cw + x] = v;
                        if (v) visibleCount++;
                    }
                }
                if (visibleCount < minVisible) continue;

                double score = MathUtils.maskedZncc(tpl, patch, visible, channels);
                if (score > bestScore) {
                    bestScore = score;
                    bestCenter = new Point(x0 + (cw / 2.0), y0 + (ch / 2.0));
                }
            }
        }

        return bestCenter == null ? null : new EvalResult(bestCenter, bestScore);
    }

    static boolean isCovered(double px, double py, List<IconCircle> coveringIcons) {
        for (IconCircle other : coveringIcons) {
            double reach = other.radius() + COVERING_ICON_BORDER_PX;
            double dx = px - other.center().x;
            double dy = py - other.center().y;
            if (dx * dx + dy * dy < reach * reach) return true;
        }
        return false;
    }

    void release() {
        if (championTemplate != null) {
            championTemplate.release();
            championTemplate = null;
        }
        if (scaledTemplates != null) {
            scaledTemplates.forEach(ScaledTemplate::release);
            scaledTemplates = null;
        }
        if (lockedCoreTemplate != null) {
            lockedCoreTemplate.release();
            lockedCoreTemplate = null;
        }
        if (lockedCoreTemplateEnhanced != null) {
            lockedCoreTemplateEnhanced.release();
            lockedCoreTemplateEnhanced = null;
        }
    }
}
