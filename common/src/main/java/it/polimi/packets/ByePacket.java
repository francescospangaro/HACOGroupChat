package it.polimi.packets;

import it.polimi.packets.discovery.Peer2DiscoveryPacket;
import it.polimi.packets.p2p.P2PPacket;

public record ByePacket(String id) implements Peer2DiscoveryPacket, P2PPacket {
}
