package org.HACO.packets;

public record DelayedMessagePacket(String chatId, String sender, Message msg, int delayedTime) implements P2PPacket {
}