package me.bedwarshurts.leagueproximitychat.position;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Scanner;

import me.bedwarshurts.leagueproximitychat.utils.RitoApiUtils;
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

            String listResponse = RitoApiUtils.fetchAPI("https://127.0.0.1:2999/liveclientdata/playerlist");

            if (listResponse == null) {
                System.err.println("Could not fetch player list.");
                return null;
            }

            int playerIndex = listResponse.indexOf(mySummonerName);
            if (playerIndex == -1) {
                System.err.println("Could not find " + mySummonerName + " in the player list.");
                return null;
            }

            String searchString = "\"rawChampionName\": \"game_character_displayname_";
            int rawNameIndex = listResponse.lastIndexOf(searchString, playerIndex);

            String rawName = listResponse.substring(rawNameIndex + searchString.length()).split("\"")[0];
            System.out.println("Detected Champion Codename: " + rawName);

            String latestPatch = getLatestDataDragonVersion();

            String ddragonUrl = "https://ddragon.leagueoflegends.com/cdn/" + latestPatch + "/img/champion/" + rawName + ".png";
            BufferedImage originalIcon = ImageIO.read(new URI(ddragonUrl).toURL());

            byte[] pixels = ((DataBufferByte) originalIcon.getRaster().getDataBuffer()).getData();
            Mat fullMat = new Mat(originalIcon.getHeight(), originalIcon.getWidth(), CvType.CV_8UC3);
            fullMat.put(0, 0, pixels);

            System.out.println("Successfully generated Raw 120x120 OpenCV template for " + rawName);
            return fullMat;
        } catch (Exception e) {
            System.err.println("Failed to automate template loading. Ensure the game is actively running in a match.");
            e.printStackTrace();
            return null;
        }
    }

    private static String getLatestDataDragonVersion() throws Exception {
        URL url = new URL("https://ddragon.leagueoflegends.com/api/versions.json");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        Scanner scanner = new Scanner(new InputStreamReader(conn.getInputStream()));
        String response = scanner.useDelimiter("\\A").next();
        scanner.close();

        return response.split("\"")[1];
    }
}