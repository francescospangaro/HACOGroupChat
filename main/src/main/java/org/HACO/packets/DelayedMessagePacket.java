package org.HACO.packets;

public record DelayedMessagePacket(String chatId, Message msg, int delayedTime) implements P2PPacket {
}