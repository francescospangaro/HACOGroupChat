package org.HACO.utility;

import java.io.Serializable;
import java.util.Map;
import java.util.stream.Collectors;

public record Message(String msg, Map<String, Integer> vectorClocks, String sender) implements Serializable {
    //Apparently in JList to format text you have to use HTML
    @Override
    public String toString(){
        return STR."""
        <html>
                [\{this.sender}]: \{this.msg} <br>
                \{this.vectorClocks.keySet().stream()
                .map((k) -> STR."<pre>   User:\{k} PID: \{this.vectorClocks.get(k)}</pre>")
                .collect(Collectors.joining(",", "Vector clocks: {<br>", "}"))}
                """;
    }
}
