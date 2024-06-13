package it.polimi.packets.p2p;

import it.polimi.utility.Message;

import java.util.UUID;

/**
 *
 * @param chatId of the chatroom the message was sent to
 * @param msg contains of the message
 */
public record MessagePacket(UUID chatId, Message msg) implements P2PPacket {
}
