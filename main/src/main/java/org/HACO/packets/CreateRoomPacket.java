package org.HACO.packets;

import java.io.Serializable;
import java.util.Set;

public record CreateRoomPacket(String id, String name, Set<String> ids) implements P2PPacket {
}
