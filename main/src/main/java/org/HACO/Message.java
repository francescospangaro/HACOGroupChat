package org.HACO;

import java.io.Serializable;
import java.util.List;

public record Message(String msg, List<Integer> vectorClocks) implements Serializable {
}
