package it.polimi.packets;


import java.util.UUID;

public record DeleteRoomPacket(UUID id) implements P2PPacket {
}
