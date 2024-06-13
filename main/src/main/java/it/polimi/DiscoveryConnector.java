package it.polimi;

import it.polimi.packets.ByePacket;
import it.polimi.packets.discovery.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketAddress;
import java.util.Map;

public class DiscoveryConnector {
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryConnector.class);

    private final SocketManager socketManager;
    private final String id;
    private final int port;
    private static final int DELAY = 1000, RETRIES = 5;

    public DiscoveryConnector(SocketManager socketManager, String id, int port) {
        this.socketManager = socketManager;
        this.id = id;
        this.port = port;
    }

    public Map<String, SocketAddress> register() throws IOException {
        sendToDiscovery(new UpdateIpPacket(id, port));
        return ((IPsPacket) socketManager.receiveFromDiscovery()).ips();
    }

    public void disconnect() throws IOException {
        sendToDiscovery(new ByePacket(id));
    }

    private void sendToDiscovery(Peer2DiscoveryPacket packet) throws IOException {
        for (int i = 0; i < RETRIES; i++) {
            try {
                socketManager.sendToDiscovery(packet);
            } catch (IOException e) {
                //Couldn't connect to DS
                if (i == RETRIES - 1) {
                    LOGGER.error(STR."Failed contacting the discovery for \{RETRIES} time. Aborting...");
                    throw e;
                }

                LOGGER.warn(STR."Failed contacting the discovery. Retrying in \{DELAY} seconds");
                try {
                    Thread.sleep(DELAY);
                } catch (InterruptedException ex) {
                    throw new InterruptedIOException("Interrupted while contacting the discovery");
                }
            }
        }
        throw new IllegalStateException("Unreachable");
    }
}
