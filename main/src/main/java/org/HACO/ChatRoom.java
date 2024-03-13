package org.HACO;

import org.HACO.utility.Message;
import org.HACO.utility.MessageGUI;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ChatRoom {
    private final Set<String> users;
    private final Set<Message> waiting;
    private final List<Message> receivedMsgs;
    private final Map<String, Integer> vectorClocks;
    private final PropertyChangeSupport propertyChangeSupport;
    private final String name;
    private final UUID id;
    private final Lock pushLock;

    public ChatRoom(String name, Set<String> users, PropertyChangeListener msgChangeListener) {
        this(name, users, UUID.randomUUID(), msgChangeListener);
    }

    public ChatRoom(String name, Set<String> users, UUID id, PropertyChangeListener msgChangeListener) {
        this.name = name;
        this.users = users;
        this.id = id;
        this.pushLock = new ReentrantLock();
        this.waiting = ConcurrentHashMap.newKeySet();

        vectorClocks = new ConcurrentHashMap<>();
        for (String user : users) {
            vectorClocks.put(user, 0);
        }
        receivedMsgs = new CopyOnWriteArrayList<>();
        propertyChangeSupport = new PropertyChangeSupport(receivedMsgs);
        propertyChangeSupport.addPropertyChangeListener(msgChangeListener);
    }

    public ChatRoom(String name, Set<String> users, UUID id, PropertyChangeListener msgChangeListener,
                    Map<String, Integer> vectorClocks, Set<Message> waiting, List<Message> messages) {
        this.name = name;
        this.users = users;
        this.id = id;
        this.pushLock = new ReentrantLock();
        this.waiting = waiting;

        this.vectorClocks = vectorClocks;
        this.receivedMsgs = messages;
        propertyChangeSupport = new PropertyChangeSupport(receivedMsgs);
        propertyChangeSupport.addPropertyChangeListener(msgChangeListener);
    }


    private static String initId() {
        Random random = new Random();
        String temp = "";
        for (int i = 0; i < 10; i++) {
            if (i % 4 == 0) temp = temp.concat(String.valueOf(random.nextInt(10)));
            else temp = temp.concat(Character.toString((char) random.nextInt(255)));
        }
        return temp;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Message send(String msg, String sender) {
        try {
            pushLock.lock();
            vectorClocks.put(sender, vectorClocks.get(sender) + 1);
            Message m = new Message(msg, Map.copyOf(vectorClocks), sender);
            receivedMsgs.add(m);
            propertyChangeSupport.firePropertyChange("ADD_MSG", null, new MessageGUI(m, this));
            return m;
        } finally {
            pushLock.unlock();
        }
    }

    // TODO: test vc implementation
    public void push(Message m) {
        try {
            pushLock.lock();
            //Checks if the user can accept the message arrived, or if he has to put it in a queue
            int res = checkVC(m);
            if (res == 1) {
                //Increase the PID of the message sender
                for (String key : m.vectorClocks().keySet()) {
                    vectorClocks.put(key, vectorClocks.get(key) > m.vectorClocks().get(key) ? vectorClocks.get(key) : m.vectorClocks().get(key));
                }
                receivedMsgs.add(m);
                //Remove the message from the queue(if it was there)
                waiting.remove(m);
                propertyChangeSupport.firePropertyChange("ADD_MSG", null, new MessageGUI(m, this));
                //Check the message queue to see if we can accept any other message
                //RECURSION BABY LET'S GO
                for (Message w : waiting) {
                    push(w);
                }
            } else if (res == -1) {
                //puts the message in a queue
                System.out.println("[" + id + "] Message " + m.vectorClocks() + " added in waiting list");
                waiting.add(m);
            } else {
                //If it doesn't enter any of the above ifs ignore the received message
                System.out.println("[" + id + "] Ignoring duplicated message " + m.vectorClocks());
            }
        } finally {
            pushLock.unlock();
        }
    }

    public Set<String> getUsers() {
        return users;
    }

    /**
     * @param m Is the message to check
     * @return 1 if the message is in order
     * E.G. I have 2.0.1 and receive packet 2.1.1
     * -1 if the message is not in order
     * E.G. I have 2.0.1 and receive packet 3.1.1
     * returns 1 even if the message is not in order, but has been sent before one that I have
     * E.G. I have 2.0.1 and receive packet 0.1.0
     * returns 0 if I already have received the message
     * E.G. I have 2.0.1 and receive packet 1.0.1
     */
    private int checkVC(Message m) {
        Map<String, Integer> newClocks = Map.copyOf(m.vectorClocks());
        int checker = 0;
        boolean justEnough = false;
        //Cycle through all users
        for (String temp : users) {
            //If user's PID is increased by one from the one I have, and it's the first time this happens, then ok
            if ((Objects.equals(newClocks.get(temp), vectorClocks.get(temp) + 1) && !justEnough)) {
                justEnough = true;
                //If user's PID is greater than expected, or if I find another PID greater than one of the ones I have, then put the message in a queue
            } else if ((newClocks.get(temp) > vectorClocks.get(temp))) {
                return -1;
            } else if (newClocks.get(temp) <= vectorClocks.get(temp)) {
                checker++;
            }
        }
        //If all VCs are <= then mine do nothing
        if (checker == vectorClocks.size()) return 0;
        return 1;
    }

    public Set<Message> getWaiting() {
        return Collections.unmodifiableSet(waiting);
    }

    public Map<String, Integer> getVectorClocks() {
        return vectorClocks;
    }

    @Override
    public String toString() {
        return name;
    }

    public List<Message> getReceivedMsgs() {
        return receivedMsgs;
    }
}
