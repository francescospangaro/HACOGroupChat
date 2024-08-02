package it.polimi.messages;

import java.io.Serializable;
import java.util.Map;
import java.util.stream.Collectors;

public sealed interface Message extends Serializable permits CloseMessage, StringMessage {
    String sender();

    Map<String, Integer> vectorClocks();

    String toString();

    /**
     * A prettier toString() method
     *
     * @return the message in pretty format
     */
    default String toDetailedString() {
        return STR."""
                \{toString()} \n
                \{vectorClocks().keySet().stream()
                .map((k) -> STR."\tUser: \{k} PID: \{vectorClocks().get(k)}")
                .collect(Collectors.joining(",\n", "Vector clocks: {\n", "\n}"))}
                """;
    }
}
