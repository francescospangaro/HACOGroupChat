package org.HACO.packets;

import org.HACO.utility.Message;

import java.util.UUID;

public record MessagePacket(UUID chatId, Message msg) implements P2PPacket {
}
