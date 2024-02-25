package org.HACO.packets;

public record MessagePacket(String chatId, Message msg) implements P2PPacket {
}
