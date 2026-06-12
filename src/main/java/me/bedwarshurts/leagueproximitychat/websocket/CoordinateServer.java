package me.bedwarshurts.leagueproximitychat.websocket;

import lombok.Getter;
import lombok.Setter;
import me.bedwarshurts.leagueproximitychat.LeagueProximityChat;
import me.bedwarshurts.leagueproximitychat.livekit.LiveKitUser;
import me.bedwarshurts.leagueproximitychat.utils.RitoApiUtils;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.util.Locale;

public class CoordinateServer extends WebSocketServer {

    private volatile WebSocket activeConnection = null;
    @Getter @Setter private volatile boolean userRequestedConnection = false;

    public CoordinateServer(InetSocketAddress address) {
        super(address);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        if (activeConnection != null && activeConnection.isOpen()) {
            activeConnection.send("{\"type\":\"REPLACED\"}");
            activeConnection.close(1000, "Replaced by a newer tab.");
        }
        activeConnection = conn;
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        if (activeConnection == conn) {
            activeConnection = null;
            userRequestedConnection = false;

            if (LeagueProximityChat.getActiveRoom() != null) {
                LeagueProximityChat.setActiveRoom(null);
            }

            System.out.println("Active browser tab closed.");
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if ("REQUEST_JOIN".equals(message)) {
            userRequestedConnection = true;
            return;
        } else if ("CANCEL_JOIN".equals(message)) {
            userRequestedConnection = false;

            LeagueProximityChat.setHasConnectedToLiveKit(false);
            if (LeagueProximityChat.getActiveRoom() != null) {
                LeagueProximityChat.setActiveRoom(null);
            }

            return;
        }

        if (message.startsWith("{")) {
            try {
                JSONObject json = new JSONObject(message);
                String type = json.optString("type");
                String identity = json.optString("identity");
                String name = json.optString("name", "Unknown");

                if (LeagueProximityChat.getActiveRoom() != null) {

                    LiveKitUser targetUser = new LiveKitUser(identity, name);

                    String localIdentity = RitoApiUtils.getLocalSummonerName();
                    LiveKitUser localModerator = (localIdentity != null && !localIdentity.isEmpty())
                            ? new LiveKitUser(localIdentity, "") : null;

                    if ("PLAYER_JOINED".equals(type) && !identity.isEmpty()) {
                        if (LeagueProximityChat.getActiveRoom().isBanned(targetUser)) {
                            System.out.println("Banned user " + identity + " tried to rejoin. Auto-kicking...");
                            if (localModerator != null
                                    && LeagueProximityChat.getActiveRoom().kickUser(targetUser, localModerator)) {
                                sendToActive(new JSONObject().put("type", "PLAYER_BANNED").put("identity", identity).toString());
                            }
                        } else {
                            LeagueProximityChat.getActiveRoom().addParticipant(targetUser);
                        }
                    }
                    else if ("PLAYER_LEFT".equals(type) && !identity.isEmpty()) {
                        LeagueProximityChat.getActiveRoom().removeParticipant(targetUser);
                    }
                    else if ("KICK_USER".equals(type) && !identity.isEmpty()) {
                        if (localModerator != null
                                && LeagueProximityChat.getActiveRoom().kickUser(targetUser, localModerator)) {
                            sendToActive(new JSONObject().put("type", "PLAYER_BANNED").put("identity", identity).toString());
                        }
                    }
                    else if ("REVOKE_BAN".equals(type) && !identity.isEmpty()) {
                        if (localModerator != null
                                && LeagueProximityChat.getActiveRoom().revokeBan(targetUser, localModerator)) {
                            sendToActive(new JSONObject().put("type", "PLAYER_UNBANNED").put("identity", identity).toString());
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.out.println("Websocket Error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("WebSocket server started on port " + getPort());
    }

    public boolean hasActiveConnection() {
        return activeConnection != null && activeConnection.isOpen();
    }

    public void sendToActive(String text) {
        if (hasActiveConnection()) {
            activeConnection.send(text);
        }
    }

    public void broadcastCoordinates(double x, double y, boolean isDead) {
        if (hasActiveConnection()) {
            String payload = String.format(Locale.US, "{\"x\":%f, \"y\":%f, \"isDead\":%b}", x, y, isDead);
            activeConnection.send(payload);
        }
    }
}