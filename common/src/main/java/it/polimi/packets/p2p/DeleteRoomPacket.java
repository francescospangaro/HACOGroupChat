package it.polimi.packets.p2p;

import java.util.UUID;

/**
 *
 * @param id of the chatroom to delete
 */
public record DeleteRoomPacket(UUID id) implements P2PPacket {
}