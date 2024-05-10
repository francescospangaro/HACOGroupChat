package it.polimi.packets;

import java.util.Set;
import java.util.UUID;

/**
 *
 * @param id of the newly created chatroom
 * @param name of the new chatroom
 * @param ids of all peers in the new chatroom
 */
public record CreateRoomPacket(UUID id, String name, Set<String> ids) implements P2PPacket {
}
