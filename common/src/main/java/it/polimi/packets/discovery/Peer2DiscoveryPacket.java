package it.polimi.packets.discovery;

import it.polimi.packets.ByePacket;
import it.polimi.packets.Packet;

public sealed interface Peer2DiscoveryPacket extends Packet permits ByePacket, ForwardPacket, UpdateIpPacket {
}
