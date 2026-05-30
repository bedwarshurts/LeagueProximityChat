package me.bedwarshurts.leagueproximitychat;

import me.bedwarshurts.leagueproximitychat.position.HybridPositionTracker;
import me.bedwarshurts.leagueproximitychat.utils.WindowUtils;
import me.bedwarshurts.leagueproximitychat.websocket.CoordinateServer;
import nu.pattern.OpenCV;

import java.net.InetSocketAddress;

public class LeagueProximityChat {
    public static void main(String[] args) {
        OpenCV.loadLocally();
        System.out.println("OpenCV loaded successfully.");

        HybridPositionTracker tracker = new HybridPositionTracker();
        CoordinateServer server = new CoordinateServer(new InetSocketAddress("127.0.0.1", 8887));
        server.start();

        System.out.println("Beginning live tracking loop...");

        boolean wasPaused = false;

        while (true) {
            if (WindowUtils.isWindowFocused("League of Legends")) {
                if (wasPaused) {
                    System.out.println("League of Legends focused. Resuming tracking...");
                    wasPaused = false;
                }

                long startTime = System.currentTimeMillis();

                HybridPositionTracker.TrackResult pos = tracker.trackPlayerPosition();

                System.out.printf("Current Map Position: X: %.2f%% | Y: %.2f%% | Dead: %b\n", pos.x, pos.y, pos.isDead);
                server.broadcastCoordinates(pos.x, pos.y, pos.isDead);

                long elapsedTime = System.currentTimeMillis() - startTime;
                long sleepTime = Math.max(10, 66 - elapsedTime);

                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            } else {
                if (!wasPaused) {
                    System.out.println("League of Legends lost focus. Pausing tracking engine...");
                    wasPaused = true;
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}