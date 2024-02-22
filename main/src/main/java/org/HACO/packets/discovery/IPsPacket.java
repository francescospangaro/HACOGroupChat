package org.HACO.packets.discovery;

import java.net.SocketAddress;
import java.util.Map;

public record IPsPacket(Map<String, SocketAddress> ips) implements Discovery2PeerPacket {
}
