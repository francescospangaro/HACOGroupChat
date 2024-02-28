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
    private final Map<String, SocketManager> sockets;

    private final PropertyChangeListener msgChangeListener;

    private boolean connected;

    private final Set<String> degradedConnections;
    private final Map<String, Set<P2PPacket>> disconnectMsgs = new ConcurrentHashMap<>();

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

                System.out.println("[" + this.id + "] connected");

                SocketManager socketManager = new SocketManager(this.id, id, executorService, s,
                        new ChatUpdater(chats, roomsPropertyChangeSupport, msgChangeListener),
                        this::onPeerDisconnected);
                sockets.put(id, socketManager);

                usersPropertyChangeSupport.firePropertyChange("USER_CONNECTED", null, id);

                resendQueued(id);

            } catch (IOException e) {
                //TODO: retry???
                onPeerDisconnected(id, e);
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
            sockets.get(id).send(packet);
            return true;
        } catch (IOException e) {
            System.err.println("[" + this.id + "] Error sending message to +" + id + ". Enqueuing it...");
            onPeerDisconnected(id, e);
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


    public void disconnect() {
        System.out.println("[" + id + "] Disconnecting...");
        connected = false;

        sendToDiscovery(new ByePacket(id));

        try {
            serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        sockets.forEach((id, socketManager) -> {
            socketManager.close();
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

                //Someone has just connected to me
                System.out.println("[" + id + "]" + justConnectedClient.getRemoteSocketAddress() + " is connected");

                SocketManager socketManager = new SocketManager(this.id, null, executorService, justConnectedClient,
                        new ChatUpdater(chats, roomsPropertyChangeSupport, msgChangeListener), this::onPeerDisconnected);

                String otherId = socketManager.getOtherId();

                //Update the list of sockets of the other peers
                sockets.put(otherId, socketManager);

                //Update the list of Addresses of the other peers
                ips.put(otherId, justConnectedClient.getRemoteSocketAddress());

                usersPropertyChangeSupport.firePropertyChange("USER_CONNECTED", null, otherId);

                resendQueued(otherId);
            }
        } catch (SocketException e) {
            System.err.println("[" + id + "] Server shut down " + e);
        } catch (IOException e) {
            throw new Error(e);
        }
    }

    private void onPeerDisconnected(String id, Throwable e) {
        e.printStackTrace();
        ips.remove(id);
        sockets.remove(id).close();
        usersPropertyChangeSupport.firePropertyChange("USER_DISCONNECTED", id, null);
        System.err.println("[" + id + "] " + id + " disconnected " + e);
    }
}