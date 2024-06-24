package it.polimi.peer;

import it.polimi.packets.ByePacket;
import it.polimi.packets.p2p.HelloPacket;
import it.polimi.packets.p2p.P2PPacket;
import it.polimi.peer.exceptions.DiscoveryUnreachableException;
import org.jetbrains.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.*;


public class PeerNetManager implements AutoCloseable {
    private static final int DEFAULT_RECONNECT_TIMEOUT_SECONDS = 5;
    private static final int DEFAULT_NETWORK_TIMEOUT_SECONDS = 5;
    private static final Logger LOGGER = LoggerFactory.getLogger(PeerNetManager.class);

    private final String id;
    protected final SocketAddress discoveryAddr;
    protected final int port;
    private final Set<ChatRoom> chats;
    private final PropertyChangeSupport roomsPropertyChangeSupport;
    private final PropertyChangeSupport usersPropertyChangeSupport;

    protected final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    private volatile ScheduledFuture<?> reconnectTask;
    private final Map<String, SocketAddress> ips;
    private final Set<String> connectedPeers;
    private final Set<String> unreachablePeers;
    private final PropertyChangeListener msgChangeListener;
    private PeerSocketManager socketManager;

    private boolean connected;
    private final int reconnectTimeoutSeconds;
    private final int networkTimeoutSeconds;

    private DiscoveryConnector discovery;
    private PeerController controller;
    private final BackupManager backupManager;
    private Future<?> updaterFuture;
    private Future<?> discoveryFuture;

    @VisibleForTesting
    public PeerNetManager(String id, int port,
                          PropertyChangeListener chatRoomsChangeListener,
                          PropertyChangeListener usersChangeListener,
                          PropertyChangeListener msgChangeListener) throws IOException {
        this("localhost", id, port, chatRoomsChangeListener, usersChangeListener, msgChangeListener, 1, 1);
    }

    /**
     * Creates a new peer
     * <p>
     * Tries to recover an existing backup (see {@link BackupManager#getFromBackup()}
     * and calls {@link #start()}
     *
     * @param discoveryAddr           address of the discovery server
     * @param id                      unique identifier of the peer
     * @param port                    port to listen on for new connections
     * @param chatRoomsChangeListener listener to call when a room is created or deleted
     * @param usersChangeListener     listener to call when a user is connected or disconnected
     * @param msgChangeListener       listener to call when a new message is received
     */
    public PeerNetManager(String discoveryAddr, String id, int port,
                          PropertyChangeListener chatRoomsChangeListener,
                          PropertyChangeListener usersChangeListener,
                          PropertyChangeListener msgChangeListener) throws IOException {
        this(discoveryAddr, id, port, chatRoomsChangeListener, usersChangeListener, msgChangeListener, DEFAULT_RECONNECT_TIMEOUT_SECONDS, DEFAULT_NETWORK_TIMEOUT_SECONDS);
    }

    private PeerNetManager(String discoveryAddr, String id, int port,
                           PropertyChangeListener chatRoomsChangeListener,
                           PropertyChangeListener usersChangeListener,
                           PropertyChangeListener msgChangeListener,
                           int reconnectTimeoutSeconds,
                           int networkTimeoutSeconds) throws IOException {
        this.id = id;

        this.msgChangeListener = msgChangeListener;

        backupManager = new BackupManager(id, msgChangeListener);
        chats = backupManager.getFromBackup();
        ips = new ConcurrentHashMap<>();
        connectedPeers = ConcurrentHashMap.newKeySet();
        unreachablePeers = ConcurrentHashMap.newKeySet();

        this.reconnectTimeoutSeconds = reconnectTimeoutSeconds;
        this.networkTimeoutSeconds = networkTimeoutSeconds;

        this.port = port;
        this.discoveryAddr = new InetSocketAddress(discoveryAddr, 8080);


        roomsPropertyChangeSupport = new PropertyChangeSupport(chats);
        roomsPropertyChangeSupport.addPropertyChangeListener(chatRoomsChangeListener);

        usersPropertyChangeSupport = new PropertyChangeSupport(this);
        usersPropertyChangeSupport.addPropertyChangeListener(usersChangeListener);

        for (ChatRoom c : chats) {
            roomsPropertyChangeSupport.firePropertyChange("ADD_ROOM", null, c);
        }


        start();
    }

    @VisibleForTesting
    protected PeerSocketManager createSocketManager() throws IOException {
        return new PeerSocketManager(id, executorService, discoveryAddr, port, networkTimeoutSeconds * 1000);
    }


