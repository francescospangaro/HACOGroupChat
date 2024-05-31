package it.polimi;

import it.polimi.Exceptions.PeerAlreadyConnectedException;
import org.jetbrains.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


public class PeerNetManager implements Closeable {
    private static final int DEFAULT_RECONNECT_TIMEOUT_SECONDS = 5;
    private static final int DEFAULT_FIRST_RECONNECT_TIMEOUT_SECONDS = 3;
    private static final Logger LOGGER = LoggerFactory.getLogger(PeerNetManager.class);

    private final String id;
    private final int port;
    private final Set<ChatRoom> chats;
    private final PropertyChangeSupport roomsPropertyChangeSupport;
    private final PropertyChangeSupport usersPropertyChangeSupport;

    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile Future<?> acceptTask;
    private volatile ServerSocket serverSocket;
    private final Map<String, SocketAddress> ips;
    private final Set<String> disconnectedIds;
    private final Map<String, SocketManager> sockets;

    private final PropertyChangeListener msgChangeListener;

    private boolean connected;
    private final int reconnectTimeoutSeconds;

    /**
     * Lock to acquire before connect to / accept connection from a new peer
     * to avoid that two peer try to connect to each other at the same time.
     */
    private final Lock connectLock = new ReentrantLock();
    private final DiscoveryConnector discovery;
    private final PeerController controller;
    private final BackupManager backupManager;

    @VisibleForTesting
    PeerNetManager(String id, int port,
                   PropertyChangeListener chatRoomsChangeListener,
                   PropertyChangeListener usersChangeListener,
                   PropertyChangeListener msgChangeListener) {
        this("localhost", id, port, chatRoomsChangeListener, usersChangeListener, msgChangeListener, 1);
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
                          PropertyChangeListener msgChangeListener) {
        this(discoveryAddr, id, port, chatRoomsChangeListener, usersChangeListener, msgChangeListener, DEFAULT_RECONNECT_TIMEOUT_SECONDS);
    }

    private PeerNetManager(String discoveryAddr, String id, int port,
                           PropertyChangeListener chatRoomsChangeListener,
                           PropertyChangeListener usersChangeListener,
                           PropertyChangeListener msgChangeListener,
                           int reconnectTimeoutSeconds) {
        this.id = id;
        this.port = port;
        discovery = new DiscoveryConnector(new InetSocketAddress(discoveryAddr, 8080), id, port);

        this.msgChangeListener = msgChangeListener;

        backupManager = new BackupManager(id, msgChangeListener);
        chats = backupManager.getFromBackup();
        sockets = new ConcurrentHashMap<>();
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

        controller = new PeerController(id, chats, sockets, msgChangeListener, roomsPropertyChangeSupport, executorService, backupManager, this::onPeerDisconnected);

        start();
    }


    /**
     * Starts the peer.
     * <p>
     * Opens the server socket and starts the server in a new thread (see {@link #runServer()})
     * Registers to the discovery server and receives the lists of peers in the network (see {@link DiscoveryConnector#register()})
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
        Runnable reconnectMethod = () -> disconnectedIds.forEach(id -> {
            var addr = ips.get(id);
            LOGGER.info(STR."[\{this.id}] Trying to reconnect to \{id}: \{addr}");
            try {
                connectToSinglePeer(id, addr);
            } catch (IOException e) {
                connectLock.unlock();
                LOGGER.warn(STR."[\{this.id}] Failed to reconnect to \{id}", e);
                Runnable reconnectToSinglePeer = () -> {
                    try {
                        connectToSinglePeer(id, addr);
                    } catch (IOException ex) {
                        connectLock.unlock();
                        LOGGER.warn(STR."[\{this.id}] Failed to reconnect to \{id}", e);
                    } catch (PeerAlreadyConnectedException ex) {
                        connectLock.unlock();
                        LOGGER.info(STR."Peer \{id} already connected");
                    }
                };
                scheduledExecutorService.schedule(reconnectToSinglePeer, DEFAULT_RECONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (PeerAlreadyConnectedException e) {
                connectLock.unlock();
                LOGGER.info(STR."Peer \{id} already connected");
            }
        });
        //Retry, until I'm connected with everyone
        reconnectTask = scheduledExecutorService.schedule(reconnectMethod, DEFAULT_FIRST_RECONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @VisibleForTesting
    Socket createNewSocket() {
        return new Socket();
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
     * @see PeerController#resendQueued(String)
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

        controller.resendQueued(id);
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

    public boolean isConnected() {
        return this.connected;
    }

    public PeerController getController() {
        return controller;
    }

    /**
     * Server loop
     * <p>
     * Accepts new connections from other peers.
     * Acquire the {@link #connectLock} when accepting a new connection to be sure that
     * we aren't connecting to the other peer at the same time.
     * Creates the {@link SocketManager} for each connected peer and resend queues messages calling {@link PeerController#resendQueued(String)}
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

            controller.resendQueued(otherId);
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

    /**
     * Close the peer
     * <p>
     * Disconnect from the network, shutdown tasks and backup chats
     *
     * @see #disconnect()
     * @see BackupManager#backupChats(Set)
     */
    @Override
    public void close() {
        disconnect();
        scheduledExecutorService.shutdownNow();
        executorService.shutdownNow();
        backupManager.backupChats(Collections.unmodifiableSet(chats));
    }
}