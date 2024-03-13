package org.HACO.packets;

import org.HACO.utility.Message;

import java.util.UUID;

public record DelayedMessagePacket(UUID chatId, Message msg, int delayedTime) implements P2PPacket {
}