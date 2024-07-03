package it.polimi.packets.p2p;

import it.polimi.messages.DeleteMessage;

import java.util.UUID;

/**
 * @param deleteMessage delete message
 */
public record DeleteRoomPacket(UUID chatId, DeleteMessage deleteMessage) implements P2PPacket {
}
