package it.polimi;

import it.polimi.packets.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.*;
import java.util.function.Consumer;

public class ChatUpdater implements Consumer<P2PPacket> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatUpdater.class);

    private final Set<ChatRoom> chats;
    private final PropertyChangeSupport propertyChangeSupport;
    private final PropertyChangeListener msgChangeListener;
    private final Set<MessagePacket> waitingMessages;


    public ChatUpdater(Set<ChatRoom> chats,
                       PropertyChangeSupport propertyChangeSupport, PropertyChangeListener msgChangeListener) {
        this.chats = chats;
        this.propertyChangeSupport = propertyChangeSupport;
        this.msgChangeListener = msgChangeListener;
        this.waitingMessages = new HashSet<>();
    }

    @Override
    public void accept(P2PPacket p2PPacket) {
        //Message handler
        switch (p2PPacket) {
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
                chats.add(newChat);
                // Once we create a new chatroom, check for all waiting messages if they can be popped.
                // By blocking the messages here, it mimics the arrival of the messages, postponing it for the user until
                // the chatroom has been created
                popQueue();
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
            default -> throw new IllegalStateException(STR."Unexpected value: \{p2PPacket}");
        }
    }

    /**
     * With this method there's no need to add vector clocks to create room packets. Simply, the first packet in the vc list must be the chat creation.
     * We put all messages in a waiting queue if their chat is not found.
     *
     * @param m is the messagePacket to parse
     * @return  0 if the chat wasn't found and the message was put in a waiting queue
     *          1 if the message was passed to it's chatroom
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
        if (!found){
            waitingMessages.add(m);
            return 0;
        }
        return 1;
    }

    private void popQueue() {
        waitingMessages.removeIf(m -> messageHandler(m) == 1);
    }
}
