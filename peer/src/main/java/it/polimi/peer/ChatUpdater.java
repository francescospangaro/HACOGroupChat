package it.polimi.peer;

import it.polimi.SocketManager;
import it.polimi.packets.ByePacket;
import it.polimi.packets.p2p.*;
import org.jetbrains.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * ChatUpdater is the class in charge of parsing each incoming message, and update the chat
 * accordingly.
 * All messages put in waiting lists here are because the chatroom has not yet been created.
 */
public class ChatUpdater implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatUpdater.class);

    private PeerSocketManager socketManager;
    private final Set<ChatRoom> chats;
    private final PropertyChangeSupport roomChangeSupport;
    private final PropertyChangeListener msgChangeListener;
    private final BiConsumer<String, SocketAddress> onPeerConnected;
    private final Consumer<String> onPeerDisconnected;
    private final Set<MessagePacket> waitingMessages;
    private final Lock waitingMessagesLock;
    private final Set<CloseRoomPacket> waitingClose;
    private final Set<UUID> deletedRooms;

    public ChatUpdater(PeerSocketManager socketManager,
                       Set<ChatRoom> chats,
                       PropertyChangeSupport roomChangeSupport,
                       PropertyChangeListener msgChangeListener,
                       BiConsumer<String, SocketAddress> onPeerConnected,
                       Consumer<String> onPeerDisconnected,
                       Set<MessagePacket> waitingMessages,
                       Set<CloseRoomPacket> waitingClose) {
        this.socketManager = socketManager;
        this.chats = chats;
        this.roomChangeSupport = roomChangeSupport;
        this.msgChangeListener = msgChangeListener;
        this.onPeerConnected = onPeerConnected;
        this.onPeerDisconnected = onPeerDisconnected;
        this.waitingMessages = waitingMessages;
        this.waitingMessagesLock = new ReentrantLock();
        this.waitingClose = waitingClose;
        this.deletedRooms = ConcurrentHashMap.newKeySet();
    }

    @Override
    public void run() {
        //Message handler
        try {
            do {
                var packetPacketAndSender = socketManager.receiveFromPeer();
                handlePacket(packetPacketAndSender.packet(), packetPacketAndSender.sender());
            } while (!Thread.currentThread().isInterrupted());
        } catch (InterruptedIOException ex) {
            LOGGER.info("Chat updater stopped");
        } catch (IOException ex) {
            LOGGER.error("Failed to update chat", ex);
        }
    }

    public void setSocketManager(PeerSocketManager socketManager) {
        this.socketManager = socketManager;
    }

    void handlePacket(P2PPacket packet, SocketAddress sender) {
        switch (packet) {
            case MessagePacket m -> messageHandler(m);

            //Sends a message with a delay of 7 seconds, in order to test the vector clocks ordering
            case DelayedMessagePacket dm -> {
                LOGGER.warn(STR."Message delayed! \{dm}");
                new Thread(() -> {
                    try {
                        Thread.sleep(dm.delayedTime() * 1000L);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    MessagePacket m = new MessagePacket(dm.chatId(), dm.msg());
                    messageHandler(m);
                }).start();
            }

            case CreateRoomPacket crp -> {
                LOGGER.info(STR."Adding new room \{crp.name()} \{crp.id()}");
                ChatRoom newChat = new ChatRoom(crp.name(), crp.ids(), crp.id(), msgChangeListener);
                // If we used a normal put and the newChat was already present and closed, it would have reopened it, we don't want that
                chats.add(newChat);
                roomChangeSupport.firePropertyChange("ADD_ROOM", null, newChat);

                // Once we create a new chatroom, check for all waiting messages if they can be popped.
                // By blocking the messages here, it mimics the arrival of the messages, postponing it for the user until
                // the chatroom has been created
                popQueue();
            }

            case CloseRoomPacket crp -> closeHandler(crp);

            case HelloPacket helloPacket -> onPeerConnected.accept(helloPacket.id(), sender);

            case ByePacket byePacket -> onPeerDisconnected.accept(byePacket.id());
        }
    }

    private void messageHandler(MessagePacket m) {
        if (checkChatExists(m) == 0) {
            try {
                LOGGER.warn(STR."Received message \{m} from an unknown chat, keeping it on hold");
                waitingMessagesLock.lock();
                waitingMessages.add(m);
            } finally {
                waitingMessagesLock.unlock();
            }
        }
    }

    /**
     * With this method there's no need to add vector clocks to create room packets. Simply, the first packet in the vc list must be the chat creation.
     * We put all messages in a waiting queue if their chat is not found.
     *
     * @param m is the messagePacket to parse
     * @return 0 if the chat wasn't found and the message was put in a waiting queue
     * 1 if the message was passed to it's chatroom
     * -1 if the message is destined to a closed chatroom
     */
    private int checkChatExists(MessagePacket m) {
        if (deletedRooms.stream().anyMatch(x -> x.equals(m.chatId())))
            return -1;

        ChatRoom chatRoom = chats.stream()
                .filter(c -> Objects.equals(c.getId(), m.chatId()))
                .findFirst()
                .orElse(null);

        if (chatRoom != null) {
            chatRoom.addMessage(m.msg());
            return 1;
        } else {
            return 0;
        }
    }

    /**
     * Check if the chat exists in the open chats list
     * @param crp message indicating which chat to close
     * @return 1 if the chat is found open and is closed
     * 0 if the chat is not found in the chat list
     * -1 if the chat has already been deleted and cannot be seen from the user's perspective
     */
    private int closeHandler(CloseRoomPacket crp) {
        ChatRoom toClose = chats
                .stream()
                .filter(c -> Objects.equals(c.getId(), crp.chatId()))
                .findFirst()
                .orElse(null);
        if (toClose != null) {
            toClose.close(crp.closeMessage());
            return 1;
        } else if (deletedRooms.stream().anyMatch(x -> x.equals(crp.chatId())))
            return -1;
        else {
            waitingClose.add(crp);
            return 0;
        }
    }

    public void deleteChat(UUID chatID) {
        deletedRooms.add(chatID);
    }

    /**
     * Pops the waiting messages from their respective queues.
     * If the methods return 1, then it means that the chat was found and the packet has done its job, if
     * the return is -1, it means that the chat has been deleted and the packets can be removed from the queues
     * without problems
     */
    private void popQueue() {
        try {
            waitingMessagesLock.lock();
            waitingMessages.removeIf(m -> {
                int res = checkChatExists(m);
                if (res == 1 || res == -1) {
                    LOGGER.info(STR."Popped message \{m}, was waiting for chat creation");
                    return true;
                }
                return false;
            });
        } finally {
            waitingMessagesLock.unlock();
        }

        waitingClose.removeIf(crp -> {
            int res = closeHandler(crp);
            return res == 1 || res == -1;
        });
    }

    @VisibleForTesting
    public Set<MessagePacket> getWaitingMessages() {
        return Collections.unmodifiableSet(waitingMessages);
    }

    @VisibleForTesting
    public Set<CloseRoomPacket> getWaitingClose() {
        return Collections.unmodifiableSet(waitingClose);
    }
}