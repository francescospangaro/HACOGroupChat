package it.polimi;

import it.polimi.packets.discovery.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Map;

public class DiscoveryConnector {
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryConnector.class);

    private final SocketAddress address;
    private final String id;
    private final int port;
    private static final int DELAY = 1000, RETRIES = 5;

    public DiscoveryConnector(SocketAddress address, String id, int port) {
        this.address = address;
        this.id = id;
        this.port = port;
    }

    public Map<String, SocketAddress> register() throws IOException {
        var packet = (IPsPacket) sendToDiscovery(new UpdateIpPacket(id, port));
        return packet.ips();
    }

    public void disconnect() throws IOException {
        sendToDiscovery(new ByePacket(id));
    }

    private Discovery2PeerPacket sendToDiscovery(Peer2DiscoveryPacket packet) throws IOException {
        for (int i = 0; i < RETRIES; i++) {
            try (Socket s = new Socket()) {
                s.connect(address);
                LOGGER.info(STR."[\{id}] Connected to discovery server");

                //Send a packet
                ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());
                ObjectInputStream ois = new ObjectInputStream(s.getInputStream());
                oos.writeObject(packet);
                oos.flush();

                LOGGER.info(STR."[\{id}] Sent \{packet} to discovery server");

                //Await response
                var res = (Discovery2PeerPacket) ois.readObject();
                LOGGER.info(STR."[\{id}] Received response from discovery \{res}");
                return res;
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
            } catch (ClassNotFoundException | ClassCastException ex) {
                throw new IOException(STR."[\{id}] Received unexpected packet", ex);
            }
        }
        throw new IllegalStateException("Unreachable");
    }
}
