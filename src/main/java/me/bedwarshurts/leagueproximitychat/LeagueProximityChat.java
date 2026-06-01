package me.bedwarshurts.leagueproximitychat;

import me.bedwarshurts.leagueproximitychat.position.ScreenPositionTracker;
import me.bedwarshurts.leagueproximitychat.position.TemplateLoader;
import me.bedwarshurts.leagueproximitychat.utils.WindowUtils;
import me.bedwarshurts.leagueproximitychat.websocket.CoordinateServer;
import nu.pattern.OpenCV;
import org.opencv.core.Mat;

import java.net.InetSocketAddress;
import java.util.Scanner;

public class LeagueProximityChat {

    public static boolean wasPaused = false;
    public static ScreenPositionTracker tracker = null;
    public static CoordinateServer server = null;

    public static void trackingLoop() throws InterruptedException {
        if (!WindowUtils.isWindowFocused("League of legends")) {
            if (!wasPaused) System.out.println("League of Legends lost focus. Pausing tracking engine...");
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

        System.out.printf("Current Map Position: X: %.2f%% | Y: %.2f%% | Dead: %b\n", pos.x, pos.y, pos.isDead);
        server.broadcastCoordinates(pos.x, pos.y, pos.isDead);

        long elapsedTime = System.currentTimeMillis() - startTime;
        long sleepTime = Math.max(10, 50 - elapsedTime);

        Thread.sleep(sleepTime);
    }

    public static void main(String[] args) {
        OpenCV.loadLocally();
        System.out.println("OpenCV loaded successfully.");

        Mat championTemplate = TemplateLoader.autoLoadChampionTemplate();
        if (championTemplate == null) {
            System.err.println("Failed to load champion template, please make sure the game is running!");
            new Scanner(System.in).nextLine();
            return;
        }

        tracker = new ScreenPositionTracker(championTemplate);
        server = new CoordinateServer(new InetSocketAddress("127.0.0.1", 8887));
        server.start();

        System.out.println("Beginning live tracking loop...");

        while (true) {
            try {
                trackingLoop();
            } catch (InterruptedException e) {
                System.err.println("Tracking loop interrupted: " + e.getMessage());
            }
        }
    }
}