package org.HACO.packets;


import java.util.UUID;

public record DeleteRoomPacket(UUID id) implements P2PPacket {
}
