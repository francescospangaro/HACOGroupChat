package it.polimi.peer;

import it.polimi.packets.ByePacket;
import it.polimi.packets.discovery.*;
import it.polimi.packets.p2p.HelloPacket;
import it.polimi.packets.p2p.P2PPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class DiscoveryConnector implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryConnector.class);

    private final CompletableFuture<IPsPacket> ipsPromise;

    private final PeerSocketManager socketManager;
    private final String id;
    private final ChatUpdater updater;
    private static final int DELAY = 1000, RETRIES = 5;

    public DiscoveryConnector(PeerSocketManager socketManager, String id, ChatUpdater updater) {
        this.socketManager = socketManager;
        this.id = id;
        this.updater = updater;
        this.ipsPromise = new CompletableFuture<>();
    }

    public Map<String, SocketAddress> register() throws IOException {
        sendToDiscovery(new UpdateIpPacket(id));
        try {
            return ipsPromise.get().ips();
        } catch (InterruptedException e) {
            throw (IOException) new InterruptedIOException().initCause(e);
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        }
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

                LOGGER.warn(STR."Failed contacting the discovery. Retrying in \{DELAY / 1000} seconds");
                try {
                    Thread.sleep(DELAY);
                } catch (InterruptedException ex) {
                    throw new InterruptedIOException("Interrupted while contacting the discovery");
                }
            }
        }
    }

    @Override
    public void run() {
        try {
            do {
                Discovery2PeerPacket p = socketManager.receiveFromDiscovery();
                switch (p) {
                    case IPsPacket ips -> ipsPromise.complete(ips);
                    case PacketQueue packetQueue -> {
                        updater.handlePacket(new HelloPacket(packetQueue.id()), packetQueue.addr());
                        packetQueue.packets().forEach(p2p -> updater.handlePacket(p2p, packetQueue.addr()));
                    }
                }

            } while (!Thread.currentThread().isInterrupted());
        } catch (IOException e) {
            LOGGER.error("Failed to receive from discovery", e);
        }
    }
}
