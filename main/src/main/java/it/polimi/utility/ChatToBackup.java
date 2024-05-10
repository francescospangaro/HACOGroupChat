package it.polimi.utility;

import it.polimi.ChatRoom;

import java.io.Serializable;
import java.util.*;

/**
 * Serialization of a chatroom in order for it to be saved locally
 *
 * @param id
 * @param name
 * @param users
 * @param waiting
 * @param received
 * @param vectorClocks
 */
public record ChatToBackup(UUID id, String name, Set<String> users, Set<Message> waiting, Collection<Message> received,
                           Map<String, Integer> vectorClocks) implements Serializable {

    public ChatToBackup(ChatRoom chat) {
        this(chat.getId(), chat.getName(), chat.getUsers(), chat.getWaiting(), chat.getReceivedMsgs(), chat.getVectorClocks());
    }
}
