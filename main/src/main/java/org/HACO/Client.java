package org.HACO;

import org.HACO.packets.CreateRoomPacket;
import org.HACO.packets.HelloPacket;
import org.HACO.packets.MessagePacket;
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
    private final String id;
    private final int port;
    private Set<ChatRoom> chats;
    private final PropertyChangeSupport propertyChangeSupport;
    private static Scanner input = new Scanner(System.in);

    ExecutorService executorService = Executors.newFixedThreadPool(50);
    private ServerSocket serverSocket;

    private final Map<String, SocketAddress> ips;
    private final Map<String, SocketInfo> sockets;

    private final PropertyChangeListener msgChangeListener;

    private record SocketInfo(Socket s, ObjectOutputStream oos, ObjectInputStream ois) {
    }

    public Client(String id, int port, PropertyChangeListener chatRoomsChangeListener, PropertyChangeListener msgChangeListener) {
        this.id = id;
        this.port = port;
        chats = ConcurrentHashMap.newKeySet();
        sockets = new HashMap<>();
        propertyChangeSupport = new PropertyChangeSupport(chats);
        propertyChangeSupport.addPropertyChangeListener(chatRoomsChangeListener);
        this.msgChangeListener = msgChangeListener;
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
                    HelloPacket helloPacket = (HelloPacket) ois.readObject();
                    sockets.put(helloPacket.id(), new SocketInfo(s, oos, ois));
                    ips.put(helloPacket.id(), s.getRemoteSocketAddress());
                    executorService.execute(new ChatUpdater(s, ois, chats, propertyChangeSupport, msgChangeListener));
                }
            } catch (IOException | ClassNotFoundException e) {
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
                sockets.put(id, new SocketInfo(s, oos, ois));
                HelloPacket helloPacket = new HelloPacket(id);
                oos.writeObject(helloPacket);
                oos.flush();
                executorService.execute(new ChatUpdater(s, ois, chats, propertyChangeSupport, msgChangeListener));
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
        Set<String> ips = new HashSet<>();
        System.out.println("Insert a component's IP(nothing for exit)\n");
        s = input.nextLine();
        while (s != null) {
            ips.add(s);
            System.out.println("Insert a component's IP(nothing for exit)\n");
            s = input.nextLine();
        }
        if (ips.size() == 0)
            throw new NoIpsInsertedException();
        return new ChatRoom("", ips, null);
    }

    private void joinChat() {
    }

    private void disconnect() {
    }

    public void sendMessage(String msg, ChatRoom chat) {
        //TODO: magie con vector clocks
        Message m = new Message(msg, null);
        chat.push(m);

        chat.getUsers().forEach(id -> {
            try {
                ObjectOutputStream oos = sockets.get(id).oos;
                oos.writeObject(new MessagePacket(chat.getId(), this.id, m));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });


    }

    public Map<String, SocketAddress> getIps() {
        return Collections.unmodifiableMap(ips);
    }

    public void createRoom(String name, Set<String> users) {
        var old = Set.copyOf(chats);
        ChatRoom newRoom = new ChatRoom(name, users, msgChangeListener);
        chats.add(newRoom);
        propertyChangeSupport.firePropertyChange("ADD_ROOM", old, Collections.unmodifiableSet(chats));

        users.forEach(id -> {
            try {
                ObjectOutputStream oos = sockets.get(id).oos;
                oos.writeObject(new CreateRoomPacket(newRoom.getId(), name, users));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

}