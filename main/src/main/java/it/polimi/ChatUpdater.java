package it.polimi;

import it.polimi.packets.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public class ChatUpdater implements Consumer<P2PPacket> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatUpdater.class);

    private final Set<ChatRoom> chats;
    private final PropertyChangeSupport propertyChangeSupport;
    private final PropertyChangeListener msgChangeListener;


    public ChatUpdater(Set<ChatRoom> chats,
                       PropertyChangeSupport propertyChangeSupport, PropertyChangeListener msgChangeListener) {
        this.chats = chats;
        this.propertyChangeSupport = propertyChangeSupport;
        this.msgChangeListener = msgChangeListener;
    }

    @Override
    public void accept(P2PPacket p2PPacket) {
        //Message handler
        switch (p2PPacket) {
            case MessagePacket m -> {
                ChatRoom chat = chats.stream().filter(c -> Objects.equals(c.getId(), m.chatId())).findFirst().orElseThrow();
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
                    ChatRoom chat = chats.stream().filter(c -> Objects.equals(c.getId(), dm.chatId())).findFirst().orElseThrow();
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
                ChatRoom toDelete = chats.stream().filter(c -> Objects.equals(c.getId(), drp.id())).findFirst().orElseThrow();
                LOGGER.info(STR."Deleting room \{toDelete.getName()} \{drp.id()}");
                chats.remove(toDelete);
                propertyChangeSupport.firePropertyChange("DEL_ROOM", toDelete, null);
            }
            default -> throw new IllegalStateException(STR."Unexpected value: \{p2PPacket}");
        }
    }
}
