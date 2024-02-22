package org.HACO.packets;

import org.HACO.Message;

public record MessagePacket(String chatId, String sender, Message msg) implements P2PPacket {
}
