package it.polimi.peer;

import it.polimi.Message;
import it.polimi.packets.p2p.*;
import org.jetbrains.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

public class PeerController {
    private static final Logger LOGGER = LoggerFactory.getLogger(PeerController.class);
    private final Map<String, Integer> degradedConnections = new ConcurrentHashMap<>();
    private final Map<String, Queue<P2PPacket>> disconnectMsgs = new ConcurrentHashMap<>();
    private final String id;
    private final Set<ChatRoom> chats;
    private final Map<String, SocketAddress> ips;
    private final Set<String> connectedPeers;
    private final PeerSocketManager socketManager;
    private final PropertyChangeListener msgChangeListener;
    private final PropertyChangeSupport roomsPropertyChangeSupport;
    private final ExecutorService executorService;
    private final BackupManager backupManager;

    private final BiConsumer<String, Throwable> onPeerUnreachable;

    public PeerController(String id,
                          Set<ChatRoom> chats,
                          Map<String, SocketAddress> ips,
                          Set<String> connectedPeers,
                          PeerSocketManager socketManager,
                          PropertyChangeListener msgChangeListener,
                          PropertyChangeSupport roomsPropertyChangeSupport,
                          ExecutorService executorService,
                          BackupManager backupManager,
                          BiConsumer<String, Throwable> onPeerUnreachable) {
        this.id = id;
        this.chats = chats;
        this.ips = ips;
        this.connectedPeers = connectedPeers;
        this.socketManager = socketManager;
        this.msgChangeListener = msgChangeListener;
        this.roomsPropertyChangeSupport = roomsPropertyChangeSupport;
        this.executorService = executorService;
        this.backupManager = backupManager;
        this.onPeerUnreachable = onPeerUnreachable;
    }

    /**
     * Resend queued packets to a peer
     * <p>
     * Tries to resend packets in the {@link #disconnectMsgs} list (sent to a peer when it was disconnected).
     * Removes packets from the list when they are sent successfully.
     *
     * @param id id of the peer
     */
    public boolean resendQueued(String id) {
        if (disconnectMsgs.containsKey(id)) {
            Iterator<P2PPacket> iter = disconnectMsgs.get(id).iterator();
            while (iter.hasNext()) {
                if (!sendSinglePeer(iter.next(), id)) {
                    return false;
                } else {
                    iter.remove();
                }
            }
        }
        return true;
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
     * @param msg  the message to be sent
     * @param chat chat where the message is sent
     */
    public void sendMessage(String msg, ChatRoom chat) {

        Message m = chat.send(msg, id);

        //Send a MessagePacket containing the Message just created to each User of the ChatRoom
        Set<String> normalPeers = new HashSet<>(chat.getUsers());

        //For testing purposes
        normalPeers.removeAll(degradedConnections.keySet());
        sendPacket(new MessagePacket(chat.getId(), m), normalPeers);

        degradedConnections.forEach((u, d) -> sendPacket(new DelayedMessagePacket(chat.getId(), m, d), Set.of(u)));
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
        backupManager.removeChatBackup(toDelete);
        roomsPropertyChangeSupport.firePropertyChange("DEL_ROOM", toDelete, null);
    }

    /**
     * Sends the packet to the given peers
     * <p>
     * For each connected peer (peer in the {@link #connectedPeers} set, calls {@link #sendSinglePeer(P2PPacket, String)}.
     * For disconnected peers, adds the message to the {@link #disconnectMsgs} queue
     *
     * @param packet packet to be sent
     * @param ids    ids of peers to send to
     */
    void sendPacket(P2PPacket packet, Set<String> ids) {
        ids.forEach(id -> {
            if (!id.equals(this.id)) {
                if (connectedPeers.contains(id)) {
                    executorService.execute(() -> {
                        LOGGER.trace(STR."[\{this.id}] sending \{packet} to \{id}");
                        sendSinglePeer(packet, id);
                    });
                } else {
                    LOGGER.warn(STR."[\{this.id}] Peer \{id} currently disconnected, enqueuing packet only for him...");
                    disconnectMsgs.computeIfAbsent(id, _ -> new ConcurrentLinkedQueue<>()).add(packet);
                }
            }
        });
    }

    /**
     * Send the packet to the given peer
     * <p>
     * If the sending fails, adds the message to the {@link #disconnectMsgs} queue
     * and calls {@link PeerNetManager#onPeerUnreachable(String, Throwable)}
     *
     * @param packet packet to be sent
     * @param id     id of the peer to send to
     * @return true if the packet is correctly sent (ack received)
     */
    private boolean sendSinglePeer(P2PPacket packet, String id) {
        try {
            socketManager.send(packet, ips.get(id));
            return true;
        } catch (IOException e) {
            LOGGER.warn(STR."[\{this.id}] Error sending message to \{id}. Enqueuing it...", e);
            disconnectMsgs.computeIfAbsent(id, _ -> new ConcurrentLinkedQueue<>()).add(packet);
            onPeerUnreachable.accept(id, e);
        }
        return false;
    }

    public void degradeConnection(String id) {
        degradedConnections.put(id, 2);
    }

    public void degradeConnections(Map<String, Integer> delays) {
        degradedConnections.putAll(delays);
    }

    public void resetDegradedConnections() {
        degradedConnections.clear();
    }

    public Map<String, Integer> getDegradedConnections() {
        return Collections.unmodifiableMap(degradedConnections);
    }

    @VisibleForTesting
    public Collection<P2PPacket> getDisconnectMsgs(String id) {
        if (disconnectMsgs.containsKey(id))
            return Collections.unmodifiableCollection(disconnectMsgs.get(id));
        return null;
    }

    Map<String, Queue<P2PPacket>> getDisconnectMsgs() {
        return disconnectMsgs;
    }
}
