package it.polimi;

import it.polimi.packets.ByePacket;
import it.polimi.packets.p2p.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.Set;
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
    }

    @Override
    public void run() {
        //Message handler
        try {
            while (true) {
                P2PPacket p = socketManager.receiveFromPeer();
                switch (p) {
                    case MessagePacket m -> {
                        ChatRoom chat = chats
                                .stream()
                                .filter(c -> Objects.equals(c.getId(), m.chatId()))
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException("This chat does not exists"));
                        chat.push(m.msg());
                    }
                    //Sends a message with a delay of 7 seconds, in order to test the vector clocks ordering
                    case DelayedMessagePacket dm -> {
                        LOGGER.warn(STR."Message delayed! \{dm}");
                        new Thread(() -> {
                            try {
                                Thread.sleep(dm.delayedTime() * 1000L);
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                            ChatRoom chat = chats
                                    .stream()
                                    .filter(c -> Objects.equals(c.getId(), dm.chatId()))
                                    .findFirst()
                                    .orElseThrow(() -> new IllegalStateException("This chat does not exists"));
                            chat.push(dm.msg());
                        }).start();
                    }

                    case CreateRoomPacket crp -> {
                        LOGGER.info(STR."Adding new room \{crp.name()} \{crp.id()}");
                        ChatRoom newChat = new ChatRoom(crp.name(), crp.ids(), crp.id(), msgChangeListener);
                        chats.add(newChat);
                        propertyChangeSupport.firePropertyChange("ADD_ROOM", null, newChat);
                    }

                    case DeleteRoomPacket drp -> {
                        ChatRoom toDelete = chats
                                .stream()
                                .filter(c -> Objects.equals(c.getId(), drp.id()))
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException("This chat does not exists"));
                        LOGGER.info(STR."Deleting room \{toDelete.getName()} \{drp.id()}");
                        chats.remove(toDelete);
                        propertyChangeSupport.firePropertyChange("DEL_ROOM", toDelete, null);
                    }
                    case NewPeer helloPacket -> onPeerConnected.accept(helloPacket.id(), helloPacket.addr());
                    case ByePacket byePacket -> onPeerDisconnected.accept(byePacket.id());
                    case HelloPacket helloPacket ->
                            throw new IllegalStateException(STR."SocketManager should have replaced this packet \{helloPacket}");
                }
            }
        } catch (IOException ex) {
            LOGGER.error("Failed to update chat", ex);
        }
    }
}