package org.HACO;

import org.HACO.Exceptions.PeerAlreadyConnectedException;
import org.HACO.packets.*;
import org.HACO.packets.discovery.ByePacket;
import org.HACO.packets.discovery.IPsPacket;
import org.HACO.packets.discovery.Peer2DiscoveryPacket;
import org.HACO.packets.discovery.UpdateIpPacket;
import org.jetbrains.annotations.VisibleForTesting;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.Closeable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


public class Peer implements Closeable {
    private static final InetSocketAddress DISCOVERY_SERVER = new InetSocketAddress("localhost", 8080);
    private final String id;
    private final boolean testing;
    private final int port;
    private final Set<ChatRoom> chats;
    private final PropertyChangeSupport roomsPropertyChangeSupport;
    private final PropertyChangeSupport usersPropertyChangeSupport;

    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile Future<?> acceptTask;
    private ServerSocket serverSocket;

    private final Map<String, SocketAddress> ips;
    private final Map<String, SocketAddress> disconnectedIps;
    private final Map<String, SocketManager> sockets;

    private final PropertyChangeListener msgChangeListener;

    private boolean connected;

    private final Set<String> degradedConnections;
    private final Map<String, Set<P2PPacket>> disconnectMsgs = new ConcurrentHashMap<>();

    //Lock to acquire before connect to / accept connection from a new peer
    // to avoid that two peer try to connect to each other at the same time.
    private final Lock connectLock = new ReentrantLock();

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
        disconnectedIps = new ConcurrentHashMap<>();

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
        acceptTask = executorService.submit(this::runServer);

