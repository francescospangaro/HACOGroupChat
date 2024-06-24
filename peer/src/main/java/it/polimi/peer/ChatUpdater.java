package it.polimi.peer;

import it.polimi.packets.ByePacket;
import it.polimi.packets.p2p.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketAddress;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ChatUpdater implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatUpdater.class);

    private final PeerSocketManager socketManager;
    private final Set<ChatRoom> chats;
    private final PropertyChangeSupport propertyChangeSupport;
    private final PropertyChangeListener msgChangeListener;
    private final BiConsumer<String, SocketAddress> onPeerConnected;
    private final Consumer<String> onPeerDisconnected;
    private final Set<MessagePacket> waitingMessages;
    private final Set<DeleteRoomPacket> waitingDeletes;

    public ChatUpdater(PeerSocketManager socketManager,
                       Set<ChatRoom> chats,
                       PropertyChangeSupport propertyChangeSupport,
                       PropertyChangeListener msgChangeListener, BiConsumer<String, SocketAddress> onPeerConnected, Consumer<String> onPeerDisconnected) {
        this.socketManager = socketManager;
        this.chats = chats;
        this.propertyChangeSupport = propertyChangeSupport;
        this.msgChangeListener = msgChangeListener;
        this.onPeerConnected = onPeerConnected;
        this.onPeerDisconnected = onPeerDisconnected;
        this.waitingMessages = new HashSet<>();
        this.waitingDeletes = new HashSet<>();

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
                // Once we create a new chatroom, check for all waiting messages if they can be popped.
                // By blocking the messages here, it mimics the arrival of the messages, postponing it for the user until
                // the chatroom has been created
                popQueue();
                propertyChangeSupport.firePropertyChange("ADD_ROOM", null, newChat);
            }

            case DeleteRoomPacket drp -> deleteHandler(drp);

            case HelloPacket helloPacket -> onPeerConnected.accept(helloPacket.id(), sender);

            case ByePacket byePacket -> onPeerDisconnected.accept(byePacket.id());
        }
    }

    /**
     * With this method there's no need to add vector clocks to create room packets. Simply, the first packet in the vc list must be the chat creation.
     * We put all messages in a waiting queue if their chat is not found.
     *
     * @param m is the messagePacket to parse
     * @return 0 if the chat wasn't found and the message was put in a waiting queue
     * 1 if the message was passed to it's chatroom
     */
    private int messageHandler(MessagePacket m) {
        Iterator<ChatRoom> chatIterator = chats.iterator();
        ChatRoom chat = chatIterator.next();
        boolean found = false;
        while (chatIterator.hasNext()) {
            if (chat.getId() == m.chatId()) {
                chat.push(m.msg());
                found = true;
                break;
            } else {
                chat = chatIterator.next();
            }
        }
        if (!found) {
            waitingMessages.add(m);
            return 0;
        }
        return 1;
    }

    private int deleteHandler(DeleteRoomPacket drp) {
        ChatRoom toDelete = chats
                .stream()
                .filter(c -> Objects.equals(c.getId(), drp.id()))
                .findFirst()
                .orElse(null);
        if (toDelete != null) {
            LOGGER.info(STR."Deleting room \{toDelete.getName()} \{drp.id()}");
            toDelete.close();
            propertyChangeSupport.firePropertyChange("DEL_ROOM", toDelete, null);
            return 1;
        } else {
            waitingDeletes.add(drp);
            return 0;
        }
    }

    private void popQueue() {
        waitingMessages.removeIf(m -> messageHandler(m) == 1);
        waitingDeletes.removeIf(drp -> deleteHandler(drp) == 1);
    }
}