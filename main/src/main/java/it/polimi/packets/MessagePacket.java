package it.polimi.packets;

import it.polimi.utility.Message;

import java.util.UUID;

public record MessagePacket(UUID chatId, Message msg) implements P2PPacket {
}
