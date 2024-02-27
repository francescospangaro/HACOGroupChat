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
import java.net.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Peer {
    private static final InetSocketAddress DISCOVERY_SERVER = new InetSocketAddress("localhost", 8080);
    private final String id;
    private final boolean testing;
    private final int port;
    private final Set<ChatRoom> chats;
    private final PropertyChangeSupport roomsPropertyChangeSupport;
    private final PropertyChangeSupport usersPropertyChangeSupport;

    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    private ServerSocket serverSocket;

    private final Map<String, SocketAddress> ips;
    private final Map<String, SocketInfo> sockets;

    private final PropertyChangeListener msgChangeListener;

    private boolean connected;

    private final Set<String> degradedConnections;
    private final Map<String, Set<P2PPacket>> disconnectMsgs = new ConcurrentHashMap<>();

    private record SocketInfo(Socket s, ObjectOutputStream oos, ObjectInputStream ois) {
    }

    public Peer(String id, int port,
                PropertyChangeListener chatRoomsChangeListener,
                PropertyChangeListener usersChangeListener,
                PropertyChangeListener msgChangeListener,
                boolean testing) {
        this.id = id;
        this.port = port;
        this.testing = testing;

        chats = ConcurrentHashMap.newKeySet();
        sockets = new ConcurrentHashMap<>();
        degradedConnections = ConcurrentHashMap.newKeySet();
        ips = new ConcurrentHashMap<>();

        roomsPropertyChangeSupport = new PropertyChangeSupport(chats);
        roomsPropertyChangeSupport.addPropertyChangeListener(chatRoomsChangeListener);

        usersPropertyChangeSupport = new PropertyChangeSupport(this);
        usersPropertyChangeSupport.addPropertyChangeListener(usersChangeListener);

        this.msgChangeListener = msgChangeListener;

        start();
    }

    public void start() {
        System.out.println("STARTING " + id);
        try {
            serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            throw new Error(e);
        }
        executorService.execute(this::runServer);
        ips.putAll(register());
        connect();
    }

    private Map<String, SocketAddress> register() {
        //I send to the DISCOVERY_SERVER my ID and Port
        try (Socket s = new Socket()) {
            s.connect(DISCOVERY_SERVER);
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
        } catch (IOException | ClassNotFoundException e) {
            throw new Error(e);
        }
    }

    public void connect() {
        //For each peer in the network I try to connect to him by sending a helloPacket
        ips.forEach((id, addr) -> {
            try {
                Socket s = new Socket();
                System.out.println("[" + this.id + "] connecting to " + id);
                s.connect(addr);
                s.setTcpNoDelay(true);

                System.out.println("[" + this.id + "] connected");

                var oos = new ObjectOutputStream(s.getOutputStream());
                var ois = new ObjectInputStream(s.getInputStream());
                sockets.put(id, new SocketInfo(s, oos, ois));

                //Send a helloPacket
                HelloPacket helloPacket = new HelloPacket(this.id);
                oos.writeObject(helloPacket);
                oos.flush();

                usersPropertyChangeSupport.firePropertyChange("USER_CONNECTED", null, id);

                resendQueued(id);

                CompletableFuture.runAsync(new ChatUpdater(s, ois, chats, roomsPropertyChangeSupport, msgChangeListener), executorService)
                        .thenRun(() -> {
                            ips.remove(id);
                            sockets.remove(id);
                            usersPropertyChangeSupport.firePropertyChange("USER_DISCONNECTED", id, null);
                            System.err.println("[" + this.id + "] " + id + " disconnected");
                        });
            } catch (IOException e) {
                //TODO: retry???
                disconnectPeer(id);
            }
        });
        connected = true;
    }

    private void resendQueued(String id) {
        if (disconnectMsgs.containsKey(id)) {
            Iterator<P2PPacket> iter = disconnectMsgs.get(id).iterator();
            while (iter.hasNext()) {
                if (!sendSinglePeer(iter.next(), id)) {
                    break;
                } else {
                    iter.remove();
                }
            }
        }
    }


    public void sendMessage(String msg, ChatRoom chat, int delayedTime) {
        boolean isDelayed = delayedTime != 0;

        Map<String, Integer> vc = new HashMap<>();
        for (String s : chat.getUsers()) {
            if (s.equals(this.id))
                vc.put(this.id, chat.getVectorClocks().get(s) + 1);
            else
                vc.put(s, chat.getVectorClocks().get(s));
        }

        Message m = new Message(msg, vc, this.id);
        chat.pushWithoutCheck(m);

        //Send a MessagePacket containing the Message just created to each User of the ChatRoom
        if (!isDelayed) {
            Set<String> normalPeers = new HashSet<>(chat.getUsers());
            if (testing) {
                normalPeers.removeAll(degradedConnections);
                sendPacket(new DelayedMessagePacket(chat.getId(), m, 2), degradedConnections);
            }
            sendPacket(new MessagePacket(chat.getId(), m), normalPeers);
        } else {
            sendPacket(new DelayedMessagePacket(chat.getId(), m, delayedTime), chat.getUsers());
        }
    }

    public Map<String, SocketAddress> getIps() {
        return Collections.unmodifiableMap(ips);
    }

    public void createRoom(String name, Set<String> users) {
        //Add the ChatRoom to the list of available ChatRooms
        ChatRoom newRoom = new ChatRoom(name, users, msgChangeListener);
        chats.add(newRoom);

        //Fires ADD_ROOM Event in order to update the GUI
        roomsPropertyChangeSupport.firePropertyChange("ADD_ROOM", null, newRoom);

        //Inform all the users about the creation of the new chat room by sending to them a CreateRoomPacket
        sendPacket(new CreateRoomPacket(newRoom.getId(), name, users), users);
    }

    public void deleteRoom(ChatRoom toDelete) {
        roomsPropertyChangeSupport.firePropertyChange("DEL_ROOM", toDelete, null);
        chats.remove(toDelete);

        sendPacket(new DeleteRoomPacket(toDelete.getId()), toDelete.getUsers());
    }

    public String getId() {
        return id;
    }

    private void sendPacket(P2PPacket packet, Set<String> ids) {
        ids.forEach(id -> {
            if (!id.equals(this.id)) {
                if (sockets.containsKey(id)) {
                    System.out.println("[" + this.id + "] sending " + packet + " to " + id);
                    sendSinglePeer(packet, id);
                } else {
                    System.out.println("[" + this.id + "] Peer " + id + " currently disconnected, enqueuing packet only for him...");
                    disconnectMsgs.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
                    disconnectMsgs.get(id).add(packet);
                }
            }
        });
    }

    public boolean sendSinglePeer(P2PPacket packet, String id) {
        try {
            ObjectOutputStream oos = sockets.get(id).oos;
            oos.writeObject(packet);
            oos.flush();
            return true;
        } catch (IOException e) {
            System.err.println("[" + this.id + "] Error sending message to +" + id + ". Enqueuing it...");
            disconnectPeer(id);
            disconnectMsgs.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
            disconnectMsgs.get(id).add(packet);
        }
        return false;
    }

    private void sendToDiscovery(Peer2DiscoveryPacket packet) {
        //I send to the DISCOVERY_SERVER my ID and Port
        try (Socket s = new Socket()) {
            s.connect(DISCOVERY_SERVER);
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

        } catch (IOException | ClassNotFoundException e) {
            throw new Error(e);
        }
    }

    private void disconnectPeer(String id) {
        System.out.println("[" + this.id + "] Disconnecting " + id);
        try {
            sockets.remove(id).s.close();
        } catch (IOException e) {
            //We can ignore this(?)
        }
        ips.remove(id);
    }

    public void disconnect() {
        System.out.println("[" + id + "] Disconnecting...");
        connected = false;

        sendToDiscovery(new ByePacket(id));

        try {
            serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        sockets.forEach((id, socketInfo) -> {
            try {
                socketInfo.s.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        sockets.clear();
        ips.clear();
    }

    public void degradePerformance(String id) {
        degradedConnections.add(id);
    }

    public void resetDegradedPerformance() {
        degradedConnections.clear();
    }

    public boolean isConnected() {
        return this.connected;
    }

    @SuppressWarnings("InfiniteLoopStatement")
    private void runServer() {
        try {
            //Waiting for incoming packets by creating a serverSocket
            System.out.println("[" + id + "] server started");
            while (true) {
                Socket justConnectedClient = serverSocket.accept();
                justConnectedClient.setTcpNoDelay(true);

                //Someone has just connected to me
                System.out.println("[" + id + "]" + justConnectedClient.getRemoteSocketAddress() + " is connected");

                var oos = new ObjectOutputStream(justConnectedClient.getOutputStream());
                var ois = new ObjectInputStream(justConnectedClient.getInputStream());

                //I will receive a helloPacket from him containing his ID
                HelloPacket helloPacket = (HelloPacket) ois.readObject();

                //Update the list of sockets of the other peers
                sockets.put(helloPacket.id(), new SocketInfo(justConnectedClient, oos, ois));

                //Update the list of Addresses of the other peers
                ips.put(helloPacket.id(), justConnectedClient.getRemoteSocketAddress());

                usersPropertyChangeSupport.firePropertyChange("USER_CONNECTED", null, helloPacket.id());

                resendQueued(helloPacket.id());

                CompletableFuture.runAsync(new ChatUpdater(justConnectedClient, ois, chats, roomsPropertyChangeSupport, msgChangeListener), executorService)
                        .thenRun(() -> {
                            ips.remove(helloPacket.id());
                            sockets.remove(helloPacket.id());
                            usersPropertyChangeSupport.firePropertyChange("USER_DISCONNECTED", helloPacket.id(), null);
                            System.err.println("[" + id + "] " + helloPacket.id() + " disconnected");
                        });
            }
        } catch (SocketException e) {
            System.err.println("[" + id + "] Server shut down " + e);
        } catch (IOException | ClassNotFoundException e) {
            throw new Error(e);
        }
    }
}