package it.polimi.discovery;

import it.polimi.packets.discovery.ByePacket;
import it.polimi.packets.discovery.IPsPacket;
import it.polimi.packets.discovery.Peer2DiscoveryPacket;
import it.polimi.packets.discovery.UpdateIpPacket;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;

public class DiscoveryServer implements Runnable {

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

    @Override
    public void run() {
        while (!serverSocket.isClosed()) {
            System.out.println("Waiting connection...");
            try (Socket s = serverSocket.accept()) {
                System.out.println("A Peer connected");

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
                        System.out.println("Send info of all peers");
                        ips.put(ipPacket.id(), new InetSocketAddress(s.getInetAddress(), ipPacket.port()));
                    }
                    case ByePacket byePacket -> {
                        ips.remove(byePacket.id());
                        System.out.println("Client disconnected id: " + byePacket.id());

                        //Let the peer know that I received his request avoiding that he closes the connection
                        // while I have not read all the bytes
                        oos.writeObject(new IPsPacket(null));
                        oos.flush();
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void close() throws IOException {
        serverSocket.close();
        System.out.println("closed discovery");
    }


}
