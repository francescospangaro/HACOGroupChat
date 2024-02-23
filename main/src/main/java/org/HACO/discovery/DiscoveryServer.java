package org.HACO.discovery;

import org.HACO.packets.discovery.ByePacket;
import org.HACO.packets.discovery.IPsPacket;
import org.HACO.packets.discovery.Peer2DiscoveryPacket;
import org.HACO.packets.discovery.UpdateIpPacket;

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

                Peer2DiscoveryPacket p = (Peer2DiscoveryPacket) ois.readObject();
                System.out.println("Received " + p);

                switch (p) {
                    case UpdateIpPacket ipPacket -> {
                        oos.writeObject(new IPsPacket(ips
//                                .entrySet().stream()
//                                .filter(ip -> !ip.getKey().equals(ipPacket.id()))
//                                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue))
                        ));
                        oos.flush();
                        System.out.println("sent");
                        ips.put(ipPacket.id(), new InetSocketAddress(s.getInetAddress(), ipPacket.port()));
                    }
                    case ByePacket byePacket -> {
                        ips.remove(byePacket.id());
                        System.out.println("Client disconnected id: "+byePacket.id());
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }


}
