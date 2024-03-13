package org.HACO.utility;

import java.io.Serializable;
import java.util.Map;
import java.util.stream.Collectors;

public record Message(String msg, Map<String, Integer> vectorClocks, String sender) implements Serializable {
    @Override
    public String toString(){
        return STR."""
                [\{this.sender}]: \{this.msg} \t
                \{this.vectorClocks.keySet().stream()
                .map((k) -> STR."   User:\{k} PID: \{this.vectorClocks.get(k)}")
                .collect(Collectors.joining(",\n", "Vector clocks: {\n", "}\n"))}
                """;
    }
}
