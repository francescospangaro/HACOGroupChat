package it.polimi.peer;

import it.polimi.messages.DeleteMessage;
import it.polimi.messages.StringMessage;
import it.polimi.peer.utility.MessageGUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ChatRoom {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatRoom.class);

    private final Set<String> users;
    private final Set<StringMessage> waitingMessages;
    private final Set<DeleteMessage> waitingDeleteRoomMessages;
    private final Queue<StringMessage> receivedMsgs;
    private final Map<String, Integer> vectorClocks;
    private final PropertyChangeSupport msgChangeSupport;
    private final String name;
    private final UUID id;
    private final Lock pushLock;
    private Boolean closed;

    public ChatRoom(String name,
                    Set<String> users,
                    PropertyChangeListener msgChangeListener) {
        this(name, users, UUID.randomUUID(), msgChangeListener);
    }

    public ChatRoom(String name,
                    Set<String> users,
                    UUID id,
                    PropertyChangeListener msgChangeListener) {
        this.name = name;
        this.users = Set.copyOf(users);
        this.id = id;
        this.pushLock = new ReentrantLock();
        this.waitingMessages = new LinkedHashSet<>();
        this.waitingDeleteRoomMessages = new LinkedHashSet<>();
        this.closed = false;

        vectorClocks = new HashMap<>();
        for (String user : users) {
            vectorClocks.put(user, 0);
        }
        receivedMsgs = new ConcurrentLinkedQueue<>();
        msgChangeSupport = new PropertyChangeSupport(receivedMsgs);
        msgChangeSupport.addPropertyChangeListener(msgChangeListener);
    }

    public ChatRoom(String name,
                    Set<String> users,
                    UUID id,
                    PropertyChangeListener msgChangeListener,
                    Map<String, Integer> vectorClocks,
                    Set<StringMessage> waiting,
                    Collection<StringMessage> messages,
                    Set<DeleteMessage> waitingDeleteRoomMessages) {
        this.name = name;
        this.users = Set.copyOf(users);
        this.id = id;
        this.pushLock = new ReentrantLock();
        this.waitingMessages = new LinkedHashSet<>(waiting);
        this.waitingDeleteRoomMessages = new LinkedHashSet<>(waitingDeleteRoomMessages);
        this.closed = false;

        this.vectorClocks = new HashMap<>(vectorClocks);
        this.receivedMsgs = new ConcurrentLinkedQueue<>(messages);

        msgChangeSupport = new PropertyChangeSupport(receivedMsgs);
        msgChangeSupport.addPropertyChangeListener(msgChangeListener);
    }


    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Boolean isClosed() {
        return closed;
    }

    /**
     * Create a new message with the given string and the local user
     * and calculate the vector clocks.
     *
     * @param msg    message
     * @param sender the local user
     * @return message obj with the vector clocks
     */
    public StringMessage createLocalMessage(String msg, String sender) {
        try {
            pushLock.lock();
            increaseVC(sender);
            StringMessage m = new StringMessage(msg, Map.copyOf(vectorClocks), sender);
            receivedMsgs.add(m);
            msgChangeSupport.firePropertyChange("ADD_MSG", null, new MessageGUI(m, this));
            return m;
        } finally {
            pushLock.unlock();
        }
    }

    /**
     * Add a new message if possibile (checks vector clocks),
     * otherwise put it in the waiting list.
     *
     * @param m message received
     */
    public void addMessage(StringMessage m) {
        try {
            pushLock.lock();
            //Checks if the user can accept the message arrived, or if he has to put it in a queue
            switch (checkVC(m.vectorClocks())) {
                //Accept message
                case 1:
                    //Increase the PID of the message sender
                    vectorClocks.put(m.sender(), m.vectorClocks().get(m.sender()));

                    receivedMsgs.add(m);

                    msgChangeSupport.firePropertyChange("ADD_MSG", null, new MessageGUI(m, this));

                    //Check the message queue to see if we can accept any other message
                    checkWaiting();
                    break;
                //Message already received
                case 0:
                    //If it doesn't enter any of the above ifs ignore the received message
                    LOGGER.info(STR."[\{id}] Ignoring duplicated message \{m.vectorClocks()}");
                    break;
                //Message can't be accepted (arrived out of order)
                case -1:
                    //puts the message in a queue
                    LOGGER.info(STR."[\{id}] Message \{m.vectorClocks()} added in waiting list");
                    waitingMessages.add(m);
            }
        } finally {
            pushLock.unlock();
        }
    }

    /**
     * After a message has been accepted checks for all messages enqueued if any other one can be popped.
     * Check recursively until a cycle in which no messages are popped
     */
    private void checkWaiting() {
        boolean added;
        do {
            added = false;
            var iter = waitingMessages.iterator();
            while (iter.hasNext()) {
                StringMessage m = iter.next();
                if (checkVC(m.vectorClocks()) == 1) {
                    //Increase the PID of the message sender
                    vectorClocks.put(m.sender(), m.vectorClocks().get(m.sender()));

                    LOGGER.info(STR."[\{this.id}] Removing message \{m.vectorClocks()} from waiting list");
                    receivedMsgs.add(m);
                    added = true;

                    //Remove the message from the queue
                    iter.remove();
                    msgChangeSupport.firePropertyChange("ADD_MSG", null, new MessageGUI(m, this));
                }
            }
        } while (added);
        // Checks for waiting DeleteRoomPackets after having checked for normal messages
        do {
            for (DeleteMessage dm : waitingDeleteRoomMessages)
                added = close(dm);
        } while (added);

    }

    public Set<String> getUsers() {
        return users;
    }

    /**
     * @param vc Are the vector clocks to check
     * @return 1 if the message is in order
     * E.G. I have 2.0.1 and receive packet 2.1.1
     * -1 if the message is not in order
     * E.G. I have 2.0.1 and receive packet 3.1.1
     * returns 1 even if the message is not in order, but has been sent before one that I have
     * E.G. I have 2.0.1 and receive packet 0.1.0
     * returns 0 if I already have received the message
     * E.G. I have 2.0.1 and receive packet 1.0.1
     */
    private int checkVC(Map<String, Integer> vc) {
        Map<String, Integer> newClocks = Map.copyOf(vc);
        boolean senderFound = false;
        //Cycle through all users
        for (String u : users) {
            //If user's PID is increased by one from the one I have, and it's the first time this happens, then ok
            if ((newClocks.get(u) == vectorClocks.get(u) + 1 && !senderFound)) {
                senderFound = true;
                //If user's PID is greater than expected, or if I find another PID greater than one of the ones I have, then put the message in a queue
            } else if ((newClocks.get(u) > vectorClocks.get(u))) {
                return -1;
            }
        }
        //If all VCs are <= then mine do nothing
        if (!senderFound) return 0;
        return 1;
    }

    /**
     * Close the chatroom if possible (checks vector clocks),
     * otherwise enqueue the delete message
     *
     * @param dm delete message
     * @return true if the chat has been closed
     */
    public boolean close(DeleteMessage dm) {
        try {
            pushLock.lock();
            int c = checkVC(dm.vectorClocks());
            switch (c) {
                // Can be accepted
                case 1:
                    vectorClocks.put(dm.sender(), dm.vectorClocks().get(dm.sender()));
                    waitingDeleteRoomMessages.remove(dm);
                    closed = true;
                    LOGGER.info(STR."Deleting room \{name} \{id}");
                    msgChangeSupport.firePropertyChange("DEL_ROOM", this, null);
                    return true;
                case -1:
                    waitingDeleteRoomMessages.add(dm);
                    LOGGER.info(STR."[\{id}] Delete message \{dm.vectorClocks()} added in waiting list");
                    return false;
                default:
                    // The default case is 0, so the packet has already been accepted and parsed
                    LOGGER.info(STR."[\{id}] Ignoring duplicated delete message \{dm.vectorClocks()}");
                    return false;
            }
        } finally {
            pushLock.unlock();
        }
    }

    public Set<StringMessage> getWaitingMessages() {
        return Set.copyOf(waitingMessages);
    }

    public Map<String, Integer> getVectorClocks() {
        return Collections.unmodifiableMap(vectorClocks);
    }

    @Override
    public String toString() {
        return name;
    }

    public Collection<StringMessage> getReceivedMsgs() {
        return Collections.unmodifiableCollection(receivedMsgs);
    }

    public void localClose() {
        // Close the chatroom
        closed = true;
    }

    public DeleteMessage createDeleteMessage(String senderId) {
        increaseVC(senderId);
        return new DeleteMessage(Map.copyOf(vectorClocks), senderId);
    }

    private void increaseVC(String sender) {
        vectorClocks.replace(sender, vectorClocks.get(sender) + 1);
    }

    public Set<DeleteMessage> getWaitingDeleteRoomMessages() {
        return waitingDeleteRoomMessages;
    }
}

