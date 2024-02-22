package org.HACO;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public class ChatRoom {
    private final Set<String> users;
    private List<Message> receivedMsgs = new CopyOnWriteArrayList<>();
    private final PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(receivedMsgs);

    private final String id, name;

    public ChatRoom(String name, Set<String> users, PropertyChangeListener msgChangeListener) {
        this.name = name;
        this.users = users;
        this.id = initId();
        propertyChangeSupport.addPropertyChangeListener(msgChangeListener);
    }

    public ChatRoom(String name, Set<String> users, String id, PropertyChangeListener msgChangeListener) {
        this.name = name;
        this.users = users;
        this.id = id;
        propertyChangeSupport.addPropertyChangeListener(msgChangeListener);
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
        //TODO: check vector clocks!
        receivedMsgs.add(m);
        propertyChangeSupport.firePropertyChange("ADD_MSG", null, m);
    }

    public Set<String> getUsers() {
        return users;
    }

    @Override
    public String toString() {
        return name;
    }
}
