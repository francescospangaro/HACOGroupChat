package org.HACO.packets;

import java.io.Serializable;
import java.util.Map;

public record Message(String msg, Map<String, Integer> vectorClocks, String sender) implements Serializable {
}
