package me.bedwarshurts.leagueproximitychat;

import com.sun.net.httpserver.HttpServer;
import me.bedwarshurts.leagueproximitychat.position.ScreenPositionTracker;
import me.bedwarshurts.leagueproximitychat.position.TemplateLoader;
import me.bedwarshurts.leagueproximitychat.utils.WindowUtils;
import me.bedwarshurts.leagueproximitychat.websocket.CoordinateServer;
import nu.pattern.OpenCV;
import org.opencv.core.Mat;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

public class LeagueProximityChat {

    public static boolean wasPaused = false;
    public static boolean isAwaitingBrowser = true;
    public static boolean isTrackerReady = false;

    public static ScreenPositionTracker tracker = null;
    public static CoordinateServer server = null;

    public static void trackingLoop() throws InterruptedException {
        if (server.getConnections().isEmpty()) {
            if (!isAwaitingBrowser) {
                System.out.println("Browser disconnected.");
                isAwaitingBrowser = true;
            }
            Thread.sleep(1000);
            return;
        }

        if (isAwaitingBrowser) {
            System.out.println("LiveKit Connection detected!");
            isAwaitingBrowser = false;
        }

        if (!isTrackerReady) {
            System.out.println("Scanning for champion template...");
            Mat championTemplate = TemplateLoader.autoLoadChampionTemplate();

            if (championTemplate == null) {
                System.err.println("Failed to load champion template. Is the game running? Retrying in 2 seconds...");
                Thread.sleep(2000);
                return;
            }

            tracker = new ScreenPositionTracker(championTemplate);
            isTrackerReady = true;
            System.out.println("Starting position tracking");
        }

        if (!WindowUtils.isWindowFocused("League of Legends (TM) Client")) {
            if (!wasPaused) System.out.println("League of Legends lost focus. Pausing tracking...");
            wasPaused = true;
            Thread.sleep(1000);
            return;
        }

        if (wasPaused) {
            System.out.println("League of Legends focused. Resuming tracking...");
            wasPaused = false;
        }

        long startTime = System.currentTimeMillis();

        ScreenPositionTracker.TrackResult pos = tracker.trackPlayerPosition();
        server.broadcastCoordinates(pos.x(), pos.y(), pos.isDead());

        long elapsedTime = System.currentTimeMillis() - startTime;
        long sleepTime = Math.max(10, 30 - elapsedTime);

        Thread.sleep(sleepTime);
    }

    public static void startHttpServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);

        server.createContext("/", exchange -> {
            try (InputStream is = LeagueProximityChat.class.getResourceAsStream("/index.html")) {
                if (is == null) {
                    throw new Exception("Could not find index.html.");
                }

                byte[] htmlBytes = is.readAllBytes();

                exchange.getResponseHeaders().set("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, htmlBytes.length);

                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(htmlBytes);
                }
            } catch (Exception e) {
                String error = "Error: " + e.getMessage();
                exchange.sendResponseHeaders(404, error.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(error.getBytes());
                }
            }
        });

        server.start();
        System.out.println("Local Web Server running on port 8000!");

        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI("http://localhost:8000"));
            } else {
                System.out.println("Please manually go to: http://localhost:8000");
            }
        } catch (Exception e) {
            System.err.println("Failed to open browser: " + e.getMessage());
            System.out.println("Please manually go to: http://localhost:8000");
        }
    }

    public static void main(String[] args) {
        OpenCV.loadLocally();
        System.out.println("OpenCV loaded successfully.");

        try {
            startHttpServer();
        } catch (IOException e) {
            System.err.println("Failed to start local web server: " + e.getMessage());
        }

        server = new CoordinateServer(new InetSocketAddress("127.0.0.1", 8887));
        server.start();

        while (true) {
            try {
                trackingLoop();
            } catch (InterruptedException e) {
                System.err.println("Tracking loop interrupted: " + e.getMessage());
            }
        }
    }
}