package it.polimi.packets;

import it.polimi.utility.Message;

import java.util.UUID;

public record DelayedMessagePacket(UUID chatId, Message msg, int delayedTime) implements P2PPacket {
}