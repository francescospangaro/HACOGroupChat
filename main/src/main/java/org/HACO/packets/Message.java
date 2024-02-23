package org.HACO.packets;

import java.io.Serializable;
import java.util.List;

public record Message(String msg, List<Integer> vectorClocks, String sender) implements Serializable {
}
