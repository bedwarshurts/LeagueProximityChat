package me.bedwarshurts.leagueproximitychat.managers;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import me.bedwarshurts.leagueproximitychat.app.AppConstants;
import me.bedwarshurts.leagueproximitychat.app.AppIcon;
import me.bedwarshurts.leagueproximitychat.position.ScoreboardLocator;
import me.bedwarshurts.leagueproximitychat.position.ScreenCapture;
import me.bedwarshurts.leagueproximitychat.utils.RitoApiUtils;
import me.bedwarshurts.leagueproximitychat.utils.WindowUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class TabOverlay {

    public record Player(String identity, String name, String champion, boolean ally, boolean self) {
    }

    static final class Voice {
        volatile double volume = 1.0;
        volatile boolean muted = false;
        volatile boolean inVoice = false;
        volatile long changedLocallyMs = 0;
    }

    record Seat(Player player, boolean allyColumn, double x, double y, double gapX) {
        Seat shifted(double dx, double dy) {
            return new Seat(player, allyColumn, x + dx, y + dy, gapX + dx);
        }
    }

    private record Board(Rectangle game, double radius, List<Seat> seats) {
    }

    static final Color GOLD = new Color(200, 170, 110);
    static final Color GOLD_LIGHT = new Color(240, 230, 210);
    static final Color INK = new Color(240, 230, 210);
    static final Color INK_DIM = new Color(107, 117, 133);
    static final Color TEAL = new Color(10, 200, 185);
    static final Color RED = new Color(232, 69, 90);

    private static final int VK_TAB = 0x09;
    private static final int VK_LBUTTON = 0x01;
    private static final long TRACK_DRAG_MS = 30;
    private static final long TRACK_IDLE_MS = 250;
    private static final double TRACK_LOST_SCORE = 0.55;
    private static final double TRACK_SCORE_MARGIN = 0.03;
    private static final double TRACK_IDLE_MIN_MOVE = 4;
    private static final double TRACK_SEARCH_PER_RADIUS = 6;
    private static final double STABLE_PER_RADIUS = 0.35;
    private static final double STABLE_RADIUS_CHANGE = 0.15;
    private static final long POLL_MS = 20;
    private static final long DETECT_DELAY_MS = 180;
    private static final long REFRESH_DELAY_MS = 600;
    private static final double TRACK_DRAG_SEARCH_PER_RADIUS = 12;
    private static final double GAP_UPDATE_PX = 1.5;
    private static final double DEBUG_BUTTON = 0.78;
    private static final double DEBUG_BUTTON_DY = 0.5;
    private static final long LOCAL_CHANGE_HOLD_MS = 600;
    private static final double PORTRAIT_MATCH_MIN = 0.6;
    private static final int LOGO_MARGIN = 18;

    private static final int GWL_EXSTYLE = -20;
    private static final int WS_EX_TRANSPARENT = 0x00000020;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    private static final int WS_EX_NOACTIVATE = 0x08000000;
    private static final int WDA_EXCLUDEFROMCAPTURE = 0x00000011;
    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final HWND HWND_TOPMOST = new HWND(Pointer.createConstant(-1L));

    private final Consumer<String> sendToPage;
    private final Map<String, Voice> voices = new ConcurrentHashMap<>();
    private final Map<String, Mat> championIcons = new ConcurrentHashMap<>();
    private final Set<String> failedIcons = ConcurrentHashMap.newKeySet();
    private final ScreenCapture screenCapture = new ScreenCapture();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tab-overlay-worker");
        t.setDaemon(true);
        return t;
    });
    private volatile List<Player> players = List.of();
    private volatile Board board;
    private volatile boolean shown = false;
    private volatile boolean captureExcluded = false;
    private volatile boolean selfMicMuted = false;
    private volatile boolean selfDeafened = false;
    private volatile HWND boardHwnd;
    private volatile HWND logoHwnd;
    private JWindow boardWindow;
    private JWindow logoWindow;
    private TabOverlayBoard boardView;
    private LogoView logoView;
    private final AtomicBoolean tracking = new AtomicBoolean(false);
    private long tabDownMs = 0;
    private long lastTrackMs = 0;
    private int detectionsThisPress = 0;
    private ScoreboardLocator.Anchor anchor;
    private Board anchorBoard;
    private Long pendingMove;

    public TabOverlay(Consumer<String> sendToPage) {
        this.sendToPage = sendToPage;
    }

    public void start() {
        if (GraphicsEnvironment.isHeadless()) return;
        Thread poller = new Thread(this::pollLoop, "tab-overlay");
        poller.setDaemon(true);
        poller.start();
    }

    public void setMatch(List<Player> matchPlayers) {
        Set<String> identities = new HashSet<>();
        for (Player p : matchPlayers) {
            identities.add(p.identity());
            voices.computeIfAbsent(p.identity(), k -> new Voice());
            loadIcon(p.champion());
        }
        voices.keySet().retainAll(identities);
        players = List.copyOf(matchPlayers);
        board = null;
    }

    public void clearMatch() {
        players = List.of();
        voices.clear();
        board = null;
    }

    public void applyVoiceState(JSONObject json) {
        JSONArray list = json.optJSONArray("players");
        if (list == null) return;
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null || o.optString("identity").isEmpty()) continue;
            Voice v = voice(o.optString("identity"));
            v.inVoice = o.optBoolean("inVoice", false);
            if (now - v.changedLocallyMs > LOCAL_CHANGE_HOLD_MS) {
                v.volume = Math.clamp(o.optDouble("volume", 1.0), 0.0, 2.0);
                v.muted = o.optBoolean("muted", false);
            }
        }
        JSONObject self = json.optJSONObject("self");
        if (self != null) {
            selfMicMuted = self.optBoolean("micMuted", false);
            selfDeafened = self.optBoolean("deafened", false);
        }
        TabOverlayBoard view = boardView;
        if (view != null) view.repaint();
    }

    Voice voice(String identity) {
        return voices.computeIfAbsent(identity, k -> new Voice());
    }

    boolean selfMicMuted() {
        return selfMicMuted;
    }

    boolean selfDeafened() {
        return selfDeafened;
    }

    void toggleSelfMute() {
        sendToPage.accept(new JSONObject().put("type", "TOGGLE_MUTE").toString());
    }

    void toggleSelfDeafen() {
        sendToPage.accept(new JSONObject().put("type", "TOGGLE_DEAFEN").toString());
    }

    void setVolume(String identity, double volume) {
        Voice v = voice(identity);
        v.volume = volume;
        v.changedLocallyMs = System.currentTimeMillis();
        sendToPage.accept(new JSONObject()
                .put("type", "SET_PLAYER_VOLUME")
                .put("identity", identity)
                .put("volume", volume)
                .toString());
    }

    void setMuted(String identity, boolean muted) {
        Voice v = voice(identity);
        v.muted = muted;
        v.changedLocallyMs = System.currentTimeMillis();
        sendToPage.accept(new JSONObject()
                .put("type", "SET_PLAYER_MUTED")
                .put("identity", identity)
                .put("muted", muted)
                .toString());
    }

    private void pollLoop() {
        while (true) {
            try {
                long now = System.currentTimeMillis();
                boolean held = !players.isEmpty() && tabHeld() && gameInFront();
                if (held && !shown) {
                    shown = true;
                    tabDownMs = now;
                    detectionsThisPress = 0;
                    SwingUtilities.invokeLater(this::show);
                } else if (!held && shown) {
                    shown = false;
                    SwingUtilities.invokeLater(this::hide);
                }
                if (held && wantsDetection(now)) {
                    detectionsThisPress++;
                    worker.execute(this::detect);
                } else if (held && wantsTracking(now) && tracking.compareAndSet(false, true)) {
                    lastTrackMs = now;
                    worker.execute(() -> {
                        try {
                            track();
                        } finally {
                            tracking.set(false);
                        }
                    });
                }
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                DebugManager.logFailure("[TabOverlay] Key check failed", e);
            }
        }
    }

    private boolean wantsDetection(long now) {
        long held = now - tabDownMs;
        if (detectionsThisPress == 0) {
            if (!captureExcluded && board != null && board.game().equals(gameBounds())) return false;
            return held >= DETECT_DELAY_MS;
        }
        return detectionsThisPress == 1 && held >= REFRESH_DELAY_MS && (board == null || captureExcluded);
    }

    private boolean wantsTracking(long now) {
        if (board == null || !captureExcluded || now - tabDownMs < DETECT_DELAY_MS) return false;
        boolean dragging = (User32.INSTANCE.GetAsyncKeyState(VK_LBUTTON) & 0x8000) != 0;
        return now - lastTrackMs >= (dragging ? TRACK_DRAG_MS : TRACK_IDLE_MS);
    }

    private static boolean tabHeld() {
        return (User32.INSTANCE.GetAsyncKeyState(VK_TAB) & 0x8000) != 0;
    }

    private boolean gameInFront() {
        HWND front = User32.INSTANCE.GetForegroundWindow();
        if (front == null) return false;
        if (front.equals(boardHwnd) || front.equals(logoHwnd)) return true;
        return WindowUtils.isWindowFocused(AppConstants.GAME_WINDOW_TITLE);
    }

    private static Rectangle gameBounds() {
        Rectangle game = WindowUtils.getGameWindowBounds(AppConstants.GAME_WINDOW_TITLE);
        return game == null || game.width <= 0 || game.height <= 0 ? null : game;
    }

    private void detect() {
        Rectangle game = gameBounds();
        if (game == null || !shown) return;
        Mat frame = screenCapture.captureWindowClient(AppConstants.GAME_WINDOW_TITLE);
        if (frame == null) return;
        try {
            ScoreboardLocator.Layout layout = ScoreboardLocator.find(frame);
            if (layout == null) {
                if (DebugManager.isENABLED()) {
                    System.out.println("[TabOverlay] Could not find the scoreboard portraits on screen.");
                    Imgcodecs.imwrite(DebugManager.getDebugDir() + "/debug_scoreboard.png", frame);
                }
                return;
            }
            Board previous = board;
            boolean sameWindow = previous != null && previous.game().equals(game);
            List<Seat> seats = new ArrayList<>();
            seats.addAll(seat(frame, layout.allies(), players.stream().filter(Player::ally).toList(), true,
                    ScoreboardLocator.gapOffset(frame, layout.allies(), layout.radius()), sameWindow ? column(previous, true) : null));
            seats.addAll(seat(frame, layout.enemies(), players.stream().filter(p -> !p.ally()).toList(), false,
                    ScoreboardLocator.gapOffset(frame, layout.enemies(), layout.radius()), sameWindow ? column(previous, false) : null));
            Board fresh = new Board(game, layout.radius(), seats);
            Board next = sameWindow && samePlace(previous, fresh) ? keepPlace(previous, fresh) : fresh;
            board = next;
            anchorBoard = next;
            pendingMove = null;
            if (anchor != null) anchor.release();
            anchor = ScoreboardLocator.anchor(frame, layout);
            if (DebugManager.isENABLED() && next == fresh) saveDebugImage(frame, next.radius(), next.seats());
            SwingUtilities.invokeLater(() -> {
                if (shown) showBoard();
            });
        } catch (Exception e) {
            DebugManager.logFailure("[TabOverlay] Could not place the scoreboard buttons", e);
        } finally {
            frame.release();
        }
    }

    private void track() {
        Board current = board;
        Board base = anchorBoard;
        ScoreboardLocator.Anchor a = anchor;
        if (!shown || current == null || base == null || a == null || current.seats().isEmpty()) return;
        Rectangle game = gameBounds();
        if (game == null || !game.equals(base.game())) {
            detect();
            return;
        }

        int dx = (int) Math.round(current.seats().getFirst().x() - base.seats().getFirst().x());
        int dy = (int) Math.round(current.seats().getFirst().y() - base.seats().getFirst().y());
        boolean dragging = (User32.INSTANCE.GetAsyncKeyState(VK_LBUTTON) & 0x8000) != 0;
        int reach = (int) Math.round(base.radius() * (dragging ? TRACK_DRAG_SEARCH_PER_RADIUS : TRACK_SEARCH_PER_RADIUS));
        Rectangle region = new Rectangle(a.rect().x + dx - reach, a.rect().y + dy - reach,
                a.rect().width + reach * 2, a.rect().height + reach * 2)
                .intersection(new Rectangle(0, 0, game.width, game.height));
        Mat captured = region.isEmpty() ? null : screenCapture.captureWindowClientRegion(AppConstants.GAME_WINDOW_TITLE, region);
        if (captured == null) return;
        ScoreboardLocator.Fix fix;
        try {
            fix = ScoreboardLocator.track(captured, new Rect(region.x, region.y, region.width, region.height), a,
                    a.rect().x + dx, a.rect().y + dy);
        } finally {
            captured.release();
        }
        if (fix == null) return;
        if (Math.max(fix.score(), fix.stayScore()) < TRACK_LOST_SCORE) {
            if (DebugManager.isENABLED()) System.out.printf("[TabOverlay] Lost the scoreboard while following it (match %.2f) - searching again.%n", fix.score());
            detect();
            return;
        }

        int moveX = fix.x() - a.rect().x;
        int moveY = fix.y() - a.rect().y;
        if (!acceptMove(moveX, moveY, dx, dy, fix, dragging)) return;
        List<Seat> moved = new ArrayList<>();
        for (Seat s : base.seats()) moved.add(s.shifted(moveX, moveY));
        board = new Board(base.game(), base.radius(), moved);
        SwingUtilities.invokeLater(() -> {
            if (shown) showBoard();
        });
    }

    private boolean acceptMove(int moveX, int moveY, int dx, int dy, ScoreboardLocator.Fix fix, boolean dragging) {
        if ((moveX == dx && moveY == dy) || fix.score() - fix.stayScore() < TRACK_SCORE_MARGIN) {
            pendingMove = null;
            return false;
        }
        if (!dragging && Math.hypot(moveX - dx, moveY - dy) < TRACK_IDLE_MIN_MOVE) {
            long key = ((long) moveX << 32) ^ (moveY & 0xffffffffL);
            if (pendingMove == null || pendingMove != key) {
                pendingMove = key;
                return false;
            }
        }
        pendingMove = null;
        return true;
    }

    private static List<Player> column(Board board, boolean ally) {
        return board.seats().stream().filter(s -> s.allyColumn() == ally).map(Seat::player).toList();
    }

    private static boolean samePlace(Board previous, Board fresh) {
        if (previous.seats().size() != fresh.seats().size()) return false;
        if (Math.abs(previous.radius() - fresh.radius()) > fresh.radius() * STABLE_RADIUS_CHANGE) return false;
        for (int i = 0; i < fresh.seats().size(); i++) {
            Seat a = previous.seats().get(i);
            Seat b = fresh.seats().get(i);
            if (a.allyColumn() != b.allyColumn() || Math.hypot(a.x() - b.x(), a.y() - b.y()) > fresh.radius() * STABLE_PER_RADIUS) return false;
        }
        return true;
    }

    private static Board keepPlace(Board previous, Board fresh) {
        List<Seat> seats = new ArrayList<>();
        for (int i = 0; i < fresh.seats().size(); i++) {
            Seat a = previous.seats().get(i);
            Seat b = fresh.seats().get(i);
            double gap = Math.abs((b.gapX() - b.x()) - (a.gapX() - a.x())) > GAP_UPDATE_PX ? a.x() + (b.gapX() - b.x()) : a.gapX();
            seats.add(new Seat(b.player(), a.allyColumn(), a.x(), a.y(), gap));
        }
        return new Board(previous.game(), previous.radius(), seats);
    }

    private List<Seat> seat(Mat frame, List<ScoreboardLocator.Portrait> portraits, List<Player> team, boolean allyColumn,
                            double gap, List<Player> previous) {
        Player[] assigned = new Player[portraits.size()];
        Set<Player> placed = new HashSet<>();
        double[][] scores = new double[portraits.size()][team.size()];
        for (int r = 0; r < portraits.size(); r++) {
            for (int p = 0; p < team.size(); p++) {
                scores[r][p] = ScoreboardLocator.match(frame, portraits.get(r), championIcons.get(team.get(p).champion()));
            }
        }
        while (true) {
            int bestRow = -1;
            int bestPlayer = -1;
            double best = PORTRAIT_MATCH_MIN;
            for (int r = 0; r < portraits.size(); r++) {
                if (assigned[r] != null) continue;
                for (int p = 0; p < team.size(); p++) {
                    if (placed.contains(team.get(p)) || scores[r][p] < best) continue;
                    best = scores[r][p];
                    bestRow = r;
                    bestPlayer = p;
                }
            }
            if (bestRow < 0) break;
            assigned[bestRow] = team.get(bestPlayer);
            placed.add(team.get(bestPlayer));
        }
        if (previous != null && previous.size() == portraits.size()) {
            for (int r = 0; r < portraits.size(); r++) {
                Player earlier = previous.get(r);
                if (assigned[r] == null && earlier != null && team.contains(earlier) && !placed.contains(earlier)) {
                    assigned[r] = earlier;
                    placed.add(earlier);
                }
            }
        }
        List<Player> leftover = team.stream().filter(p -> !placed.contains(p)).toList();
        int next = 0;
        for (int r = 0; r < portraits.size() && next < leftover.size(); r++) {
            if (assigned[r] == null) assigned[r] = leftover.get(next++);
        }

        List<Seat> seats = new ArrayList<>();
        for (int r = 0; r < portraits.size(); r++) {
            ScoreboardLocator.Portrait p = portraits.get(r);
            seats.add(new Seat(assigned[r], allyColumn, p.x(), p.y(), p.x() + gap));
        }
        return seats;
    }

    private void show() {
        if (players.isEmpty()) return;
        ensureWindows();
        Rectangle game = gameBounds();
        double dpi = dpiScale();
        Rectangle logical = game != null ? logical(game, dpi)
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();

        logoView.scale = logical.height / 1080.0;
        Dimension logoSize = logoView.logoSize();
        int margin = (int) Math.round(LOGO_MARGIN * logoView.scale);
        logoWindow.setBounds(logical.x + margin, logical.y + margin, logoSize.width, logoSize.height);
        showWithoutFocus(logoWindow, logoHwnd);

        Board current = board;
        if (current != null && current.game().equals(game)) showBoard();
    }

    private void showBoard() {
        Board current = board;
        if (current == null || boardWindow == null) return;
        double dpi = dpiScale();
        double r = current.radius() / dpi;
        List<Seat> seats = new ArrayList<>();
        for (Seat s : current.seats()) {
            seats.add(new Seat(s.player(), s.allyColumn(), (current.game().x + s.x()) / dpi, (current.game().y + s.y()) / dpi,
                    (current.game().x + s.gapX()) / dpi));
        }
        Rectangle bounds = TabOverlayBoard.bounds(seats, r);
        List<Seat> local = new ArrayList<>();
        for (Seat s : seats) local.add(s.shifted(-bounds.x, -bounds.y));
        boardView.setSeats(local, r);
        boardWindow.setBounds(bounds);
        showWithoutFocus(boardWindow, boardHwnd);
        boardView.repaint();
    }

    private void hide() {
        if (boardWindow != null) boardWindow.setVisible(false);
        if (logoWindow != null) logoWindow.setVisible(false);
        if (boardView != null) boardView.reset();
    }

    private void ensureWindows() {
        if (boardWindow != null) return;
        boardView = new TabOverlayBoard(this);
        boardWindow = overlayWindow(boardView);
        boardHwnd = prepare(boardWindow, false);
        logoView = new LogoView();
        logoWindow = overlayWindow(logoView);
        logoHwnd = prepare(logoWindow, true);
    }

    private static JWindow overlayWindow(JComponent content) {
        JWindow window = new JWindow();
        window.setAlwaysOnTop(true);
        window.setFocusableWindowState(false);
        window.setAutoRequestFocus(false);
        window.setBackground(new Color(0, 0, 0, 0));
        content.setOpaque(false);
        window.setContentPane(content);
        window.addNotify();
        return window;
    }

    private HWND prepare(JWindow window, boolean clickThrough) {
        HWND hwnd = new HWND(Native.getWindowPointer(window));
        int style = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE) | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW;
        if (clickThrough) style |= WS_EX_TRANSPARENT;
        User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE, style);
        try {
            boolean excluded = WindowUtils.CustomUser32.INSTANCE.SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE);
            if (!clickThrough) captureExcluded = excluded;
        } catch (Throwable t) {
            if (!clickThrough) captureExcluded = false;
        }
        return hwnd;
    }

    private static void showWithoutFocus(JWindow window, HWND hwnd) {
        window.setVisible(true);
        User32.INSTANCE.SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
    }

    private static double dpiScale() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getDefaultTransform().getScaleX();
    }

    private static Rectangle logical(Rectangle physical, double dpi) {
        return new Rectangle((int) Math.round(physical.x / dpi), (int) Math.round(physical.y / dpi),
                (int) Math.round(physical.width / dpi), (int) Math.round(physical.height / dpi));
    }

    private void loadIcon(String champion) {
        if (champion == null || champion.isEmpty() || championIcons.containsKey(champion) || failedIcons.contains(champion)) return;
        worker.execute(() -> {
            if (championIcons.containsKey(champion)) return;
            try (InputStream in = new URI("https://ddragon.leagueoflegends.com/cdn/" + RitoApiUtils.getLatestDataDragonVersion()
                    + "/img/champion/" + champion + ".png").toURL().openStream()) {
                Mat icon = Imgcodecs.imdecode(new MatOfByte(in.readAllBytes()), Imgcodecs.IMREAD_COLOR);
                if (icon.empty()) throw new IllegalStateException("not an image");
                championIcons.put(champion, icon);
            } catch (Exception e) {
                failedIcons.add(champion);
                DebugManager.logFailure("[TabOverlay] Could not load the " + champion + " icon", e);
            }
        });
    }

    private void saveDebugImage(Mat frame, double radius, List<Seat> seats) {
        Mat image = frame.clone();
        try {
            for (Seat s : seats) {
                Point center = new Point(s.x(), s.y());
                Imgproc.circle(image, center, (int) Math.round(radius), new Scalar(0, 255, 0), 2);
                String label = s.player() == null ? "?" : s.player().name() + (s.player().self() ? " (you)" : "");
                Imgproc.putText(image, label, new Point(s.x() - radius, s.y() - radius - 6), Imgproc.FONT_HERSHEY_SIMPLEX,
                        0.5, new Scalar(0, 255, 255), 1);
                double half = DEBUG_BUTTON * radius / 2;
                for (int sign : new int[]{-1, 1}) {
                    double cy = s.y() + sign * DEBUG_BUTTON_DY * radius;
                    Imgproc.rectangle(image, new Point(s.gapX() - half, cy - half), new Point(s.gapX() + half, cy + half),
                            new Scalar(0, 200, 255), 1);
                }
            }
            Imgcodecs.imwrite(DebugManager.getDebugDir() + "/debug_scoreboard.png", image);
        } finally {
            image.release();
        }
    }

    private static final class LogoView extends JComponent {

        private static final String TITLE = "LeagueProximityChat";
        private static final int ICON = 56;
        private static final int GAP = 12;
        private static final int SHADOW = 2;
        private static final Color SHADOW_COLOR = new Color(0, 0, 0, 190);

        double scale = 1.0;

        Dimension logoSize() {
            int text = Math.max(getFontMetrics(titleFont()).stringWidth(TITLE),
                    getFontMetrics(creditFont()).stringWidth(AppConstants.CREDIT));
            return new Dimension(textX() + text + px(SHADOW + 4), px(ICON + SHADOW + 4));
        }

        private int textX() {
            return AppIcon.image() != null ? px(ICON + GAP) : 0;
        }

        private Font titleFont() {
            return new Font("Segoe UI", Font.BOLD, 1).deriveFont((float) (26 * scale));
        }

        private Font creditFont() {
            return new Font("Segoe UI", Font.PLAIN, 1).deriveFont((float) (14 * scale));
        }

        private int px(double units) {
            return (int) Math.round(units * scale);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

            Image icon = AppIcon.image();
            if (icon != null) g.drawImage(icon, 0, 0, px(ICON), px(ICON), null);

            int shadow = Math.max(1, px(SHADOW));
            drawShadowed(g, TITLE, titleFont(), GOLD, textX(), px(26), shadow);
            drawShadowed(g, AppConstants.CREDIT, creditFont(), INK, textX(), px(48), shadow);
            g.dispose();
        }

        private static void drawShadowed(Graphics2D g, String text, Font font, Color color, int x, int y, int shadow) {
            g.setFont(font);
            g.setColor(SHADOW_COLOR);
            g.drawString(text, x + shadow, y + shadow);
            g.setColor(color);
            g.drawString(text, x, y);
        }
    }
}
