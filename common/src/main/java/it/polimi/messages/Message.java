package it.polimi.messages;

import java.io.Serializable;
import java.util.Map;

public interface Message extends Serializable {
    String sender();
    Map<String, Integer> vectorClocks();
}
