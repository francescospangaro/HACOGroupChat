package org.HACO;

import org.HACO.packets.discovery.IPsPacket;
import org.HACO.packets.discovery.UpdateIpPacket;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Client {
    private static final InetSocketAddress DISCOVERY_SERVER = new InetSocketAddress("localhost", 8080);
    private static final String id = "";
    private final int port;
    private Set<ChatRoom> chats;
    private final PropertyChangeSupport propertyChangeSupport;
    private static Scanner input = new Scanner(System.in);

    ExecutorService executorService = Executors.newFixedThreadPool(50);
    private ServerSocket serverSocket;

    private final Map<String, SocketAddress> ips;

    public Client(int port, PropertyChangeListener chatRoomsChangeListener) {
        this.port = port;
        chats = ConcurrentHashMap.newKeySet();
        propertyChangeSupport = new PropertyChangeSupport(chats);
        propertyChangeSupport.addPropertyChangeListener(chatRoomsChangeListener);
        ips = register();
        connect();
        executorService.execute(() -> {
            try {
                serverSocket = new ServerSocket(port);

                while (!serverSocket.isClosed()) {
                    Socket s = serverSocket.accept();
                    System.out.println(s.getRemoteSocketAddress() + " is connected");
                    var oos = new ObjectOutputStream(s.getOutputStream());
                    var ois = new ObjectInputStream(s.getInputStream());
                    executorService.execute(new ChatUpdater(s, ois, chats));
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

    }

    private Map<String, SocketAddress> register() {
        try (Socket s = new Socket()) {
            s.connect(DISCOVERY_SERVER);
            System.out.println("Connected");
            ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());
            ObjectInputStream ois = new ObjectInputStream(s.getInputStream());
            oos.writeObject(new UpdateIpPacket(id, port));
            System.out.println("Sent");
            oos.flush();
            Map<String, SocketAddress> ips = ((IPsPacket) ois.readObject()).ips();
            System.out.println("Received map " + ips);
            return ips;
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public void connect() {
        ips.forEach((id, addr) -> {
            try {
                Socket s = new Socket();
                System.out.println("connecting to " + addr);
                s.connect(addr);
                System.out.println("connected");
                var oos = new ObjectOutputStream(s.getOutputStream());
                var ois = new ObjectInputStream(s.getInputStream());
                executorService.execute(new ChatUpdater(s, ois, chats));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void startingMenu() {
        int choice;

        System.out.println("""
                Choose one:\t
                1. Create a new chatroom;\t
                2. Join a chatroom with a specific id.\t
                """);
        choice = input.nextInt();
        switch (choice) {
            case 1:
                try {
                    chats.add(createChatRoom());
                } catch (NoIpsInsertedException e) {
                    throw new RuntimeException(e);
                }
                break;
            case 2:
                joinChat();
                break;
            default:
                System.out.println("Closing the application.\n");
                disconnect();
                break;
        }
    }

    private ChatRoom createChatRoom() throws NoIpsInsertedException {
        String s;
        List<String> ips = new ArrayList<>();
        System.out.println("Insert a component's IP(nothing for exit)\n");
        s = input.nextLine();
        while (s != null) {
            ips.add(s);
            System.out.println("Insert a component's IP(nothing for exit)\n");
            s = input.nextLine();
        }
        if (ips.size() == 0)
            throw new NoIpsInsertedException();
        return new ChatRoom(ips);
    }

    private void joinChat() {
    }

    private void disconnect() {
    }

    private void sendMessage() {
        String msg = input.nextLine();
        //

    }
}