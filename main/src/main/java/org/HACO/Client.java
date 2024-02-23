package org.HACO;

import org.HACO.packets.*;
import org.HACO.packets.discovery.ByePacket;
import org.HACO.packets.discovery.IPsPacket;
import org.HACO.packets.discovery.Peer2DiscoveryPacket;
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

    ExecutorService executorService = Executors.newFixedThreadPool(50);
    private ServerSocket serverSocket;

    private final Map<String, SocketAddress> ips;
    private final Map<String, SocketInfo> sockets;

    private final PropertyChangeListener msgChangeListener;

    private boolean connected;

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
                //Waiting for incoming packets by creating a serverSocket
                serverSocket = new ServerSocket(port);

                while (!serverSocket.isClosed()) {
                    Socket justConnectedClient = serverSocket.accept();
                    //Someone has just connected to me
                    System.out.println(justConnectedClient.getRemoteSocketAddress() + " is connected");

                    var oos = new ObjectOutputStream(justConnectedClient.getOutputStream());
                    var ois = new ObjectInputStream(justConnectedClient.getInputStream());

                    //I will receive a helloPacket from him containing his ID
                    HelloPacket helloPacket = (HelloPacket) ois.readObject();

                    //Update the list of sockets of the other peers
                    sockets.put(helloPacket.id(), new SocketInfo(justConnectedClient, oos, ois));

                    //Update the list of Addresses of the other peers
                    ips.put(helloPacket.id(), justConnectedClient.getRemoteSocketAddress());

                    executorService.execute(new ChatUpdater(justConnectedClient, ois, chats, propertyChangeSupport, msgChangeListener));
                }
            } catch (IOException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        });

    }

    private Map<String, SocketAddress> register() {
        //I send to the DISCOVERY_SERVER my ID and Port
        try (Socket s = new Socket()) {
            s.connect(DISCOVERY_SERVER);
            System.out.println("Connected");

            //Send a UpdateIpPacket
            ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());
            ObjectInputStream ois = new ObjectInputStream(s.getInputStream());
            oos.writeObject(new UpdateIpPacket(id, port));
            System.out.println("Sent");

            oos.flush();

            //Waiting list of <ID_otherPeer,HisSocketAddress> from DISCOVERY_SERVER
            Map<String, SocketAddress> ips = ((IPsPacket) ois.readObject()).ips();
            System.out.println("Received map " + ips);

            return ips;
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public void connect() {
        //For each peer in the network I try to connect to him by sending a helloPacket
        ips.forEach((id, addr) -> {
            try {
                Socket s = new Socket();
                System.out.println("connecting to " + addr);
                s.connect(addr);
                System.out.println("connected");

                var oos = new ObjectOutputStream(s.getOutputStream());
                var ois = new ObjectInputStream(s.getInputStream());
                sockets.put(id, new SocketInfo(s, oos, ois));

                //Send a helloPacket
                HelloPacket helloPacket = new HelloPacket(this.id);
                oos.writeObject(helloPacket);

                oos.flush();
                executorService.execute(new ChatUpdater(s, ois, chats, propertyChangeSupport, msgChangeListener));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void sendMessage(String msg, ChatRoom chat) {
        List<Integer> vc = new ArrayList<>();
        for (String s : chat.getUsers()) {
            if (s.equals(this.id))
                vc.add(chat.getVectorClocks().get(s) + 1);
            else
                vc.add(chat.getVectorClocks().get(s));
        }

        Message m = new Message(msg, vc, this.id);
        chat.push(m);

        //Send a MessagePacket containing the Message just created to each User of the ChatRoom
        chat.getUsers().forEach(id -> {
            if (!id.equals(this.id)) {
                try {
                    ObjectOutputStream oos = sockets.get(id).oos;
                    oos.writeObject(new MessagePacket(chat.getId(), this.id, m));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });


    }

    public Map<String, SocketAddress> getIps() {
        return Collections.unmodifiableMap(ips);
    }

    public void createRoom(String name, Set<String> users) {
        //Add the ChatRoom to the list of available ChatRooms
        ChatRoom newRoom = new ChatRoom(name, users, msgChangeListener);
        chats.add(newRoom);

        //Fires ADD_ROOM Event in order to update the GUI
        propertyChangeSupport.firePropertyChange("ADD_ROOM", null, newRoom);

        //Inform all the users about the creation of the new chat room by sending to them a CreateRoomPacket
        sendMsg(new CreateRoomPacket(newRoom.getId(), name, users), users);
    }

    public void deleteRoom(ChatRoom toDelete) {
        propertyChangeSupport.firePropertyChange("DEL_ROOM", null, toDelete);
        chats.remove(toDelete);

        sendMsg(new DeleteRoomPacket(toDelete.getId()), toDelete.getUsers());
    }

    public String getId() {
        return id;
    }

    private void sendMsg(P2PPacket packet, Set<String> ids) {
        ids.forEach(id -> {
            if (!id.equals(this.id)) {
                try {
                    ObjectOutputStream oos = sockets.get(id).oos;
                    oos.writeObject(packet);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private void connect(Peer2DiscoveryPacket packet) {
        //I send to the DISCOVERY_SERVER my ID and Port
        try (Socket s = new Socket()) {
            s.connect(DISCOVERY_SERVER);
            System.out.println("Connected");

            //Send a UpdateIpPacket
            ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());
            oos.writeObject(packet);
            System.out.println("Sent");

            oos.flush();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void setConnected(boolean connected) {
        this.connected = connected;

        if (!this.connected) {
            connect(new ByePacket(this.id));
        }

    }

    public boolean getConnected() {
        return this.connected;
    }
}