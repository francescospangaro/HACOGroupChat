package it.polimi.packets.discovery;

public record ByePacket(String id) implements Peer2DiscoveryPacket {
}
