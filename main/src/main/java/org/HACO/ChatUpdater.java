package org.HACO;

import org.HACO.packets.CreateRoomPacket;
import org.HACO.packets.DelayedMessagePacket;
import org.HACO.packets.DeleteRoomPacket;
import org.HACO.packets.MessagePacket;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.Socket;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

public class ChatUpdater extends Thread {
    private final Socket otherPeerSocket;
    private final ObjectInputStream ois;
    private final Set<ChatRoom> chats;
    private final PropertyChangeSupport propertyChangeSupport;
    private final PropertyChangeListener msgChangeListener;


    public ChatUpdater(Socket otherPeerSocket, ObjectInputStream ois, Set<ChatRoom> chats,
                       PropertyChangeSupport propertyChangeSupport, PropertyChangeListener msgChangeListener) {
        this.otherPeerSocket = otherPeerSocket;
        this.chats = chats;
        this.ois = ois;
        this.propertyChangeSupport = propertyChangeSupport;
        this.msgChangeListener = msgChangeListener;
    }

    @Override
    public void run() {
        //Ready to handle messages received from the peer associated with otherPeerSocket
        System.out.println("Run");

        while (!otherPeerSocket.isClosed()) {
            try {
                //Wait for a packet
                Object o = ois.readObject();

                //Message handler
                switch (o) {
                    case MessagePacket m -> {
                        ChatRoom chat = chats.stream().filter(c -> Objects.equals(c.getId(), m.chatId())).findFirst().orElseThrow();
                        chat.push(m.msg());
                    }
                    //Sends a message with a delay of 7 seconds, in order to test the vector clocks ordering
                    case DelayedMessagePacket dm -> {
                        System.out.println("Message delayed!");
                        sleep(dm.delayedTime() * 1000L);
                        ChatRoom chat = chats.stream().filter(c -> Objects.equals(c.getId(), dm.chatId())).findFirst().orElseThrow();
                        chat.push(dm.msg());
                    }

                    case CreateRoomPacket crp -> {
                        System.out.println("Adding new room " + crp.id());
                        ChatRoom newChat = new ChatRoom(crp.name(), crp.ids(), crp.id(), msgChangeListener);
                        chats.add(newChat);
                        propertyChangeSupport.firePropertyChange("ADD_ROOM", null, newChat);
                    }

                    case DeleteRoomPacket drp -> {
                        System.out.println("Deleting room " + drp.id());
                        ChatRoom toDelete = chats.stream().filter(c -> Objects.equals(c.getId(), drp.id())).findFirst().orElseThrow();
                        chats.remove(toDelete);
                        propertyChangeSupport.firePropertyChange("DEL_ROOM", toDelete, null);
                    }
                    default -> throw new IllegalStateException("Unexpected value: " + o);
                }
            } catch (IOException | ClassNotFoundException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