        //We are (re-)connecting from scratch, so delete all crashed peer and get a new list from the discovery
        disconnectedIps.clear();
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
                connectToSinglePeer(id, addr);
            } catch (IOException e) {
                connectLock.unlock();
                onPeerDisconnected(id, e);
            } catch (PeerAlreadyConnectedException e) {
                connectLock.unlock();
                System.out.println("Peer " + id + " already connected");
            }
        });
        connected = true;
        //Try reconnecting to the peers I couldn't connect to previously
        reconnectToPeers();
    }

    private void reconnectToPeers() {
        //Every 5 seconds retry, until I'm connected with everyone
        reconnectTask = scheduledExecutorService.scheduleAtFixedRate(() -> {
            disconnectedIps.forEach((id, addr) -> {
                System.out.println("Trying to reconnect to " + id);
                try {
                    connectToSinglePeer(id, addr);
                    ips.put(id, disconnectedIps.remove(id));
                } catch (IOException e) {
                    connectLock.unlock();
                    System.err.println("Failed to reconnect to " + id);
                    e.printStackTrace();
                } catch (PeerAlreadyConnectedException e) {
                    connectLock.unlock();
                    System.out.println("Peer " + id + " already connected");
                }
            });
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void connectToSinglePeer(String id, SocketAddress addr) throws PeerAlreadyConnectedException, IOException {
        connectLock.lock();
        System.out.println("Got client lock");

        //After getting lock, re-check if this peer is not connected yet
        if (sockets.containsKey(id)) {
            throw new PeerAlreadyConnectedException();
        }

        Socket s = new Socket();
        System.out.println("[" + this.id + "] connecting to " + id);
        s.connect(addr, 500);

        System.out.println("[" + this.id + "] connected");

        SocketManager socketManager = new SocketManager(this.id, id, executorService, s,
                new ChatUpdater(chats, roomsPropertyChangeSupport, msgChangeListener),
                this::onPeerDisconnected);
        sockets.put(id, socketManager);

        connectLock.unlock();

        usersPropertyChangeSupport.firePropertyChange("USER_CONNECTED", null, id);

        resendQueued(id);
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

        //Inform all the users about the creation of the new chat room by sending to them a CreateRoomPacket
        sendPacket(new CreateRoomPacket(newRoom.getId(), name, users), users);

        //Fires ADD_ROOM Event in order to update the GUI
        roomsPropertyChangeSupport.firePropertyChange("ADD_ROOM", null, newRoom);
    }

    public void deleteRoom(ChatRoom toDelete) {
        chats.remove(toDelete);

        sendPacket(new DeleteRoomPacket(toDelete.getId()), toDelete.getUsers());

        roomsPropertyChangeSupport.firePropertyChange("DEL_ROOM", toDelete, null);
    }

    public String getId() {
        return id;
    }

    private void sendPacket(P2PPacket packet, Set<String> ids) {
        ids.forEach(id -> {
            if (!id.equals(this.id)) {
                if (sockets.containsKey(id)) {
                    executorService.execute(() -> {
                        System.out.println("[" + this.id + "] sending " + packet + " to " + id);
                        sendSinglePeer(packet, id);
                    });
                } else {
                    System.out.println("[" + this.id + "] Peer " + id + " currently disconnected, enqueuing packet only for him...");
                    disconnectMsgs.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
                    disconnectMsgs.get(id).add(packet);
                }
            }
        });
    }

    private boolean sendSinglePeer(P2PPacket packet, String id) {
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

    private void sendToDiscovery(Peer2DiscoveryPacket packet/*, int tries*/) {
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
            //Couldn't connect to DS
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
            //if(tries>9)
            //    throw new Error(e);
            sendToDiscovery(packet/*, tries+1*/);
        }
    }


    public void disconnect() {
        System.out.println("[" + id + "] Disconnecting...");
        connected = false;
        reconnectTask.cancel(true);

        acceptTask.cancel(true);
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }

        sendToDiscovery(new ByePacket(id)/*, 0*/);

        sockets.forEach((id, socketManager) -> socketManager.close());
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

    private void runServer() {
        //Waiting for incoming packets by creating a serverSocket
        System.out.println("[" + id + "] server started");
        while (true) {
            Socket justConnectedClient;
            try {
                justConnectedClient = serverSocket.accept();
            } catch (IOException e) {
                //Error on the server socket, stop the server
                System.err.println("[" + id + "] Server shut down " + e + serverSocket.isClosed());
                return;
            }

            //Someone has just connected to me
            System.out.println("[" + id + "] " + justConnectedClient.getRemoteSocketAddress() + " is connected. Waiting for his id...");
            String otherId;

            try {
                //Try to acquire lock, timeout of 2 secs to avoid deadlocks.
                // if I can't acquire lock in 2 sec, I assume a deadlock and close the connection.
                if (!connectLock.tryLock(2, TimeUnit.SECONDS)) {
                    System.err.println("[" + id + "] Can't get lock. Connection refused");
                    justConnectedClient.close();
                    continue;
                }

                System.out.println("Got server lock");

                SocketManager socketManager = new SocketManager(this.id, null, executorService, justConnectedClient,
                        new ChatUpdater(chats, roomsPropertyChangeSupport, msgChangeListener), this::onPeerDisconnected);

                otherId = socketManager.getOtherId();
                System.out.println("[" + id + "]" + otherId + " is connected");

                //Remove from the disconnected peers (if present)
                disconnectedIps.remove(otherId);

                //Close pending socket for this peer (if any)
                if (sockets.containsKey(otherId))
                    sockets.get(otherId).close();

                //Update the list of sockets of the other peers
                sockets.put(otherId, socketManager);

                //Update the list of Addresses of the other peers
                ips.put(otherId, justConnectedClient.getRemoteSocketAddress());
            } catch (InterruptedException e) {
                //We got interrupted, quit
                e.printStackTrace();
                return;
            } catch (IOException e) {
                //Error creating the socket manager, close the socket and continue listening for new connection
                System.err.println("[" + this.id + "] Error accepting connection");
                try {
                    justConnectedClient.close();
                } catch (IOException ignored) {
                }
                continue;
            } finally {
                connectLock.unlock();
            }

            usersPropertyChangeSupport.firePropertyChange("USER_CONNECTED", null, otherId);

            resendQueued(otherId);
        }
    }

    private void onPeerDisconnected(String id, Throwable e) {
        e.printStackTrace();
        var addr = ips.remove(id);
        if (addr != null)
            disconnectedIps.put(id, addr);

        var socket = sockets.remove(id);
        if (socket != null)
            socket.close();

        usersPropertyChangeSupport.firePropertyChange("USER_DISCONNECTED", id, null);
        System.err.println("[" + id + "] " + id + " disconnected " + e);
    }

    @VisibleForTesting
    Set<P2PPacket> getDiscMsg(String id) {
        if (disconnectMsgs.containsKey(id))
            return Collections.unmodifiableSet(disconnectMsgs.get(id));
        return null;
    }

    @Override
    public void close() {
        disconnect();
        scheduledExecutorService.shutdownNow();
        executorService.shutdownNow();
    }
}