package org.HACO.packets;

import java.io.Serializable;
import java.net.InetAddress;

public record UpdateIpPacket(String id, int port) implements Serializable {
}
