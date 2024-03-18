package it.polimi;

import it.polimi.packets.discovery.IPsPacket;
import it.polimi.packets.discovery.Peer2DiscoveryPacket;
import it.polimi.packets.discovery.UpdateIpPacket;
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
        //I send to the DISCOVERY_SERVER my ID and Port
        for (int i = 0; i < RETRIES; i++) {
            try (Socket s = new Socket()) {
                s.connect(address);
                LOGGER.info(STR."[\{id}] Connected to discovery");

                //Send a UpdateIpPacket
                ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());
                ObjectInputStream ois = new ObjectInputStream(s.getInputStream());
                oos.writeObject(new UpdateIpPacket(id, port));
                LOGGER.info(STR."[\{id}] Sent subscribe message to discovery");

                oos.flush();

                //List of <ID_otherPeer,HisSocketAddress> from DISCOVERY_SERVER
                Map<String, SocketAddress> ips = ((IPsPacket) ois.readObject()).ips();
                LOGGER.info(STR."[\{id}] Received peers map \{ips}");

                return ips;
            } catch (IOException e) {
                //Couldn't connect to DS
                if (i == RETRIES - 1)
                    throw e;

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

    public void sendToDiscovery(Peer2DiscoveryPacket packet) throws IOException {
        for (int i = 0; i < RETRIES; i++) {
            try (Socket s = new Socket()) {
                s.connect(address);
                LOGGER.info(STR."[\{id}] Connected to DISCOVERY_SERVER");

                //Send a UpdateIpPacket
                ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());
                oos.writeObject(packet);
                var ois = new ObjectInputStream(s.getInputStream());
                LOGGER.info(STR."[\{id}] Sent to DISCOVERY_SERVER");

                oos.flush();

                //Waiting ACK from DISCOVERY_SERVER
                ois.readObject();
                LOGGER.trace(STR."[\{id}] Received ACK");
                return;
            } catch (IOException e) {
                //Couldn't connect to DS
                if (i == RETRIES - 1)
                    throw e;

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
