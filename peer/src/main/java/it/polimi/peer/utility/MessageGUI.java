package it.polimi.peer.utility;

import it.polimi.messages.Message;
import it.polimi.peer.ChatRoom;

public record MessageGUI(Message message, ChatRoom chatRoom) {
}
