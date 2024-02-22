package org.HACO.packets.discovery;

public record UpdateIpPacket(String id, int port) implements Peer2DiscoveryPacket {
}
