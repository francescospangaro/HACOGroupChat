package it.polimi;

import it.polimi.packets.*;
import it.polimi.utility.ChatToBackup;
import it.polimi.utility.Message;
import it.polimi.Exceptions.PeerAlreadyConnectedException;
import it.polimi.packets.discovery.ByePacket;
import org.jetbrains.annotations.VisibleForTesting;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


public class Peer implements Closeable {
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
    private volatile ServerSocket serverSocket;
    private String saveDirectory = STR."\{System.getProperty("user.home")}\{File.separator}HACOBackup\{File.separator}";
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
    private final DiscoveryConnector discovery;

    @VisibleForTesting
    Peer(String id, int port,
         PropertyChangeListener chatRoomsChangeListener,
         PropertyChangeListener usersChangeListener,
         PropertyChangeListener msgChangeListener) {
        this("localhost", id, port, chatRoomsChangeListener, usersChangeListener, msgChangeListener, true);
    }

    public Peer(String discoveryAddr, String id, int port,
                PropertyChangeListener chatRoomsChangeListener,
                PropertyChangeListener usersChangeListener,
                PropertyChangeListener msgChangeListener) {
        this(discoveryAddr, id, port, chatRoomsChangeListener, usersChangeListener, msgChangeListener, false);
    }

    private Peer(String discoveryAddr, String id, int port,
                 PropertyChangeListener chatRoomsChangeListener,
                 PropertyChangeListener usersChangeListener,
                 PropertyChangeListener msgChangeListener,
                 boolean testing) {
        this.id = id;
        this.saveDirectory += id + File.separator;
        this.port = port;
        this.testing = testing;
        discovery = new DiscoveryConnector(new InetSocketAddress(discoveryAddr, 8080), id, port);

        chats = getFromBackup();
        sockets = new ConcurrentHashMap<>();
        degradedConnections = ConcurrentHashMap.newKeySet();
        ips = new ConcurrentHashMap<>();
        disconnectedIps = new ConcurrentHashMap<>();

        roomsPropertyChangeSupport = new PropertyChangeSupport(chats);
        roomsPropertyChangeSupport.addPropertyChangeListener(chatRoomsChangeListener);

        usersPropertyChangeSupport = new PropertyChangeSupport(this);
        usersPropertyChangeSupport.addPropertyChangeListener(usersChangeListener);

        this.msgChangeListener = msgChangeListener;

        for (ChatRoom c : chats) {
            roomsPropertyChangeSupport.firePropertyChange("ADD_ROOM", null, c);
        }

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
        try {
            ips.putAll(discovery.register());
        } catch (IOException e) {
            throw new Error("Can't connect to the discovery server", e);
        }
        connect();
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
        reconnectTask = scheduledExecutorService.scheduleAtFixedRate(() -> disconnectedIps.forEach((id, addr) -> {
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
        }), 5, 5, TimeUnit.SECONDS);
    }

    @VisibleForTesting
    Socket createNewSocket() {
        return new Socket();
    }

    private Set<ChatRoom> getFromBackup() {
        if (new File(saveDirectory).listFiles() == null)
            return ConcurrentHashMap.newKeySet();

        Set<ChatRoom> tempChats = ConcurrentHashMap.newKeySet();
        for (File f : Objects.requireNonNull(new File(saveDirectory).listFiles())) {
            try {
                FileInputStream fileInputStream = new FileInputStream(f);
                ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
                ChatToBackup tempChat = (ChatToBackup) objectInputStream.readObject();
                tempChats.add(new ChatRoom(tempChat.name(), tempChat.users(), tempChat.id(), msgChangeListener,
                        tempChat.vectorClocks(), tempChat.waiting(), tempChat.received()));
                objectInputStream.close();
                fileInputStream.close();
            } catch (IOException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
        return tempChats;
    }

    private void backupChats() {
        try {
            //Create all save directories
            Files.createDirectories(Paths.get(saveDirectory));
            for (ChatRoom c : chats) {
                File backupFile = new File(STR."\{saveDirectory}\{c.getId()}.dat");
                backupFile.createNewFile();
                FileOutputStream fileOutputStream = new FileOutputStream(backupFile);
                ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream);
                objectOutputStream.writeObject(new ChatToBackup(c));
                objectOutputStream.close();
                fileOutputStream.close();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private void connectToSinglePeer(String id, SocketAddress addr) throws PeerAlreadyConnectedException, IOException {
        connectLock.lock();
        System.out.println("Got client lock");

        //After getting lock, re-check if this peer is not connected yet
        if (sockets.containsKey(id)) {
            throw new PeerAlreadyConnectedException();
        }

        Socket s = createNewSocket();
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


    /**
     * Send a message to the given chat
     * <p>
     * This method is NOT thread-safe.
     * If it is called simultaneously by two threads, there is no guarantee on the order of the two messages.
     *
     * @param msg         the message to be sent
     * @param chat        chat where the message is sent
     * @param delayedTime seconds to wait before sending the message (for testing purposes)
     */
    public void sendMessage(String msg, ChatRoom chat, int delayedTime) {
        boolean isDelayed = delayedTime != 0;

        Message m = chat.send(msg, id);

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
        removeChatBackup(toDelete);
        roomsPropertyChangeSupport.firePropertyChange("DEL_ROOM", toDelete, null);
    }

    private void removeChatBackup(ChatRoom toDelete) {
        File chatToDelete = new File(STR."\{saveDirectory}\{toDelete.getId()}.dat");
        // If the file is not deleted is means that it wasn't backed up in the first place
        // We don't care for the deletion outcome
        chatToDelete.delete();
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
                    disconnectMsgs.computeIfAbsent(id, _ -> ConcurrentHashMap.newKeySet());
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
            disconnectMsgs.computeIfAbsent(id, _ -> ConcurrentHashMap.newKeySet());
            disconnectMsgs.get(id).add(packet);
            onPeerDisconnected(id, e);
        }
        return false;
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

        try {
            discovery.sendToDiscovery(new ByePacket(id));
        } catch (IOException e) {
            System.err.println("Can't contact the discovery " + e);
        }

        sockets.keySet().forEach(id -> onPeerDisconnected(id, new IOException("Disconnected")));
        sockets.clear();
        ips.clear();
    }

    public void degradePerformance(String id) {
        degradedConnections.add(id);
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

                SocketManager socketManager = new SocketManager(this.id, executorService, justConnectedClient,
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
        backupChats();
        disconnect();
        scheduledExecutorService.shutdownNow();
        executorService.shutdownNow();
    }
}