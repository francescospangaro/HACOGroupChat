package org.HACO;

import org.HACO.packets.CreateRoomPacket;
import org.HACO.packets.DeleteRoomPacket;
import org.HACO.packets.Message;

import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.Socket;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

public class ChatUpdater implements Runnable {
    private final Socket socket;
    private final ObjectInputStream ois;
    private final Set<ChatRoom> chats;
    private final PropertyChangeSupport propertyChangeSupport;

    public ChatUpdater(Socket socket, ObjectInputStream ois, Set<ChatRoom> chats, PropertyChangeSupport propertyChangeSupport) {
        this.socket = socket;
        this.chats = chats;
        this.ois = ois;
        this.propertyChangeSupport = propertyChangeSupport;
    }

    @Override
    public void run() {
        System.out.println("Run");
        while (!socket.isClosed()) {
            try {
                Object o = ois.readObject();
                switch (o) {
                    case Message m -> {
                        ChatRoom chat = chats.stream().filter(c -> Objects.equals(c.getId(), m.chatId())).findFirst().orElseThrow();
                        chat.push(m);
                    }
                    case CreateRoomPacket crp -> {
                        System.out.println("Adding new room " + crp.id());
                        var old = Set.copyOf(chats);
                        chats.add(new ChatRoom(crp.ids(), crp.id()));
                        propertyChangeSupport.firePropertyChange("ADD_ROOM", old, Collections.unmodifiableSet(chats));
                    }
                    case DeleteRoomPacket drp -> {
                        var old = Set.copyOf(chats);
                        ChatRoom toDelete = chats.stream().filter(c -> c.getId() == drp.id()).findFirst().orElseThrow();
                        chats.remove(toDelete);
                        propertyChangeSupport.firePropertyChange("DEL_ROOM", old, Collections.unmodifiableSet(chats));
                    }
                    default -> throw new IllegalStateException("Unexpected value: " + o);
                }
            } catch (IOException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
