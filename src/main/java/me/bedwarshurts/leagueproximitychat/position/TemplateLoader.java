package me.bedwarshurts.leagueproximitychat.position;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Scanner;

import me.bedwarshurts.leagueproximitychat.utils.RitoApiUtils;
import me.bedwarshurts.leagueproximitychat.data.LeagueGame;
import me.bedwarshurts.leagueproximitychat.data.LeaguePlayer;
import org.opencv.core.Mat;
import org.opencv.core.CvType;
import org.opencv.imgcodecs.Imgcodecs;

public class TemplateLoader {

    public static Mat autoLoadChampionTemplate() {
        try {
            String mySummonerName = RitoApiUtils.getLocalSummonerName();

            if (mySummonerName == null) {
                System.err.println("Could not detect local player. Is the game running?");
                return null;
            }
            System.out.println("Detected Local Player: " + mySummonerName);

            LeagueGame gameData = RitoApiUtils.getLivePlayerList();
            if (gameData == null || gameData.players() == null) {
                System.err.println("Could not fetch or parse player list.");
                return null;
            }

            String championCodename = null;
            for (LeaguePlayer player : gameData.players()) {
                if (mySummonerName.equals(player.getSummonerName())) {

                    championCodename = player.getChampionName();
                    break;
                }
            }
            if (championCodename == null || championCodename.isEmpty()) {
                System.err.println("Could not find " + mySummonerName + " in the parsed player list.");
                return null;
            }
            championCodename = RitoApiUtils.sanitizeChampionName(championCodename);

            System.out.println("Detected Champion Codename: " + championCodename);

            String latestPatch = getLatestDataDragonVersion();
            String ddragonUrl = "https://ddragon.leagueoflegends.com/cdn/" + latestPatch + "/img/champion/" + championCodename + ".png";

            BufferedImage originalIcon = ImageIO.read(new URI(ddragonUrl).toURL());
            byte[] pixels = ((DataBufferByte) originalIcon.getRaster().getDataBuffer()).getData();
            Mat fullMat = new Mat(originalIcon.getHeight(), originalIcon.getWidth(), CvType.CV_8UC3);
            fullMat.put(0, 0, pixels);

            System.out.println("Successfully generated Raw 120x120 OpenCV template for " + championCodename);
            Imgcodecs.imwrite("debug/debug_template.png", fullMat);
            return fullMat;
        } catch (Exception e) {
            System.err.println("Failed to automate template loading. Ensure the game is actively running in a match.");
            System.err.println("Stacktrace: " + e.getMessage());
            return null;
        }
    }

    private static String getLatestDataDragonVersion() throws Exception {
        URL url = new URI("https://ddragon.leagueoflegends.com/api/versions.json").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        Scanner scanner = new Scanner(new InputStreamReader(conn.getInputStream()));
        String response = scanner.useDelimiter("\\A").next();
        scanner.close();

        return response.split("\"")[1];
    }
}