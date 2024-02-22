package org.HACO;

import org.HACO.packets.Message;

import java.util.List;
import java.util.Random;
import java.util.Set;

public class ChatRoom {
    private final Set<String> users;
    private List<Message> receivedMsgs;
    private final String id, name;

    public ChatRoom(String name, Set<String> users) {
        this.name = name;
        this.users = users;
        this.id = initId();
    }

    public ChatRoom(String name, Set<String> users, String id) {
        this.name = name;
        this.users = users;
        this.id = id;
    }

    private String initId() {
        Random random = new Random();
        String temp = "";
        for (int i = 0; i < 10; i++) {
            if (i % 4 == 0)
                temp = temp.concat(String.valueOf(random.nextInt(10)));
            else
                temp = temp.concat(Character.toString((char) random.nextInt(255)));
        }
        return temp;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void push(Message m) {
        //TODO: check vector clocks
        receivedMsgs.add(m);
    }

    @Override
    public String toString() {
        return name;
    }
}
