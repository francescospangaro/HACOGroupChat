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
    //Apparently in JList to format text you have to use HTML
    @Override
    public String toString() {
        return msg;
    }

    /**
     * A prettier toString() method
     * @return the message in pretty format
     */
    public String toDetailedString() {
        return STR."""
                \{this.msg} \n
                \{this.vectorClocks.keySet().stream()
                .map((k) -> STR."\tUser:\{k} PID: \{this.vectorClocks.get(k)}")
                .collect(Collectors.joining(",\n", "Vector clocks: {\n", "\n}"))}
                """;
    }
}
