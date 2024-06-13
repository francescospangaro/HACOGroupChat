package it.polimi.packets;

import it.polimi.packets.discovery.Discovery2PeerPacket;
import it.polimi.packets.discovery.Peer2DiscoveryPacket;
import it.polimi.packets.p2p.P2PPacket;

import java.io.Serializable;

public sealed interface Packet extends Serializable permits Discovery2PeerPacket, Peer2DiscoveryPacket, P2PPacket {
}
