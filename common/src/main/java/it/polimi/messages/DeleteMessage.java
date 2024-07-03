package it.polimi.messages;

import java.util.Map;

public record DeleteMessage(Map<String, Integer> vectorClocks, String sender) implements Message {
    @Override
    public String toString() {
        return "CLOSED THIS CHAT";
    }
}
