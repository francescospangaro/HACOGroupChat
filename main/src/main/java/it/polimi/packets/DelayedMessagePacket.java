package it.polimi.packets;

import it.polimi.utility.Message;

import java.util.UUID;

/**
 * Since in LAN testing, testing of the packet delay was hard to do via hardware,
 * we opted for a software approach, by letting peers choose how much time should the
 * message be delivered after it being sent.
 *
 * @param chatId contains the id of the chatroom this message was sent to
 * @param msg contains the message
 * @param delayedTime contains the delivery delay of the message
 */
public record DelayedMessagePacket(UUID chatId, Message msg, int delayedTime) implements P2PPacket {
}