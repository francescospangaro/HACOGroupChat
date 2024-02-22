package org.HACO.packets;

import java.io.Serializable;

public record DeleteRoomPacket(String id) implements P2PPacket {
}
