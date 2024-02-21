package org.HACO;

import org.HACO.packets.CreateRoomPacket;
import org.HACO.packets.DeleteRoomPacket;
import org.HACO.packets.Message;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.Socket;
import java.util.Objects;
import java.util.Set;

public class ChatUpdater implements Runnable {
    private final Socket socket;
    private final ObjectInputStream ois;
    private final Set<ChatRoom> chats;

    public ChatUpdater(Socket socket, ObjectInputStream ois, Set<ChatRoom> chats) {
        this.socket = socket;
        this.chats = chats;
        this.ois = ois;
    }

    @Override
    public void run() {
        System.out.println("RUNNNNNNNNNNNNNN");
        while (!socket.isClosed()) {
            try {
                Object o = ois.readObject();
                switch (o) {
                    case Message m -> {
                        ChatRoom chat = chats.stream().filter(c -> Objects.equals(c.getId(), m.chatId())).findFirst().orElseThrow();
                        chat.push(m);
                    }
                    case CreateRoomPacket crp -> chats.add(new ChatRoom(crp.ids(), crp.id()));
                    case DeleteRoomPacket drp -> {
                        ChatRoom toDelete = chats.stream().filter(c -> c.getId() == drp.id()).findFirst().orElseThrow();
                        chats.remove(toDelete);
                    }
                    default -> throw new IllegalStateException("Unexpected value: " + o);
                }
            } catch (IOException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
