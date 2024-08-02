package it.polimi.discovery;

import it.polimi.packets.ByePacket;
import it.polimi.packets.discovery.ForwardPacket;
import it.polimi.packets.discovery.ForwardedPacket;
import it.polimi.packets.discovery.IPsPacket;
import it.polimi.packets.discovery.UpdateIpPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Discovery server class: Serves the purpose of being a first point of connection
 * when a new peer connects to the app.
 * When connected, the server will send this peer a list of all peers connected, so that
 * the new peer can establish connections with everyone else in the network.
 */
public class DiscoveryServer implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryServer.class);

    private final Map<String, SocketAddress> ips;
    private final DiscoverySocketManager socketManager;
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    private final Set<ForwardPacket> waitingConnection = new HashSet<>();
    private final Set<ForwardPacket> toRetry = ConcurrentHashMap.newKeySet();

    public DiscoveryServer() {
        ips = new HashMap<>();
        try {
            socketManager = new DiscoverySocketManager("discovery", executorService, 8080);
        } catch (IOException e) {
            LOGGER.error("Error opening the socket", e);
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Method that accepts connections and parses each received message.
     * Once connection with a new peer is established, this method becomes a parser
     * for the packets the server receives from the peers.
     * 1. UpdateIdPacket - Sends the peer that changed his IP address the list of all connected peers,
     * then saves the changes to the ips list.
     * 2. ByePacket - Removes the IP of the disconnected peer from his connected peers list
     * 3. ForwardPacket - Sends the packets that need forwarding to the peers the packets need to be forwarded to
     */
    public void start() {
        LOGGER.info("Running discovery server...");
        startRetryTask();
        while (!socketManager.isClosed()) {
            try {
                var p = socketManager.receive();
                LOGGER.trace(STR."[discovery] Received \{p}");
                switch (p.packet()) {
                    case UpdateIpPacket ipPacket -> {
                        LOGGER.info(STR."[discovery] Sending info of all peers to \{ipPacket.id()}");
                        SocketAddress addr = p.sender();
                        socketManager.send(new IPsPacket(ips
//                                .entrySet().stream()
//                                .filter(ip -> !ip.getKey().equals(ipPacket.recipientId()))
//                                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue))
                        ), addr);
                        ips.put(ipPacket.id(), addr);

                        //Removes ByePacket from list sent by the user that has just reconnected
                        waitingConnection.stream()
                                .filter(fp -> fp.senderId().equals(ipPacket.id()))
                                .forEach(queue -> queue.packets().removeIf(p2p -> p2p instanceof ByePacket));
                        waitingConnection.removeIf(fp -> fp.packets().isEmpty());

                        toRetry.stream()
                                .filter(r -> r.senderId().equals(ipPacket.id()))
                                .forEach(queue -> queue.packets().removeIf(p2p -> p2p instanceof ByePacket));
                        toRetry.removeIf(r -> r.packets().isEmpty());

                        // Check if a peer reconnects, then send him all waiting messages
                        Set<ForwardPacket> toForward = waitingConnection
                                .stream()
                                .filter(fp -> fp.recipientId().equals(ipPacket.id()))
                                .collect(Collectors.toSet());
                        if (!toForward.isEmpty()) {
                            for (ForwardPacket packet : toForward) {
                                try {
                                    socketManager.send(new ForwardedPacket(packet.senderId(), ips.get(packet.senderId()), packet.packets()), addr);
                                } catch (IOException e) {
                                    LOGGER.warn(STR."[discovery] Can't forward packet: peer unreachble \{packet.recipientId()}");
                                    toRetry.add(packet);
                                }
                            }
                            waitingConnection.removeAll(toForward);
                        }
                    }
                    case ByePacket byePacket -> {
                        ips.remove(byePacket.id());
                        LOGGER.info(STR."[discovery] Client disconnected id: \{byePacket.id()}");
                        Set<ForwardPacket> toForward = toRetry
                                .stream()
                                .filter(fp -> fp.recipientId().equals(byePacket.id()))
                                .collect(Collectors.toSet());
                        waitingConnection.addAll(toForward);
                        toRetry.removeAll(toForward);
                    }
                    case ForwardPacket forwardPacket -> {
                        SocketAddress addr = ips.get(forwardPacket.recipientId());
                        if (addr != null)
                            try {
                                socketManager.send(new ForwardedPacket(forwardPacket.recipientId(), p.sender(), forwardPacket.packets()), addr);
                            } catch (IOException e) {
                                LOGGER.warn(STR."[discovery] Can't forward packet: peer unreachble \{forwardPacket.recipientId()}");
                                toRetry.add(forwardPacket);
                            }
                        else {
                            // If the peer is unreachable, save all packets in a set (so we don't have dupes)
                            LOGGER.warn(STR."[discovery] Can't forward packet: peer unknown or disconnected \{forwardPacket.recipientId()}");
                            waitingConnection.add(forwardPacket);
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.error("[discovery] Error during communication", e);
            } catch (Throwable t) {
                LOGGER.error("[discovery] Unexpected exception", t);
                throw t;
            }
        }
    }

    private void startRetryTask() {
        //Every 5 seconds retry, until I'm connected with everyone
        scheduledExecutorService.scheduleAtFixedRate(() -> toRetry.forEach(packet -> {
            try {
                socketManager.send(new ForwardedPacket(packet.senderId(), ips.get(packet.senderId()), packet.packets()), ips.get(packet.recipientId()));
                toRetry.remove(packet);
            } catch (IOException e) {
                LOGGER.warn(STR."[discovery] Failed to resend \{packet}", e);
            }
        }), 10, 10, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        scheduledExecutorService.shutdownNow();
        executorService.shutdownNow();
        socketManager.close();
        LOGGER.info("Closed discovery");
    }

}
