package it.polimi.packets;

import java.io.Serializable;

public sealed interface P2PPacket extends Serializable permits CreateRoomPacket, DelayedMessagePacket, DeleteRoomPacket, HelloPacket, MessagePacket {
}
