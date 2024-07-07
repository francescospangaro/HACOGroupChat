package it.polimi.discovery;

import it.polimi.packets.ByePacket;
import it.polimi.packets.discovery.ForwardPacket;
import it.polimi.packets.discovery.IPsPacket;
import it.polimi.packets.discovery.PacketQueue;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final Set<ForwardPacket> waitingConnection = new HashSet<>();

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
        while (!socketManager.isClosed()) {
            try {
                var p = socketManager.receive();
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

                        // Check if a peer reconnects, then send him all waiting messages
                        Set<ForwardPacket> toForward = waitingConnection
                                .stream()
                                .filter(fp -> fp.recipientId().equals(ipPacket.id()))
                                .collect(Collectors.toSet());
                        if (!toForward.isEmpty()) {
                            for (ForwardPacket packet : toForward) {
                                socketManager.send(new PacketQueue(packet.senderId(), addr, packet.packets()), addr);
                            }
                            waitingConnection.removeAll(toForward);
                        }
                    }
                    case ByePacket byePacket -> {
                        ips.remove(byePacket.id());
                        LOGGER.info(STR."[discovery] Client disconnected id: \{byePacket.id()}");
                    }
                    case ForwardPacket forwardPacket -> {
                        SocketAddress addr = ips.get(forwardPacket.recipientId());
                        if (addr != null)
                            socketManager.send(new PacketQueue(forwardPacket.recipientId(), addr, forwardPacket.packets()), addr);
                        else {
                            // If the peer is unreachable, save all packets in a set (so we don't have dupes)
                            LOGGER.warn(STR."[discovery] Can't forward packet: peer unknown or disconnected \{forwardPacket.recipientId()}");
                            waitingConnection.add(forwardPacket);
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.error("[discovery] during communication", e);
            }
        }
    }

    @Override
    public void close() {
        executorService.shutdownNow();
        socketManager.close();
        LOGGER.info("Closed discovery");
    }

}
