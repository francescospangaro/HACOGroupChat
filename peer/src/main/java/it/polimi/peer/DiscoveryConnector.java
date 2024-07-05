package it.polimi.peer;

import it.polimi.packets.ByePacket;
import it.polimi.packets.discovery.*;
import it.polimi.packets.p2p.*;
import it.polimi.peer.utility.ByteSizeGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

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

    // If the packets are too big, divide the queue in different smaller ones and send them separated
    public void forwardQueue(String id, Queue<P2PPacket> queue) throws IOException {
        if (evaluateSize(queue) > 60000) {
            List<Queue<P2PPacket>> queues = dividePackets(queue);
            queues.forEach(q -> {
                try {
                    sendToDiscovery(new ForwardPacket(q, id));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } else {
            sendToDiscovery(new ForwardPacket(queue, id));
        }
    }

    // Gets byte size of the whole packet queue
    private int evaluateSize(Queue<P2PPacket> queue) {
        AtomicInteger size = new AtomicInteger();
        queue.forEach(q -> size.addAndGet(ByteSizeGetter.getByteSize(q)));
        return size.get();
    }

    // Approximate 20 packets per queue (Suppose we have packets of approx 500 bytes each)
    private List<Queue<P2PPacket>> dividePackets(Queue<P2PPacket> queues) {
        List<Queue<P2PPacket>> queueList = new ArrayList<>();
        Queue<P2PPacket> tempQueue = new LinkedList<>();
        for (int i = 0; i < queues.size(); i += 20) {
            for (int j = 0; j < 20; j++) {
                tempQueue.add(queues.remove());
            }
            queueList.add(tempQueue);
        }
        return queueList;
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
        } catch (InterruptedIOException ex) {
            LOGGER.info("Discovery connector stopped");
        } catch (IOException e) {
            LOGGER.error("Failed to receive from discovery", e);
        }
    }
}
