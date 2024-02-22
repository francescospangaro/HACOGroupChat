package org.HACO.packets.discovery;

public record ByePacket(String id) implements Peer2DiscoveryPacket {
}
