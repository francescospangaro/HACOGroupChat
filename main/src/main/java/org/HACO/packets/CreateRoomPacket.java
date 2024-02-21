package org.HACO.packets;

import java.io.Serializable;
import java.util.List;

public record CreateRoomPacket(String id, List<String> ids) implements Serializable {
}
