package it.polimi.utility;

import java.io.Serializable;
import java.util.Map;
import java.util.stream.Collectors;

public record Message(String msg, Map<String, Integer> vectorClocks, String sender) implements Serializable {
    //Apparently in JList to format text you have to use HTML
    @Override
    public String toString() {
        return msg;
    }

    public String toDetailedString() {
        return STR."""
                \{this.msg} \n
                \{this.vectorClocks.keySet().stream()
                .map((k) -> STR."\tUser:\{k} PID: \{this.vectorClocks.get(k)}")
                .collect(Collectors.joining(",\n", "Vector clocks: {\n", "\n}"))}
                """;
    }
}
