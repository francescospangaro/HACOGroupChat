package it.polimi.peer.utility;

import it.polimi.messages.Message;
import it.polimi.messages.StringMessage;
import it.polimi.peer.ChatRoom;

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
public record ChatToBackup(UUID id, String name, Set<String> users, Set<Message> waiting,
                           Collection<Message> received,
                           Map<String, Integer> vectorClocks) implements Serializable {

    public ChatToBackup(ChatRoom chat) {
        this(chat.getId(), chat.getName(), chat.getUsers(), chat.getWaitingMessages(), chat.getReceivedMsgs(), chat.getVectorClocks());
    }
}
