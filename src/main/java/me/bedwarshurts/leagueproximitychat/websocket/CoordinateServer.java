package me.bedwarshurts.leagueproximitychat.websocket;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import java.net.InetSocketAddress;
import java.util.Locale;

public class CoordinateServer extends WebSocketServer {

    public CoordinateServer(InetSocketAddress address) {
        super(address);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("Web client connected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("Web client disconnected.");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // we dont read incoming msgs
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
    }

    public void broadcastCoordinates(float x, float y, boolean isDead) {
        String jsonPayload = String.format(Locale.US, "{\"x\": %.3f, \"y\": %.3f, \"isDead\": %b}", x, y, isDead);
        broadcast(jsonPayload);
    }
}