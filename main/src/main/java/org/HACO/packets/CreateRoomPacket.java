package org.HACO.packets;

import java.io.Serializable;
import java.util.Set;

public record CreateRoomPacket(String id, Set<String> ids) implements Serializable {
}
