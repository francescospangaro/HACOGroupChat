package org.HACO.packets;

import java.util.Set;
import java.util.UUID;

public record CreateRoomPacket(UUID id, String name, Set<String> ids) implements P2PPacket {
}
