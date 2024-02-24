package org.HACO.packets;

public record DelayedMessagePacket(String chatId, String sender, Message msg) implements P2PPacket {
}