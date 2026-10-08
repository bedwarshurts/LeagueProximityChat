package me.bedwarshurts.leagueproximitychat.app;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;

public final class LoadingWindow {

    private static final int WIDTH = 440;
    private static final int HEIGHT = 170;
    private static final int PADDING = 28;
    private static final int BAR_HEIGHT = 6;
    private static final int FRAME_MS = 16;

    private static final Color BACKGROUND_TOP = new Color(12, 22, 36);
    private static final Color BACKGROUND_BOTTOM = new Color(2, 6, 12);
    private static final Color GLOW = new Color(10, 200, 185, 26);
    private static final Color BORDER = new Color(200, 170, 110, 70);
    private static final Color TRACK = new Color(240, 230, 210, 18);
    private static final Color GOLD = new Color(200, 170, 110);
    private static final Color GOLD_LIGHT = new Color(240, 230, 210);
    private static final Color GOLD_DEEP = new Color(120, 90, 40);
    private static final Color INK = new Color(240, 230, 210);
    private static final Color INK_DIM = new Color(107, 117, 133);
    private static final Color CLEAR_GOLD = new Color(200, 170, 110, 0);
    private static final Color CLEAR_WHITE = new Color(255, 255, 255, 0);
    private static final Color SHEEN = new Color(255, 255, 255, 120);

    private static final Font TITLE_FONT = new Font("Segoe UI", Font.BOLD, 17);
    private static final Font VERSION_FONT = new Font("Segoe UI", Font.PLAIN, 11);
    private static final Font STATUS_FONT = new Font("Segoe UI", Font.PLAIN, 13);
    private static final Font HINT_FONT = new Font("Segoe UI", Font.PLAIN, 11);

    private static JFrame frame;
    private static LoadingPanel panel;
    private static Timer animation;

    private LoadingWindow() {
    }

    public static void open() {
        if (GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeLater(() -> {
            if (frame != null) return;
            panel = new LoadingPanel();
            frame = new JFrame(AppConstants.APP_WINDOW_TITLE);
            frame.setUndecorated(true);
            frame.setIconImage(AppIcon.image());
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.setAlwaysOnTop(true);
            frame.setContentPane(panel);
            frame.setSize(WIDTH, HEIGHT);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            animation = new Timer(FRAME_MS, e -> panel.tick());
            animation.start();
        });
    }

    public static void status(String text, float percent) {
        SwingUtilities.invokeLater(() -> {
            if (panel != null) panel.setStatus(text, percent);
        });
    }

    public static void close() {
        SwingUtilities.invokeLater(() -> {
            if (frame == null) return;
            animation.stop();
            frame.dispose();
            frame = null;
            panel = null;
            animation = null;
        });
    }

    public static void closeAfter(int delayMs) {
        SwingUtilities.invokeLater(() -> {
            Timer timer = new Timer(delayMs, e -> close());
            timer.setRepeats(false);
            timer.start();
        });
    }

    private static final class LoadingPanel extends JComponent {

        private final long startNanos = System.nanoTime();
        private String status = "Starting up…";
        private float target = -1f;
        private float shown = 0f;

        void setStatus(String text, float percent) {
            status = text;
            float next = percent < 0 ? -1f : Math.min(percent, 100f) / 100f;
            if (target < 0 && next >= 0) shown = 0f;
            target = next;
        }

        void tick() {
            if (target >= 0) shown += (target - shown) * 0.15f;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int w = getWidth();
            int h = getHeight();

            g.setPaint(new GradientPaint(0, 0, BACKGROUND_TOP, 0, h, BACKGROUND_BOTTOM));
            g.fillRect(0, 0, w, h);
            g.setPaint(new RadialGradientPaint(new Point2D.Float(w * 0.85f, -30f), 260f,
                    new float[]{0f, 1f}, new Color[]{GLOW, new Color(10, 200, 185, 0)}));
            g.fillRect(0, 0, w, h);
            g.setColor(BORDER);
            g.drawRect(0, 0, w - 1, h - 1);

            Image icon = AppIcon.image();
            int textX = PADDING;
            if (icon != null) {
                g.drawImage(icon, PADDING, 26, 40, 40, null);
                textX = PADDING + 54;
            }
            g.setFont(TITLE_FONT);
            g.setColor(GOLD);
            g.drawString("LeagueProximityChat", textX, 45);
            g.setFont(VERSION_FONT);
            g.setColor(INK_DIM);
            g.drawString("Version " + AppInfo.version(), textX, 62);

            int barX = PADDING;
            int barY = 116;
            int barW = w - PADDING * 2;
            g.setFont(STATUS_FONT);
            g.setColor(INK);
            g.drawString(status, barX, barY - 12);
            if (target >= 0) {
                String percent = Math.round(target * 100) + "%";
                FontMetrics fm = g.getFontMetrics();
                g.setColor(GOLD);
                g.drawString(percent, barX + barW - fm.stringWidth(percent), barY - 12);
            }

            Shape track = new RoundRectangle2D.Float(barX, barY, barW, BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT);
            g.setColor(TRACK);
            g.fill(track);
            double seconds = (System.nanoTime() - startNanos) / 1e9;
            if (target >= 0) {
                paintFill(g, barX, barY, barW, seconds);
            } else {
                paintSlider(g, track, barX, barY, barW, seconds);
            }

            g.setFont(HINT_FONT);
            g.setColor(INK_DIM);
            g.drawString(AppConstants.CREDIT, barX, barY + 30);
            g.dispose();
        }

        private void paintFill(Graphics2D g, int barX, int barY, int barW, double seconds) {
            float fillW = Math.max(BAR_HEIGHT, barW * shown);
            Shape fill = new RoundRectangle2D.Float(barX, barY, fillW, BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT);
            g.setPaint(new GradientPaint(barX, 0, GOLD_DEEP, barX + fillW, 0, GOLD_LIGHT));
            g.fill(fill);

            float sheenW = 70f;
            float phase = (float) ((seconds % 1.6) / 1.6);
            float sheenX = barX - sheenW + (fillW + sheenW) * phase;
            Graphics2D sheen = (Graphics2D) g.create();
            sheen.clip(fill);
            sheen.setPaint(new LinearGradientPaint(sheenX, 0, sheenX + sheenW, 0,
                    new float[]{0f, 0.5f, 1f}, new Color[]{CLEAR_WHITE, SHEEN, CLEAR_WHITE}));
            sheen.fillRect((int) sheenX, barY, (int) sheenW + 1, BAR_HEIGHT);
            sheen.dispose();
        }

        private void paintSlider(Graphics2D g, Shape track, int barX, int barY, int barW, double seconds) {
            float segmentW = barW * 0.3f;
            float phase = (float) ((seconds % 1.8) / 1.8);
            float eased = (float) (0.5 - 0.5 * Math.cos(Math.PI * phase));
            float segmentX = barX - segmentW + (barW + segmentW) * eased;
            Graphics2D slider = (Graphics2D) g.create();
            slider.clip(track);
            slider.setPaint(new LinearGradientPaint(segmentX, 0, segmentX + segmentW, 0,
                    new float[]{0f, 0.6f, 1f}, new Color[]{CLEAR_GOLD, GOLD, GOLD_LIGHT}));
            slider.fillRect((int) segmentX, barY, (int) segmentW + 1, BAR_HEIGHT);
            slider.dispose();
        }
    }
}
