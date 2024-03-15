package it.polimi.utility;

import it.polimi.ChatRoom;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record ChatToBackup(UUID id, String name, Set<String> users, Set<Message> waiting, List<Message> received,
                           Map<String, Integer> vectorClocks) implements Serializable {

    public ChatToBackup(ChatRoom chat) {
        this(chat.getId(), chat.getName(), chat.getUsers(), chat.getWaiting(), chat.getReceivedMsgs(), chat.getVectorClocks());
    }
}
