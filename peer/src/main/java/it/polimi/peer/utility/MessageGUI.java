package it.polimi.peer.utility;

import it.polimi.peer.ChatRoom;
import it.polimi.messages.StringMessage;

public record MessageGUI(StringMessage message, ChatRoom chatRoom) {
}
