package it.polimi.packets.p2p;

import java.util.Map;
import java.util.UUID;

/**
 *
 * @param id of the chatroom to delete
 */
public record DeleteRoomPacket(String sender, UUID id, Map<String, Integer> vectorClocks) implements P2PPacket {
}
