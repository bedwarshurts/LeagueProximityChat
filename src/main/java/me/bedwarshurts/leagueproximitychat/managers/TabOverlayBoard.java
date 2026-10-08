package me.bedwarshurts.leagueproximitychat.managers;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.Objects;

final class TabOverlayBoard extends JComponent {

    private static final double ARC_RADIUS = 1.12;
    private static final double ARC_WIDTH = 0.09;
    private static final double GLOW_WIDTH = 0.26;
    private static final double ARC_START = -20;
    private static final double ARC_EXTENT = 220;
    private static final double BUTTON = 0.78;
    private static final double BUTTON_DY = 0.5;
    private static final double POPUP_GAP = 0.25;
    private static final double POPUP_W = 5.4;
    private static final double POPUP_H = 0.96;
    private static final double TRACK_LEFT = 0.45;
    private static final double TRACK_RIGHT = 1.75;
    private static final double MARGIN = 1.6;
    private static final double MAX_VOLUME = 2.0;
    private static final double VOLUME_STEP = 0.05;

    private static final Color VOICE_GREEN = new Color(70, 225, 120);
    private static final Color VOICE_GLOW = new Color(70, 225, 120, 70);
    private static final Color BUTTON_FILL = new Color(1, 10, 19, 235);
    private static final Color BUTTON_EDGE = new Color(200, 170, 110, 170);
    private static final Color MUTED_FILL = new Color(70, 14, 22, 235);
    private static final Color TRACK = new Color(240, 230, 210, 40);
    private static final Color GLYPH_TRACK = new Color(240, 230, 210, 120);
    private static final Color TICK = new Color(240, 230, 210, 90);
    private static final Color FILL_START = new Color(120, 90, 40);
    private static final Color KNOB_EDGE = new Color(2, 6, 12);
    private static final float DISABLED_ALPHA = 0.4f;

    private enum Part { TOP, BOTTOM }

    private final TabOverlay overlay;
    private List<TabOverlay.Seat> seats = List.of();
    private double r = 20;
    private String popupFor;
    private String hoverFor;
    private Part hoverPart;
    private boolean dragging;

