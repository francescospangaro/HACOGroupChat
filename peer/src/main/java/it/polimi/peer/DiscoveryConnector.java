package it.polimi.peer;

import it.polimi.packets.ByePacket;
import it.polimi.packets.discovery.*;
import it.polimi.packets.p2p.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class DiscoveryConnector implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryConnector.class);

    private static final int UUID_SIZE = 16;

    private static final int MAX_PACKET_SIZE = 40000;

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
        if (evaluateSize(queue) > MAX_PACKET_SIZE) {
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

    // Gets size of the whole packet queue, only check string items
    private int evaluateSize(Queue<P2PPacket> queue) {
        int size = 0;
        for (P2PPacket p : queue) {
            switch (p) {
                case ByePacket bp -> size += bp.id().length();
                case CreateRoomPacket crp ->
                        size += crp.name().length() + crp.ids().stream().reduce(0, (s, t) -> s + t.length(), Integer::sum) + UUID_SIZE;
                case DelayedMessagePacket dmp ->
                        size += getVCSize(dmp.msg().vectorClocks()) + dmp.msg().sender().length() + dmp.msg().msg().length() + UUID_SIZE;
                case CloseRoomPacket clrp ->
                        size += clrp.closeMessage().sender().length() + getVCSize(clrp.closeMessage().vectorClocks()) + UUID_SIZE;
                case HelloPacket hp -> size += hp.id().length();
                case MessagePacket mp ->
                        size += getVCSize(mp.msg().vectorClocks()) + mp.msg().sender().length() + mp.msg().msg().length() + UUID_SIZE;
            }
        }
        return size;
    }

    private int getVCSize(Map<String, Integer> vc) {
        return vc.keySet().stream().reduce(0, (s, t) -> s + t.length(), Integer::sum);
    }

    // Approximate 20 packets per queue
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
