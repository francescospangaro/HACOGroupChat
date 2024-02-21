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
            System.out.println("Waiting connection...");
            try (Socket s = serverSocket.accept()) {
                System.out.println("connected");
                ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());
                ObjectInputStream ois = new ObjectInputStream(s.getInputStream());
                UpdateIpPacket p = (UpdateIpPacket) ois.readObject();
                System.out.println("Received " + p);
                oos.writeObject(new IPsPacket(ips));
                System.out.println("sent");
                oos.flush();
                ips.put(p.id(), new InetSocketAddress(s.getInetAddress(), p.port()));
            } catch (IOException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }


}
