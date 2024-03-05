package org.HACO;

import org.HACO.packets.discovery.IPsPacket;
import org.HACO.packets.discovery.Peer2DiscoveryPacket;
import org.HACO.packets.discovery.UpdateIpPacket;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Map;

public class DiscoveryConnector {
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
                System.out.println("[" + id + "] Connected");

                //Send a UpdateIpPacket
                ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());
                ObjectInputStream ois = new ObjectInputStream(s.getInputStream());
                oos.writeObject(new UpdateIpPacket(id, port));
                System.out.println("[" + id + "] Sent subscribe message to discovery");

                oos.flush();

                //Waiting list of <ID_otherPeer,HisSocketAddress> from DISCOVERY_SERVER
                Map<String, SocketAddress> ips = ((IPsPacket) ois.readObject()).ips();
                System.out.println("[" + id + "] Received peers map " + ips);

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
                throw new IOException("[" + id + "] Received unexpected packet" + ex);
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    public void sendToDiscovery(Peer2DiscoveryPacket packet) throws IOException {
        //I send to the DISCOVERY_SERVER my ID and Port
        for (int i = 0; i < RETRIES; i++) {
            try (Socket s = new Socket()) {
                s.connect(address);
                System.out.println("[" + id + "] Connected to DISCOVERY_SERVER");

                //Send a UpdateIpPacket
                ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());
                oos.writeObject(packet);
                var ois = new ObjectInputStream(s.getInputStream());
                System.out.println("[" + id + "] Sent to DISCOVERY_SERVER");

                oos.flush();

                //Waiting ACK from DISCOVERY_SERVER
                ois.readObject();
                System.out.println("[" + id + "] Received ACK");
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
                throw new IOException("[" + id + "] Received unexpected packet" + ex);
            }
        }
        throw new IllegalStateException("Unreachable");
    }
}
