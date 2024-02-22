package org.HACO.packets;

import java.io.Serializable;

public sealed interface P2PPacket extends Serializable permits CreateRoomPacket, DeleteRoomPacket, HelloPacket, MessagePacket {
}
