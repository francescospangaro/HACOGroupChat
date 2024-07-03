package it.polimi.messages;

import java.util.Map;

public record DeleteMessage(Map<String, Integer> vectorClocks, String sender) implements Message {
}
