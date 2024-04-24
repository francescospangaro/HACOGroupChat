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
    private static final int DEFAULT_RECONNECT_TIMEOUT_SECONDS = 5;
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
    private final int reconnectTimeoutSeconds;

    private final Set<String> degradedConnections;
    private final Map<String, Set<P2PPacket>> disconnectMsgs = new ConcurrentHashMap<>();

    /**
     * Lock to acquire before connect to / accept connection from a new peer
     * to avoid that two peer try to connect to each other at the same time.
     */
    private final Lock connectLock = new ReentrantLock();
    private final DiscoveryConnector discovery;

    @VisibleForTesting
    Peer(String id, int port,
         PropertyChangeListener chatRoomsChangeListener,
         PropertyChangeListener usersChangeListener,
         PropertyChangeListener msgChangeListener) {
        this("localhost", id, port, chatRoomsChangeListener, usersChangeListener, msgChangeListener, true, 1);
    }

    /**
     * Creates a new peer
     * <p>
     * Tries to recover an existing backup (see {@link #getFromBackup()}
     * and calls {@link #start()}
     *
     * @param discoveryAddr           address of the discovery server
     * @param id                      unique identifier of the peer
     * @param port                    port to listen on for new connections
     * @param chatRoomsChangeListener listener to call when a room is created or deleted
     * @param usersChangeListener     listener to call when a user is connected or disconnected
     * @param msgChangeListener       listener to call when a new message is received
     */
    public Peer(String discoveryAddr, String id, int port,
                PropertyChangeListener chatRoomsChangeListener,
                PropertyChangeListener usersChangeListener,
                PropertyChangeListener msgChangeListener) {
        this(discoveryAddr, id, port, chatRoomsChangeListener, usersChangeListener, msgChangeListener, false, DEFAULT_RECONNECT_TIMEOUT_SECONDS);
    }

    private Peer(String discoveryAddr, String id, int port,
                 PropertyChangeListener chatRoomsChangeListener,
                 PropertyChangeListener usersChangeListener,
                 PropertyChangeListener msgChangeListener,
                 boolean testing,
                 int reconnectTimeoutSeconds) {
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

        this.reconnectTimeoutSeconds = reconnectTimeoutSeconds;

        roomsPropertyChangeSupport = new PropertyChangeSupport(chats);
        roomsPropertyChangeSupport.addPropertyChangeListener(chatRoomsChangeListener);

        usersPropertyChangeSupport = new PropertyChangeSupport(this);
        usersPropertyChangeSupport.addPropertyChangeListener(usersChangeListener);

        for (ChatRoom c : chats) {
            roomsPropertyChangeSupport.firePropertyChange("ADD_ROOM", null, c);
        }

        start();
    }


    /**
     * Starts the peer.
     * <p>
     * Opens the server socket and starts the server in a new thread (see {@link #runServer()}
     * Registers to the discovery server and receives the lists of peers in the network (see {@link DiscoveryConnector#register()}
     * Calls {@link #connect()} to connect to other clients.
     *
     * @throws Error if the server socket can't be opened or the discovery server can't be reached
     */
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


    /**
     * Connect to peers in the {@link #ips} map.
     * Call {@link #reconnectToPeers()} to start the reconnection task
     */
    private void connect() {
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

    /**
     * Starts the reconnection task.
     * <p>
     * Every {@link #reconnectTimeoutSeconds} seconds tries to reconnect to disconnected peer
     * (peers in the {@link #disconnectedIds} list)
     */
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
        }), reconnectTimeoutSeconds, reconnectTimeoutSeconds, TimeUnit.SECONDS);
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


    /**
     * Connect to a peer and send queued message.
     * <p>
     * Acquire the {@link #connectLock} before connecting so that we are sure that the other peer is not trying to connect to us.
     * Creates the {@link SocketManager} for the given peer
     *
     * @param id   id of the other peer
     * @param addr address of the other peer
     * @throws PeerAlreadyConnectedException if this peer was connected before the lock is acquired
     * @throws IOException                   in case of communication problems
     * @see #resendQueued(String)
     */
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

    /**
     * Resend queued packets to a peer
     * <p>
     * Tries to resend packets in the {@link #disconnectedIds} list (sent to a peer when it was disconnected).
     * Removes packets from the list when they are sent successfully.
     *
     * @param id id of the peer
     */
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
     * Sends the message to all users in the given chat.
     * If the delayedTime param is 0, a {@link MessagePacket} is sent, otherwise a {@link DelayedMessagePacket} is sent.
     * <p>
     * Warning: this method is NOT thread-safe.
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

    /**
     * Sends the packet to the given peers
     * <p>
     * For each connected peer (peer in the {@link #sockets} map, calls {@link #sendSinglePeer(P2PPacket, String)}.
     * For disconnected peers, adds the message to the {@link #disconnectMsgs} queue
     *
     * @param packet packet to be sent
     * @param ids    ids of peers to send to
     */
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

    /**
     * Send the packet to the given peer
     * <p>
     * If the sending fails, adds the message to the {@link #disconnectMsgs} queue
     * and calls {@link #onPeerDisconnected(String, Throwable)}
     *
     * @param packet packet to be sent
     * @param id     id of the peer to send to
     * @return true if the packet is correctly sent (ack received)
     */
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

    /**
     * Disconnect from the network
     * <p>
     * Stops the server and the reconnection task.
     * Sends a disconnection packet to the discovery server {@link DiscoveryConnector#disconnect()}.
     * Closes connection with all peers {@link #onPeerDisconnected(String, Throwable)}
     */
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

    @VisibleForTesting
    void degradePerformance(String id) {
        degradedConnections.add(id);
    }

    public boolean isConnected() {
        return this.connected;
    }

    /**
     * Server loop
     * <p>
     * Accepts new connections from other peers.
     * Acquire the {@link #connectLock} when accepting a new connection to be sure that
     * we aren't connecting to the other peer at the same time.
     * Creates the {@link SocketManager} for each connected peer and resend queues messages calling {@link #resendQueued(String)}
     */
    private void runServer() {
        final Random random = new Random();
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
                //Try to acquire lock, with a random timeout (~2 sec) to avoid deadlocks.
                // if I can't acquire lock, I assume a deadlock and close the connection.
                int timeout = random.nextInt(1500, 2500);
                if (!connectLock.tryLock(timeout, TimeUnit.MILLISECONDS)) {
                    LOGGER.warn(STR."[\{id}] Can't get lock. Refusing connection...");
                    justConnectedClient.close();
                    LOGGER.trace(STR."[\{id}] Connection refused. Continuing...");
                    continue;
                }

                try {
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
                } finally {
                    connectLock.unlock();
                }
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
            }

            usersPropertyChangeSupport.firePropertyChange("USER_CONNECTED", null, otherId);

            resendQueued(otherId);
        }
    }

    /**
     * Method to call when a peer is disconnected
     * <p>
     * Adds the peer to the {@link #disconnectedIds} list
     * Closes the socket and removes it from the {@link #sockets} map.
     *
     * @param id id of the disconnected peer
     * @param e  cause of the disconnection
     */
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

    /**
     * Close the peer
     * <p>
     * Disconnect from the network, shutdown tasks and backup chats
     *
     * @see #disconnect()
     * @see #backupChats()
     */
    @Override
    public void close() {
        disconnect();
        scheduledExecutorService.shutdownNow();
        executorService.shutdownNow();
        backupChats();
    }
}