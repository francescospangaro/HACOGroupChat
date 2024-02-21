package org.HACO.packets;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;

public record IPsPacket(Map<String, SocketAddress> ips) implements Serializable {
}
