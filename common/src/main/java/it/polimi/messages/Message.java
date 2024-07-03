package it.polimi.messages;

import java.io.Serializable;
import java.util.Map;

public sealed interface Message extends Serializable permits DeleteMessage, StringMessage {
    String sender();
    Map<String, Integer> vectorClocks();
}
