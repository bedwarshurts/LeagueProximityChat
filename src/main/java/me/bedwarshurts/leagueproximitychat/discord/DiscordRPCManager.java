package me.bedwarshurts.leagueproximitychat.discord;

import com.jagrosh.discordipc.IPCClient;
import com.jagrosh.discordipc.IPCListener;
import com.jagrosh.discordipc.entities.Packet;
import com.jagrosh.discordipc.entities.RichPresence;
import com.jagrosh.discordipc.entities.User;
import me.bedwarshurts.leagueproximitychat.data.LeagueGame;
import me.bedwarshurts.leagueproximitychat.data.LeaguePlayer;
import me.bedwarshurts.leagueproximitychat.position.ScreenPositionTracker.TrackResult;
import me.bedwarshurts.leagueproximitychat.utils.RitoApiUtils;
import com.google.gson.JsonObject;

public class DiscordRPCManager {

    private static IPCClient client;
    private static final long APPLICATION_ID = 1512450479949615225L;

    private static boolean isRunning = false;
    private static String latestDDragonVersion = "16.11.1";
    private static long startTime;

    public static void start() {
        if (isRunning) return;

        try {
            latestDDragonVersion = RitoApiUtils.getLatestDataDragonVersion();
        } catch (Exception e) {
            System.err.println("Failed to fetch latest Data Dragon version, defaulting to 16.11.1");
        }

        client = new IPCClient(APPLICATION_ID);

        client.setListener(new IPCListener() {
            @Override
            public void onReady(IPCClient client) {
            }

            @Override
            public void onClose(IPCClient client, JsonObject json) {
            }

            @Override
            public void onDisconnect(IPCClient client, Throwable t) {
            }

            @Override
            public void onPacketSent(IPCClient client, Packet packet) {

            }

            @Override
            public void onPacketReceived(IPCClient client, Packet packet) {
            }

            @Override
            public void onActivityJoin(IPCClient client, String secret) {

            }

            @Override
            public void onActivitySpectate(IPCClient client, String secret) {

            }

            @Override
            public void onActivityJoinRequest(IPCClient client, String secret, User user) {

            }
        });
        try {
            client.connect();
            startTime = System.currentTimeMillis() / 1000L;
            isRunning = true;
        } catch (Exception e) {
            System.err.println("[RPC] Failed to connect: " + e.getMessage());
        }
    }

    public static void updatePresenceActive(TrackResult result, String championName) {
        if (!isRunning || client == null) return;

        String ddragonUrl = "https://ddragon.leagueoflegends.com/cdn/" + latestDDragonVersion + "/img/champion/" + championName + ".png";
        LeagueGame gameData = RitoApiUtils.getLivePlayerList();
        if (gameData == null) return;
        LeaguePlayer player = gameData.players().stream().filter(p -> p.getSummonerName().equals(RitoApiUtils.getLocalSummonerName())).findFirst().orElse(null);
        if (player == null) return;

        String state = result.isDead() ? "Respawning in " + Math.round(player.getRespawnTimer()) + "s" : "In Live Match";

        RichPresence.Builder builder = new RichPresence.Builder()
                .setState(state)
                .setDetails(String.format(" Level %d, %d/%d/%d, %d CS", player.getLevel(),
                        player.getScore().kills(), player.getScore().deaths(), player.getScore().assists(), player.getScore().creepScore()))
                .setStartTimestamp(startTime)
                .setLargeImageWithTooltip(ddragonUrl, championName)
                .setSmallImageWithTooltip("app_logo", "League Proximity Chat");

        client.sendRichPresence(builder.build());
    }

    public static void updatePresenceIdle() {
        if (!isRunning || client == null) return;

        RichPresence.Builder builder = new RichPresence.Builder()
                .setState("Waiting for the game to start")
                .setDetails("Idle")
                .setStartTimestamp(startTime)
                .setLargeImageWithTooltip("app_logo", "League Proximity Chat");

        client.sendRichPresence(builder.build());
    }

    public static void stop() {
        if (!isRunning || client == null) return;
        isRunning = false;
        client.close();
    }
}