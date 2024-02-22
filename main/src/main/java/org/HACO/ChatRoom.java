package org.HACO;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class ChatRoom {
    private final Set<String> users;
    private List<Message> waiting;
    private List<Message> receivedMsgs = new CopyOnWriteArrayList<>();
    private Map<String, Integer> vectorClocks;
    private final PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(receivedMsgs);

    private final String id, name;

    public ChatRoom(String name, Set<String> users, PropertyChangeListener msgChangeListener) {
        this.name = name;
        this.users = users;
        this.id = initId();
        waiting = new ArrayList<>();
        vectorClocks = new HashMap<>();
        for (String user : users) {
            vectorClocks.put(user, 0);
        }
        propertyChangeSupport.addPropertyChangeListener(msgChangeListener);
    }

    public ChatRoom(String name, Set<String> users, String id, PropertyChangeListener msgChangeListener) {
        this.name = name;
        this.users = users;
        this.id = id;
        waiting = new ArrayList<>();
        vectorClocks = new HashMap<>();
        for (String user : users) {
            vectorClocks.put(user, 0);
        }
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

    // TODO: test vc implementation
    public void push(Message m) {
        if (checkVC(m)) {
            vectorClocks.put(m.sender(), vectorClocks.get(m.sender()) + 1);
            receivedMsgs.add(m);
            waiting.remove(m);

            propertyChangeSupport.firePropertyChange("ADD_MSG", null, m);
            for(Message w : waiting){
                push(w);
            }
        }else{
            waiting.add(m);
        }
    }

    public Set<String> getUsers() {
        return users;
    }

    private boolean checkVC(Message m) {
        Map<String, Integer> oldClocks = Map.copyOf(vectorClocks);
        Map<String, Integer> newClocks = new HashMap<>();
        for(String s : oldClocks.keySet()){
            newClocks.put(s, oldClocks.get(s));
        }
        newClocks.put(m.sender(), vectorClocks.get(m.sender()) + 1);
        boolean justEnough = false;
        Iterator<String> iter = users.iterator();
        while (iter.hasNext()) {
            if ((Objects.equals(newClocks.get(iter.next()), oldClocks.get(iter.next()) + 1) && !justEnough)) {
                justEnough = true;
            } else if ((newClocks.get(iter.next()) > oldClocks.get(iter.next()))) {
                return false;
            }
        }
        return true;
    }

    public Map<String, Integer> getVectorClocks() {
        return vectorClocks;
    }

    @Override
    public String toString() {
        return name;
    }
}
