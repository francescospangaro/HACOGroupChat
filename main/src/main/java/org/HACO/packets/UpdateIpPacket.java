package org.HACO.packets;

import java.io.Serializable;

public record UpdateIpPacket(String id, int port) implements Serializable {
}