    /**
     * Starts the peer.
     * <p>
     * Opens the socket manager and starts the chat updater
     * Registers to the discovery server and receives the lists of peers in the network (see {@link DiscoveryConnector#register()}
     * Calls {@link #connect()} to connect to other clients.
     *
     * @throws Error if the server socket can't be opened or the discovery server can't be reached
     */
    public void start() throws IOException {
        LOGGER.info(STR."[\{id}] STARTING");

        socketManager = createSocketManager();
        var updater = new ChatUpdater(socketManager, chats, roomsPropertyChangeSupport, msgChangeListener, this::onPeerConnected, this::onPeerDisconnected);
        discovery = new DiscoveryConnector(socketManager, id, updater);
        discoveryFuture = executorService.submit(discovery);
        updaterFuture = executorService.submit(updater);
        controller = new PeerController(id, chats, ips, connectedPeers, socketManager, msgChangeListener, roomsPropertyChangeSupport, executorService, backupManager, this::onPeerUnreachable);

        //We are (re-)connecting from scratch, so delete all crashed peer and get a new list from the discovery
        connectedPeers.clear();
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
                onPeerUnreachable(id, e);
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
     * (peers in the {@link #unreachablePeers} list)
     */
    private void reconnectToPeers() {
        //Every 5 seconds retry, until I'm connected with everyone
        reconnectTask = scheduledExecutorService.scheduleAtFixedRate(() -> unreachablePeers.forEach(id -> {
            var addr = ips.get(id);
            LOGGER.info(STR."[\{this.id}] Trying to reconnect to \{id}: \{addr}");
            try {
                connectToSinglePeer(id, addr);
            } catch (IOException e) {
                LOGGER.warn(STR."[\{this.id}] Failed to reconnect to \{id}", e);
            }
        }), reconnectTimeoutSeconds, reconnectTimeoutSeconds, TimeUnit.SECONDS);
    }


    /**
     * Connect to a peer and send queued message.
     * <p>
     * Creates the {@link PeerSocketManager} for the given peer
     *
     * @param id   id of the other peer
     * @param addr address of the other peer
     * @throws IOException in case of communication problems
     * @see PeerController#resendQueued(String)
     */
    private void connectToSinglePeer(String id, SocketAddress addr) throws IOException {
        LOGGER.info(STR."[\{this.id}] connecting to \{id}: \{addr}");

        socketManager.send(new HelloPacket(this.id), addr);

        onPeerConnected(id, addr);
    }

    public Map<String, SocketAddress> getIps() {
        return Collections.unmodifiableMap(ips);
    }

    public String getId() {
        return id;
    }

    /**
     * Disconnect from the network
     * <p>
     * Stops the server and the reconnection task.
     * Sends a disconnection packet to the discovery server {@link DiscoveryConnector#disconnect()}.
     * Closes connection with all peers {@link #onPeerUnreachable(String, Throwable)}
     */
    public void disconnect() throws DiscoveryUnreachableException {
        if (!connected)
            return;
        LOGGER.info(STR."[\{this.id}] Disconnecting...", new Exception());

        try {
            Map<String, Queue<P2PPacket>> enqueued = controller.getDisconnectMsgs();
            for (var entry : enqueued.entrySet()) {
                String id = entry.getKey();
                Queue<P2PPacket> queue = entry.getValue();
                if (!queue.isEmpty()) {
                    discovery.forwardQueue(id, queue);
                    enqueued.remove(id);
                }
            }
            LOGGER.info(STR."[\{this.id}] Queue forwarded");
            discovery.disconnect();
        } catch (IOException e) {
            LOGGER.error(STR."[\{this.id}] Can't contact the discovery", e);
            throw new DiscoveryUnreachableException(e);
        }

        reconnectTask.cancel(true);

        //Send ByePacket to all peer, also unreachable ones. They will be enqueued and forwarded to the discovery
        controller.sendPacket(new ByePacket(this.id), ips.keySet());
        connectedPeers.forEach(id -> usersPropertyChangeSupport.firePropertyChange("USER_DISCONNECTED", id, null));

        connectedPeers.clear();
        connected = false;

        updaterFuture.cancel(true);
        discoveryFuture.cancel(true);
        socketManager.close();

        ips.clear();
    }

    public boolean isConnected() {
        return this.connected;
    }

    public PeerController getController() {
        return controller;
    }

    /**
     * Method to call when a peer becomes unreachable
     * <p>
     * Adds the peer to the {@link #unreachablePeers} list
     *
     * @param id id of the disconnected peer
     * @param e  cause of the disconnection
     */
    private void onPeerUnreachable(String id, Throwable e) {
        LOGGER.warn(STR."[\{this.id}] \{id} disconnected", e);

        unreachablePeers.add(id);
        connectedPeers.remove(id);

        usersPropertyChangeSupport.firePropertyChange("USER_DISCONNECTED", id, null);
    }

    private void onPeerConnected(String id, SocketAddress addr) {
        LOGGER.info(STR."[\{this.id}] \{id} connected");

        ips.put(id, addr);
        unreachablePeers.remove(id);
        connectedPeers.add(id);

        controller.resendQueued(id);

        usersPropertyChangeSupport.firePropertyChange("USER_CONNECTED", null, id);
    }

    private void onPeerDisconnected(String id) {
        LOGGER.warn(STR."[\{this.id}] \{id} disconnected");

        unreachablePeers.remove(id);
        connectedPeers.remove(id);

        usersPropertyChangeSupport.firePropertyChange("USER_DISCONNECTED", id, null);
    }

    /**
     * Close the peer
     * <p>
     * Disconnect from the network, shutdown tasks and backup chats
     *
     * @see #disconnect()
     * @see BackupManager#backupChats(Set)
     */
    @Override
    public void close() throws DiscoveryUnreachableException {
        disconnect();
        scheduledExecutorService.shutdownNow();
        executorService.shutdownNow();
        backupManager.backupChats(Collections.unmodifiableSet(chats));
    }
}