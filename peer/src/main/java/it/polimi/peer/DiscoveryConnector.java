package it.polimi.peer;

import it.polimi.packets.ByePacket;
import it.polimi.packets.discovery.ForwardPacket;
import it.polimi.packets.discovery.IPsPacket;
import it.polimi.packets.discovery.Peer2DiscoveryPacket;
import it.polimi.packets.discovery.UpdateIpPacket;
import it.polimi.packets.p2p.P2PPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Queue;

public class DiscoveryConnector {
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryConnector.class);

    private final PeerSocketManager socketManager;
    private final String id;
    private static final int DELAY = 1000, RETRIES = 5;

    public DiscoveryConnector(PeerSocketManager socketManager, String id) {
        this.socketManager = socketManager;
        this.id = id;
    }

    public Map<String, SocketAddress> register() throws IOException {
        sendToDiscovery(new UpdateIpPacket(id));
        return ((IPsPacket) socketManager.receiveFromDiscovery()).ips();
    }

    public void disconnect() throws IOException {
        sendToDiscovery(new ByePacket(id));
    }

    public void forwardQueue(String id, Queue<P2PPacket> queue) throws IOException {
        sendToDiscovery(new ForwardPacket(queue, id));
    }

    private void sendToDiscovery(Peer2DiscoveryPacket packet) throws IOException {
        for (int i = 0; i < RETRIES; i++) {
            try {
                socketManager.sendToDiscovery(packet);
                return;
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
    }
}
