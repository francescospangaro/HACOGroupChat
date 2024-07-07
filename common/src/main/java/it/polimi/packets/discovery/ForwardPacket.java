package it.polimi.packets.discovery;

import it.polimi.packets.p2p.P2PPacket;

import java.util.Queue;

public record ForwardPacket(Queue<P2PPacket> packets, String senderId,
                            String recipientId) implements Peer2DiscoveryPacket {
}
