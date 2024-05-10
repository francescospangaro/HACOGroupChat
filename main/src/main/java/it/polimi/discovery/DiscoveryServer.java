package it.polimi.discovery;

import it.polimi.packets.discovery.ByePacket;
import it.polimi.packets.discovery.IPsPacket;
import it.polimi.packets.discovery.Peer2DiscoveryPacket;
import it.polimi.packets.discovery.UpdateIpPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * Discovery server class: Serves the purpose of being a first point of connection
 * when a new peer connects to the app.
 * When connected, the server will send this peer a list of all peers connected, so that
 * the new peer can establish connections with everyone else in the network.
 *
 */
public class DiscoveryServer implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryServer.class);

    private final Map<String, SocketAddress> ips;
    private final ServerSocket serverSocket;

    public DiscoveryServer() {
        ips = new HashMap<>();
        try {
            serverSocket = new ServerSocket(8080);
        } catch (IOException e) {
            throw new RuntimeException(e);
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
    @Override
    public void run() {
        LOGGER.info("Running discovery server...");
        while (!serverSocket.isClosed()) {
            LOGGER.info("Waiting connection...");
            try (Socket s = serverSocket.accept()) {
                LOGGER.info("A Peer connected");

                ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());
                ObjectInputStream ois = new ObjectInputStream(s.getInputStream());

                Peer2DiscoveryPacket p = (Peer2DiscoveryPacket) ois.readObject();
                LOGGER.info(STR."Received \{p}");

                switch (p) {
                    case UpdateIpPacket ipPacket -> {
                        oos.writeObject(new IPsPacket(ips
//                                .entrySet().stream()
//                                .filter(ip -> !ip.getKey().equals(ipPacket.id()))
//                                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue))
                        ));
                        oos.flush();
                        LOGGER.info("Sending info of all peers");
                        ips.put(ipPacket.id(), new InetSocketAddress(s.getInetAddress(), ipPacket.port()));
                    }
                    case ByePacket byePacket -> {
                        ips.remove(byePacket.id());
                        LOGGER.info(STR."Client disconnected id: \{byePacket.id()}");
                        oos.writeObject(new IPsPacket(null));
                        oos.flush();
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                LOGGER.error("Error during communication", e);
            }
        }
    }

    /**
     * Closes the connection
     * @throws IOException on IOException
     */
    public void close() throws IOException {
        serverSocket.close();
        LOGGER.info("Closed discovery");
    }


}
