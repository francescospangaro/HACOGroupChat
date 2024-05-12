package it.polimi;

import it.polimi.packets.*;
import it.polimi.utility.Message;
import org.jetbrains.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

public class PeerController {
    private static final Logger LOGGER = LoggerFactory.getLogger(PeerController.class);
    private final Set<String> degradedConnections = ConcurrentHashMap.newKeySet();
    private final Map<String, Queue<P2PPacket>> disconnectMsgs = new ConcurrentHashMap<>();
    private final String id;
    private final Set<ChatRoom> chats;
    private final Map<String, SocketManager> sockets;
    private final PropertyChangeListener msgChangeListener;
    private final PropertyChangeSupport roomsPropertyChangeSupport;
    private final ExecutorService executorService;
    private final BackupManager backupManager;

    private final BiConsumer<String, Throwable> onPeerDisconnected;

    public PeerController(String id, Set<ChatRoom> chats, Map<String, SocketManager> sockets, PropertyChangeListener msgChangeListener, PropertyChangeSupport roomsPropertyChangeSupport, ExecutorService executorService, BackupManager backupManager, BiConsumer<String, Throwable> onPeerDisconnected) {
        this.id = id;
        this.chats = chats;
        this.sockets = sockets;
        this.msgChangeListener = msgChangeListener;
        this.roomsPropertyChangeSupport = roomsPropertyChangeSupport;
        this.executorService = executorService;
        this.backupManager = backupManager;
        this.onPeerDisconnected = onPeerDisconnected;
    }

    /**
     * Resend queued packets to a peer
     * <p>
     * Tries to resend packets in the {@link #disconnectMsgs} list (sent to a peer when it was disconnected).
     * Removes packets from the list when they are sent successfully.
     *
     * @param id id of the peer
     */
    public void resendQueued(String id) {
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

            //For testing purpose
            normalPeers.removeAll(degradedConnections);
            sendPacket(new DelayedMessagePacket(chat.getId(), m, 2), degradedConnections);

            sendPacket(new MessagePacket(chat.getId(), m), normalPeers);
        } else {
            sendPacket(new DelayedMessagePacket(chat.getId(), m, delayedTime), chat.getUsers());
        }
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
                    disconnectMsgs.computeIfAbsent(id, _ -> new ConcurrentLinkedQueue<>()).add(packet);
                }
            }
        });
    }

    /**
     * Send the packet to the given peer
     * <p>
     * If the sending fails, adds the message to the {@link #disconnectMsgs} queue
     * and calls {@link PeerNetManager#onPeerDisconnected(String, Throwable)}
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
            disconnectMsgs.computeIfAbsent(id, _ -> new ConcurrentLinkedQueue<>()).add(packet);
            onPeerDisconnected.accept(id, e);
        }
        return false;
    }

    @VisibleForTesting
    void degradePerformance(String id) {
        degradedConnections.add(id);
    }

    @VisibleForTesting
    Collection<P2PPacket> getDiscMsg(String id) {
        if (disconnectMsgs.containsKey(id))
            return Collections.unmodifiableCollection(disconnectMsgs.get(id));
        return null;
    }
}
