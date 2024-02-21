package org.HACO.discovery;

import org.HACO.packets.IPsPacket;
import org.HACO.packets.UpdateIpPacket;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;

public class DiscoveryServer {

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

    public void run() {
        while (!serverSocket.isClosed()) {
            try (Socket s = serverSocket.accept()) {
                ObjectInputStream ois = new ObjectInputStream(s.getInputStream());
                ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());
                UpdateIpPacket p = (UpdateIpPacket) ois.readObject();
                ips.put(p.id(), new InetSocketAddress(s.getInetAddress(), p.port()));
                oos.writeObject(new IPsPacket(ips));
                oos.flush();
            } catch (IOException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }


}
