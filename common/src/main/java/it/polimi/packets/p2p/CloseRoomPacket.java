package it.polimi.packets.p2p;

import it.polimi.messages.CloseMessage;

import java.util.UUID;

/**
 * @param closeMessage close message
 */
public record CloseRoomPacket(UUID chatId, CloseMessage closeMessage) implements P2PPacket {
}
