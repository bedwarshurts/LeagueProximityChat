package me.bedwarshurts.leagueproximitychat.websocket;

import lombok.Getter;
import lombok.Setter;
import me.bedwarshurts.leagueproximitychat.SessionState;
import me.bedwarshurts.leagueproximitychat.livekit.LiveKitUser;
import me.bedwarshurts.leagueproximitychat.livekit.LivekitRoom;
import me.bedwarshurts.leagueproximitychat.managers.DebugManager;
import me.bedwarshurts.leagueproximitychat.position.ScreenPositionTracker;
import me.bedwarshurts.leagueproximitychat.utils.LeagueConfigReader;
import me.bedwarshurts.leagueproximitychat.utils.RitoApiUtils;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;

public class CoordinateServer extends WebSocketServer {

    private final SessionState session;
    private volatile WebSocket activeConnection = null;
    @Getter @Setter private volatile boolean userRequestedConnection = false;

    public CoordinateServer(InetSocketAddress address, SessionState session) {
        super(address);
        this.session = session;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        if (activeConnection != null && activeConnection.isOpen()) {
            activeConnection.send("{\"type\":\"REPLACED\"}");
            activeConnection.close(1000, "Replaced by a newer tab.");
        }
        activeConnection = conn;
        sendConfigWarning();
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        if (activeConnection == conn) {
            activeConnection = null;
            userRequestedConnection = false;
            session.setActiveRoom(null);

            System.out.println("Active browser tab closed.");
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if ("REQUEST_JOIN".equals(message)) {
            userRequestedConnection = true;
            return;
        }
        if ("CANCEL_JOIN".equals(message)) {
            userRequestedConnection = false;
            session.setConnectedToLiveKit(false);
            session.setActiveRoom(null);
            return;
        }
        if (!message.startsWith("{")) return;

        try {
            handleJsonMessage(new JSONObject(message));
        } catch (Exception e) {
            DebugManager.logFailure("[WebSocket] Could not handle a message from the UI", e);
        }
    }

    private void handleJsonMessage(JSONObject json) {
        String type = json.optString("type");

        if ("CLIENT_LOG".equals(type)) {
            System.out.println("[Client] " + json.optString("msg", ""));
            return;
        }
        if ("WARNING_ACK".equals(type)) {
            session.acknowledgeWarning(json.optString("id"));
            return;
        }

        LivekitRoom room = session.getActiveRoom();
        String identity = json.optString("identity");
        if (room == null || identity.isEmpty()) return;

        LiveKitUser targetUser = new LiveKitUser(identity, json.optString("name", "Unknown"));
        switch (type) {
            case "PLAYER_JOINED" -> {
                if (room.isBanned(targetUser)) {
                    System.out.println("Banned user " + identity + " tried to rejoin. Auto-kicking...");
                    kick(room, targetUser);
                } else {
                    room.addParticipant(targetUser);
                }
            }
            case "PLAYER_LEFT" -> room.removeParticipant(targetUser);
            case "KICK_USER" -> kick(room, targetUser);
            case "REVOKE_BAN" -> {
                LiveKitUser moderator = localModerator();
                if (moderator != null && room.revokeBan(targetUser, moderator)) {
                    sendToActive(new JSONObject().put("type", "PLAYER_UNBANNED").put("identity", identity).toString());
                }
            }
            default -> {
            }
        }
    }

    private void kick(LivekitRoom room, LiveKitUser targetUser) {
        LiveKitUser moderator = localModerator();
        if (moderator != null && room.kickUser(targetUser, moderator)) {
            sendToActive(new JSONObject().put("type", "PLAYER_BANNED").put("identity", targetUser.identity()).toString());
        }
    }

    private static LiveKitUser localModerator() {
        String localIdentity = RitoApiUtils.getLocalSummonerName();
        return (localIdentity != null && !localIdentity.isEmpty()) ? new LiveKitUser(localIdentity, "") : null;
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

    public void sendConfigWarning() {
        LeagueConfigReader.Warning warning = session.getConfigWarning();
        if (warning == null) return;
        sendToActive(new JSONObject()
                .put("type", "WARNING")
                .put("id", SessionState.CONFIG_WARNING_ID)
                .put("title", "League Settings")
                .put("message", warning.message())
                .put("items", new JSONArray(warning.settingsToChange()))
                .put("footer", warning.footer() == null ? JSONObject.NULL : warning.footer())
                .toString());
    }

    public void broadcastCoordinates(float x, float y, boolean isDead, boolean detected, ScreenPositionTracker.DeadView deadView) {
        if (!hasActiveConnection()) return;

        if (!Float.isFinite(x) || !Float.isFinite(y)) return;

        JSONObject payload = new JSONObject()
                .put("x", x)
                .put("y", y)
                .put("isDead", isDead)
                .put("detected", detected);
        if (deadView != null) {
            if (!Float.isFinite(deadView.listenX()) || !Float.isFinite(deadView.listenY())) return;
            JSONArray enemies = new JSONArray();
            for (float[] enemy : deadView.visibleEnemies()) {
                if (!Float.isFinite(enemy[0]) || !Float.isFinite(enemy[1])) return;
                enemies.put(new JSONArray().put(enemy[0]).put(enemy[1]));
            }
            payload.put("listenX", deadView.listenX())
                    .put("listenY", deadView.listenY())
                    .put("visibleEnemies", enemies);
        }
        sendToActive(payload.toString());
    }
}
