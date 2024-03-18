package it.polimi;

import it.polimi.Exceptions.PeerAlreadyConnectedException;
import it.polimi.packets.*;
import it.polimi.utility.ChatToBackup;
import it.polimi.utility.Message;
import org.jetbrains.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    static final String SAVE_DIR = STR."\{System.getProperty("user.home")}\{File.separator}HACOBackup\{File.separator}";
    private static final Logger LOGGER = LoggerFactory.getLogger(Peer.class);

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
    private final String saveDirectory;
    private final Map<String, SocketAddress> ips;
    private final Set<String> disconnectedIds;
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
        this.saveDirectory = SAVE_DIR + id + File.separator;
        this.port = port;
        this.testing = testing;
        discovery = new DiscoveryConnector(new InetSocketAddress(discoveryAddr, 8080), id, port);

        this.msgChangeListener = msgChangeListener;

        chats = getFromBackup();
        sockets = new ConcurrentHashMap<>();
        degradedConnections = ConcurrentHashMap.newKeySet();
        ips = new ConcurrentHashMap<>();
        disconnectedIds = ConcurrentHashMap.newKeySet();

        roomsPropertyChangeSupport = new PropertyChangeSupport(chats);
        roomsPropertyChangeSupport.addPropertyChangeListener(chatRoomsChangeListener);

        usersPropertyChangeSupport = new PropertyChangeSupport(this);
        usersPropertyChangeSupport.addPropertyChangeListener(usersChangeListener);

        for (ChatRoom c : chats) {
            roomsPropertyChangeSupport.firePropertyChange("ADD_ROOM", null, c);
        }

        start();
    }


    public void start() {
        LOGGER.info(STR."[\{id}] STARTING");
        try {
            serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            throw new Error(e);
        }
        acceptTask = executorService.submit(this::runServer);

        //We are (re-)connecting from scratch, so delete all crashed peer and get a new list from the discovery
        disconnectedIds.clear();
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
                LOGGER.info(STR."[\{this.id}] Peer \{id} already connected");
            }
        });
        connected = true;
        //Try reconnecting to the peers I couldn't connect to previously
        reconnectToPeers();
    }

    private void reconnectToPeers() {
        //Every 5 seconds retry, until I'm connected with everyone
        reconnectTask = scheduledExecutorService.scheduleAtFixedRate(() -> disconnectedIds.forEach(id -> {
            var addr = ips.get(id);
            LOGGER.info(STR."[\{this.id}] Trying to reconnect to \{id}: \{addr}");
            try {
                connectToSinglePeer(id, addr);
            } catch (IOException e) {
                connectLock.unlock();
                LOGGER.warn(STR."[\{this.id}] Failed to reconnect to \{id}", e);
            } catch (PeerAlreadyConnectedException e) {
                connectLock.unlock();
                LOGGER.info(STR."Peer \{id} already connected");
            }
        }), 5, 5, TimeUnit.SECONDS);
    }

    @VisibleForTesting
    Socket createNewSocket() {
        return new Socket();
    }

    private Set<ChatRoom> getFromBackup() {
        var saveDir = new File(saveDirectory);
        var files = saveDir.listFiles();
        Set<ChatRoom> tempChats = ConcurrentHashMap.newKeySet();

        if (files == null)
            return tempChats;

        for (File f : files) {
            try (FileInputStream fileInputStream = new FileInputStream(f);
                 ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream)) {
                ChatToBackup tempChat = (ChatToBackup) objectInputStream.readObject();
                tempChats.add(new ChatRoom(tempChat.name(), tempChat.users(), tempChat.id(), msgChangeListener,
                        tempChat.vectorClocks(), tempChat.waiting(), tempChat.received()));
            } catch (IOException | ClassNotFoundException e) {
                LOGGER.error(STR."[\{this.id}] Error reading file \{f} from backup", e);
            }
        }
        return tempChats;
    }

    private void backupChats() {
        //Create all save directories
        try {
            Files.createDirectories(Paths.get(saveDirectory));
        } catch (IOException e) {
            LOGGER.error(STR."[\{this.id}] Error creating backup folder", e);
        }
        for (ChatRoom c : chats) {
            File backupFile = new File(STR."\{saveDirectory}\{c.getId()}.dat");
            try (FileOutputStream fileOutputStream = new FileOutputStream(backupFile);
                 ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream)) {
                objectOutputStream.writeObject(new ChatToBackup(c));
            } catch (IOException e) {
                LOGGER.error(STR."[\{this.id} Error during backup of chat \{c}", e);
            }
        }
    }


    private void connectToSinglePeer(String id, SocketAddress addr) throws PeerAlreadyConnectedException, IOException {
        connectLock.lock();
        LOGGER.trace(STR."[\{this.id}] Got lock to connect to \{id}: \{addr}");

        //After getting lock, re-check if this peer is not connected yet
        if (sockets.containsKey(id)) {
            throw new PeerAlreadyConnectedException();
        }

        Socket s = createNewSocket();
        LOGGER.trace(STR."[\{this.id}] connecting to \{id}");
        s.connect(addr, 500);

        LOGGER.info(STR."[\{this.id}] connected to \{id}: \{addr}");

        SocketManager socketManager = new SocketManager(this.id, port, id, executorService, s,
                new ChatUpdater(chats, roomsPropertyChangeSupport, msgChangeListener),
                this::onPeerDisconnected);

        disconnectedIds.remove(id);
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
        LOGGER.info(STR."[\{this.id}] Creating room \{name}");

        //Add the ChatRoom to the list of available ChatRooms
        ChatRoom newRoom = new ChatRoom(name, users, msgChangeListener);
        chats.add(newRoom);

        //Inform all the users about the creation of the new chat room by sending to them a CreateRoomPacket
        sendPacket(new CreateRoomPacket(newRoom.getId(), name, users), users);

        //Fires ADD_ROOM Event in order to update the GUI
        roomsPropertyChangeSupport.firePropertyChange("ADD_ROOM", null, newRoom);
    }

    public void deleteRoom(ChatRoom toDelete) {
        LOGGER.info(STR."[\{this.id}] Deleting room \{toDelete.getName()} \{toDelete.getId()}");
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
                        LOGGER.trace(STR."[\{this.id}] sending \{packet} to \{id}");
                        sendSinglePeer(packet, id);
                    });
                } else {
                    LOGGER.warn(STR."[\{this.id}] Peer \{id} currently disconnected, enqueuing packet only for him...");
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
            LOGGER.warn(STR."[\{this.id}] Error sending message to \{id}. Enqueuing it...", e);
            disconnectMsgs.computeIfAbsent(id, _ -> ConcurrentHashMap.newKeySet());
            disconnectMsgs.get(id).add(packet);
            onPeerDisconnected(id, e);
        }
        return false;
    }

    public void disconnect() {
        LOGGER.info(STR."[\{this.id}] Disconnecting...");
        connected = false;
        reconnectTask.cancel(true);

        acceptTask.cancel(true);
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }

        try {
            discovery.disconnect();
        } catch (IOException e) {
            LOGGER.error(STR."[\{this.id}] Can't contact the discovery", e);
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
        LOGGER.info(STR."[\{id}] server started");
        while (true) {
            Socket justConnectedClient;
            LOGGER.trace(STR."[\{id}] Waiting connection...");
            try {
                justConnectedClient = serverSocket.accept();
            } catch (IOException e) {
                //Error on the server socket, stop the server
                LOGGER.error(STR."[\{id}] Error accepting new connection. Server shut down. Socket closed: \{serverSocket.isClosed()}", e);
                return;
            } finally {
                LOGGER.trace(STR."[\{id}] Finished waiting connection");
            }

            //Someone has just connected to me
            LOGGER.info(STR."[\{id}] \{justConnectedClient.getRemoteSocketAddress()} is connected. Waiting for his id...");
            String otherId;
            int otherPort;

            try {
                //Try to acquire lock, timeout of 2 secs to avoid deadlocks.
                // if I can't acquire lock in 2 sec, I assume a deadlock and close the connection.
                if (!connectLock.tryLock(2, TimeUnit.SECONDS)) {
                    LOGGER.warn(STR."[\{id}] Can't get lock. Connection refused");
                    justConnectedClient.close();
                    continue;
                }

                LOGGER.trace(STR."[\{this.id}] Got lock to accept connection");

                SocketManager socketManager = new SocketManager(this.id, executorService, justConnectedClient,
                        new ChatUpdater(chats, roomsPropertyChangeSupport, msgChangeListener), this::onPeerDisconnected);

                otherId = socketManager.getOtherId();
                otherPort = socketManager.getServerPort();
                LOGGER.info(STR."[\{id}] \{otherId} is connected. His server is on port \{otherPort}");

                //Remove from the disconnected peers (if present)
                disconnectedIds.remove(otherId);

                //Close pending socket for this peer (if any)
                if (sockets.containsKey(otherId))
                    sockets.get(otherId).close();

                //Update the list of sockets of the other peers
                sockets.put(otherId, socketManager);

                //Update the list of Addresses of the other peers
                ips.put(otherId, new InetSocketAddress(justConnectedClient.getInetAddress(), otherPort));
            } catch (InterruptedException e) {
                //We got interrupted, quit
                LOGGER.error(STR."[\{id}] Server interrupted while getting lock. Server shut down.", e);
                return;
            } catch (IOException e) {
                //Error creating the socket manager, close the socket and continue listening for new connection
                LOGGER.error(STR."[\{this.id}] Error creating socket manager", e);
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
        LOGGER.warn(STR."[\{this.id}] \{id} disconnected", e);

        disconnectedIds.add(id);

        var socket = sockets.remove(id);
        if (socket != null)
            socket.close();

        usersPropertyChangeSupport.firePropertyChange("USER_DISCONNECTED", id, null);
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
        backupChats();
    }
}