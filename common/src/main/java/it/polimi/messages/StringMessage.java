package it.polimi.messages;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * String message object
 *
 * @param msg
 * @param vectorClocks
 * @param sender
 */
public record StringMessage(String msg, Map<String, Integer> vectorClocks, String sender) implements Message {
    @Override
    public String toString() {
        return msg;
    }
}
