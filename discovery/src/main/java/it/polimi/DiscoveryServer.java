package it.polimi;

import it.polimi.packets.ByePacket;
import it.polimi.packets.discovery.IPsPacket;
import it.polimi.packets.discovery.Peer2DiscoveryPacket;
import it.polimi.packets.discovery.UpdateIpPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

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
     */
    public void start() {
        LOGGER.info("Running discovery server...");
        while (!socketManager.isClosed()) {
            try {
                DiscoverySocketManager.PacketAndSender p = socketManager.receive();
                switch (p.p()) {
                    case UpdateIpPacket ipPacket -> {
                        LOGGER.info(STR."[discovery] Sending info of all peers to \{ipPacket.id()}");
                        socketManager.send(new IPsPacket(ips
//                                .entrySet().stream()
//                                .filter(ip -> !ip.getKey().equals(ipPacket.id()))
//                                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue))
                        ), p.sender());
                        ips.put(ipPacket.id(), p.sender());
                    }
                    case ByePacket byePacket -> {
                        ips.remove(byePacket.id());
                        LOGGER.info(STR."[discovery] Client disconnected id: \{byePacket.id()}");
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