    TabOverlayBoard(TabOverlay overlay) {
        this.overlay = overlay;
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                TabOverlay.Seat popupSeat = seatFor(popupFor);
                if (popupSeat != null && popupRect(popupSeat).contains(e.getPoint())) {
                    if (trackHit(popupSeat).contains(e.getPoint())) {
                        dragging = true;
                        dragTo(popupSeat, e.getX());
                    }
                    return;
                }
                for (TabOverlay.Seat seat : seats) {
                    if (!hasButtons(seat)) continue;
                    String identity = seat.player().identity();
                    if (seat.player().self()) {
                        if (!overlay.voice(identity).inVoice) continue;
                        if (buttonRect(seat, Part.TOP).contains(e.getPoint())) {
                            overlay.toggleSelfMute();
                            return;
                        }
                        if (buttonRect(seat, Part.BOTTOM).contains(e.getPoint())) {
                            overlay.toggleSelfDeafen();
                            return;
                        }
                        continue;
                    }
                    if (buttonRect(seat, Part.TOP).contains(e.getPoint())) {
                        overlay.setMuted(identity, !overlay.voice(identity).muted);
                        repaint();
                        return;
                    }
                    if (buttonRect(seat, Part.BOTTOM).contains(e.getPoint())) {
                        popupFor = identity.equals(popupFor) ? null : identity;
                        repaint();
                        return;
                    }
                }
                if (popupFor != null) {
                    popupFor = null;
                    repaint();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                TabOverlay.Seat popupSeat = seatFor(popupFor);
                if (dragging && popupSeat != null) dragTo(popupSeat, e.getX());
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragging = false;
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return;
                TabOverlay.Seat popupSeat = seatFor(popupFor);
                if (popupSeat != null && trackHit(popupSeat).contains(e.getPoint())) {
                    overlay.setVolume(popupFor, 1.0);
                    repaint();
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                String identity = null;
                Part part = null;
                for (TabOverlay.Seat seat : seats) {
                    if (!hasButtons(seat)) continue;
                    for (Part p : Part.values()) {
                        if (buttonRect(seat, p).contains(e.getPoint())) {
                            identity = seat.player().identity();
                            part = p;
                        }
                    }
                }
                if (!Objects.equals(identity, hoverFor) || part != hoverPart) {
                    hoverFor = identity;
                    hoverPart = part;
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (hoverFor != null) {
                    hoverFor = null;
                    hoverPart = null;
                    repaint();
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    static Rectangle bounds(List<TabOverlay.Seat> seats, double r) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (TabOverlay.Seat s : seats) {
            minX = Math.min(minX, s.x() - MARGIN * r);
            maxX = Math.max(maxX, s.gapX() + (BUTTON / 2 + POPUP_GAP + POPUP_W + 0.3) * r);
            minY = Math.min(minY, s.y() - MARGIN * r);
            maxY = Math.max(maxY, s.y() + MARGIN * r);
        }
        int x = (int) Math.floor(minX);
        int y = (int) Math.floor(minY);
        return new Rectangle(x, y, (int) Math.ceil(maxX) - x, (int) Math.ceil(maxY) - y);
    }

    void setSeats(List<TabOverlay.Seat> seats, double radius) {
        this.seats = List.copyOf(seats);
        this.r = radius;
    }

    void reset() {
        popupFor = null;
        hoverFor = null;
        hoverPart = null;
        dragging = false;
    }

    private static boolean hasButtons(TabOverlay.Seat seat) {
        return seat.player() != null;
    }

    private TabOverlay.Seat seatFor(String identity) {
        if (identity == null) return null;
        for (TabOverlay.Seat seat : seats) {
            if (seat.player() != null && identity.equals(seat.player().identity())) return seat;
        }
        return null;
    }

    private Rectangle2D buttonRect(TabOverlay.Seat seat, Part part) {
        double size = BUTTON * r;
        double cy = seat.y() + (part == Part.TOP ? -BUTTON_DY : BUTTON_DY) * r;
        return new Rectangle2D.Double(seat.gapX() - size / 2, cy - size / 2, size, size);
    }

    private Rectangle2D popupRect(TabOverlay.Seat seat) {
        Rectangle2D volume = buttonRect(seat, Part.BOTTOM);
        double h = POPUP_H * r;
        return new Rectangle2D.Double(volume.getMaxX() + POPUP_GAP * r, volume.getCenterY() - h / 2, POPUP_W * r, h);
    }

    private double trackX(TabOverlay.Seat seat) {
        return popupRect(seat).getX() + TRACK_LEFT * r;
    }

    private double trackWidth() {
        return (POPUP_W - TRACK_LEFT - TRACK_RIGHT) * r;
    }

    private Rectangle2D trackHit(TabOverlay.Seat seat) {
        Rectangle2D popup = popupRect(seat);
        return new Rectangle2D.Double(trackX(seat) - 0.3 * r, popup.getY(), trackWidth() + 0.6 * r, popup.getHeight());
    }

    private void dragTo(TabOverlay.Seat seat, int x) {
        double t = Math.clamp((x - trackX(seat)) / trackWidth(), 0.0, 1.0);
        double volume = Math.round(t * MAX_VOLUME / VOLUME_STEP) * VOLUME_STEP;
        String identity = seat.player().identity();
        if (Math.abs(volume - overlay.voice(identity).volume) > 1e-6) {
            overlay.setVolume(identity, volume);
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        for (TabOverlay.Seat seat : seats) {
            if (seat.player() == null) continue;
            TabOverlay.Voice voice = overlay.voice(seat.player().identity());
            if (voice.inVoice) paintVoiceArc(g, seat);
            if (seat.player().self()) {
                paintSelfButtons(g, seat, voice.inVoice);
            } else {
                paintMuteButton(g, seat, voice.muted);
                paintVolumeButton(g, seat, voice.volume);
            }
        }
        TabOverlay.Seat popupSeat = seatFor(popupFor);
        if (popupSeat != null) paintPopup(g, popupSeat, overlay.voice(popupFor).volume);
        g.dispose();
    }

    private void paintVoiceArc(Graphics2D g, TabOverlay.Seat seat) {
        double radius = ARC_RADIUS * r;
        Arc2D arc = new Arc2D.Double(seat.x() - radius, seat.y() - radius, radius * 2, radius * 2, ARC_START, ARC_EXTENT, Arc2D.OPEN);
        g.setStroke(new BasicStroke((float) (GLOW_WIDTH * r), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(VOICE_GLOW);
        g.draw(arc);
        g.setStroke(new BasicStroke((float) (ARC_WIDTH * r), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(VOICE_GREEN);
        g.draw(arc);
    }

    private RoundRectangle2D buttonShape(Rectangle2D rect) {
        double corner = 0.22 * r;
        return new RoundRectangle2D.Double(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight(), corner, corner);
    }

    private boolean hovered(TabOverlay.Seat seat, Part part) {
        return part == hoverPart && seat.player().identity().equals(hoverFor);
    }

    private void paintButtonFrame(Graphics2D g, TabOverlay.Seat seat, Part part, boolean red, Color edge) {
        Rectangle2D rect = buttonRect(seat, part);
        RoundRectangle2D shape = buttonShape(rect);
        g.setColor(red ? MUTED_FILL : BUTTON_FILL);
        g.fill(shape);
        g.setStroke(new BasicStroke((float) Math.max(1.0, 0.05 * r)));
        g.setColor(red ? TabOverlay.RED : edge != null ? edge : hovered(seat, part) ? TabOverlay.GOLD_LIGHT : BUTTON_EDGE);
        g.draw(shape);
    }

    private void paintSelfButtons(Graphics2D g, TabOverlay.Seat seat, boolean inVoice) {
        Composite before = g.getComposite();
        if (!inVoice) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, DISABLED_ALPHA));
        boolean micMuted = inVoice && overlay.selfMicMuted();
        boolean deafened = inVoice && overlay.selfDeafened();

        paintButtonFrame(g, seat, Part.TOP, micMuted, inVoice ? null : BUTTON_EDGE);
        Rectangle2D mic = buttonRect(seat, Part.TOP);
        double u = mic.getWidth() / 24.0;
        double x = mic.getX();
        double c = mic.getCenterY();
        g.setColor(TabOverlay.INK);
        g.fill(new RoundRectangle2D.Double(x + 9 * u, c - 8 * u, 6 * u, 10 * u, 6 * u, 6 * u));
        g.setStroke(new BasicStroke((float) (1.6 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(x + 6.5 * u, c - 4.5 * u, 11 * u, 9.5 * u, 180, 180, Arc2D.OPEN));
        g.draw(new Line2D.Double(x + 12 * u, c + 5 * u, x + 12 * u, c + 8 * u));
        g.draw(new Line2D.Double(x + 9 * u, c + 8 * u, x + 15 * u, c + 8 * u));
        if (micMuted) slash(g, x, c, u);

        paintButtonFrame(g, seat, Part.BOTTOM, deafened, inVoice ? null : BUTTON_EDGE);
        Rectangle2D phones = buttonRect(seat, Part.BOTTOM);
        x = phones.getX();
        c = phones.getCenterY();
        g.setColor(TabOverlay.INK);
        g.setStroke(new BasicStroke((float) (1.7 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(x + 5 * u, c - 7 * u, 14 * u, 14 * u, 0, 180, Arc2D.OPEN));
        g.fill(new RoundRectangle2D.Double(x + 4 * u, c - 1 * u, 4.5 * u, 7 * u, 2 * u, 2 * u));
        g.fill(new RoundRectangle2D.Double(x + 15.5 * u, c - 1 * u, 4.5 * u, 7 * u, 2 * u, 2 * u));
        if (deafened) slash(g, x, c, u);

        g.setComposite(before);
    }

    private static void slash(Graphics2D g, double x, double c, double u) {
        g.setStroke(new BasicStroke((float) (3.2 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(MUTED_FILL);
        g.draw(new Line2D.Double(x + 5 * u, c - 8 * u, x + 19 * u, c + 8 * u));
        g.setStroke(new BasicStroke((float) (1.8 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(TabOverlay.RED);
        g.draw(new Line2D.Double(x + 5 * u, c - 8 * u, x + 19 * u, c + 8 * u));
    }

    private void paintMuteButton(Graphics2D g, TabOverlay.Seat seat, boolean muted) {
        Rectangle2D rect = buttonRect(seat, Part.TOP);
        paintButtonFrame(g, seat, Part.TOP, muted, null);

        double u = rect.getWidth() / 24.0;
        double x = rect.getX();
        double c = rect.getCenterY();
        Path2D speaker = new Path2D.Double();
        speaker.moveTo(x + 4.5 * u, c - 3 * u);
        speaker.lineTo(x + 8.5 * u, c - 3 * u);
        speaker.lineTo(x + 13 * u, c - 7 * u);
        speaker.lineTo(x + 13 * u, c + 7 * u);
        speaker.lineTo(x + 8.5 * u, c + 3 * u);
        speaker.lineTo(x + 4.5 * u, c + 3 * u);
        speaker.closePath();
        g.setColor(TabOverlay.INK);
        g.fill(speaker);

        g.setStroke(new BasicStroke((float) (1.7 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (muted) {
            g.setColor(TabOverlay.RED);
            g.draw(new Line2D.Double(x + 15 * u, c - 3.5 * u, x + 20.5 * u, c + 3.5 * u));
            g.draw(new Line2D.Double(x + 20.5 * u, c - 3.5 * u, x + 15 * u, c + 3.5 * u));
        } else {
            g.setColor(TabOverlay.INK);
            g.draw(new Arc2D.Double(x + 10.5 * u, c - 4 * u, 8 * u, 8 * u, -50, 100, Arc2D.OPEN));
            g.draw(new Arc2D.Double(x + 8.5 * u, c - 7 * u, 14 * u, 14 * u, -50, 100, Arc2D.OPEN));
        }
    }

    private void paintVolumeButton(Graphics2D g, TabOverlay.Seat seat, double volume) {
        Rectangle2D rect = buttonRect(seat, Part.BOTTOM);
        boolean open = seat.player().identity().equals(popupFor);
        paintButtonFrame(g, seat, Part.BOTTOM, false, open ? TabOverlay.TEAL : null);

        double w = rect.getWidth();
        double x0 = rect.getX() + 0.2 * w;
        double x1 = rect.getX() + 0.8 * w;
        double c = rect.getCenterY();
        double knobX = x0 + (x1 - x0) * Math.clamp(volume / MAX_VOLUME, 0.0, 1.0);
        g.setStroke(new BasicStroke((float) (0.08 * w), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(GLYPH_TRACK);
        g.draw(new Line2D.Double(x0, c, x1, c));
        g.setColor(TabOverlay.GOLD);
        g.draw(new Line2D.Double(x0, c, knobX, c));
        double knob = 0.26 * w;
        g.setColor(TabOverlay.GOLD_LIGHT);
        g.fill(new Ellipse2D.Double(knobX - knob / 2, c - knob / 2, knob, knob));
    }

    private void paintPopup(Graphics2D g, TabOverlay.Seat seat, double volume) {
        Rectangle2D popup = popupRect(seat);
        RoundRectangle2D shape = new RoundRectangle2D.Double(popup.getX(), popup.getY(), popup.getWidth(), popup.getHeight(), 0.3 * r, 0.3 * r);
        g.setColor(BUTTON_FILL);
        g.fill(shape);
        g.setStroke(new BasicStroke((float) Math.max(1.0, 0.05 * r)));
        g.setColor(BUTTON_EDGE);
        g.draw(shape);

        double x = trackX(seat);
        double width = trackWidth();
        double c = popup.getCenterY();
        double trackH = 0.14 * r;
        g.setColor(TRACK);
        g.fill(new RoundRectangle2D.Double(x, c - trackH / 2, width, trackH, trackH, trackH));
        double filled = width * Math.clamp(volume / MAX_VOLUME, 0.0, 1.0);
        if (filled > 0) {
            g.setPaint(new GradientPaint((float) x, 0, FILL_START, (float) (x + width), 0, TabOverlay.GOLD_LIGHT));
            g.fill(new RoundRectangle2D.Double(x, c - trackH / 2, filled, trackH, trackH, trackH));
        }
        g.setStroke(new BasicStroke((float) Math.max(1.0, 0.04 * r)));
        g.setColor(TICK);
        g.draw(new Line2D.Double(x + width / 2, c - 0.18 * r, x + width / 2, c + 0.18 * r));

        double knob = 0.36 * r;
        Ellipse2D handle = new Ellipse2D.Double(x + filled - knob / 2, c - knob / 2, knob, knob);
        g.setColor(TabOverlay.GOLD_LIGHT);
        g.fill(handle);
        g.setColor(KNOB_EDGE);
        g.draw(handle);

        g.setFont(new Font("Segoe UI", Font.BOLD, 1).deriveFont((float) (0.42 * r)));
        g.setColor(TabOverlay.INK);
        String percent = Math.round(volume * 100) + "%";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(percent, (float) (popup.getMaxX() - 0.3 * r - fm.stringWidth(percent)), (float) (c + fm.getAscent() / 2.0 - 0.06 * r));
    }
}
