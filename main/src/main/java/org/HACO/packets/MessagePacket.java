package org.HACO.packets;

public record MessagePacket(String chatId, String sender, Message msg) implements P2PPacket {
}
