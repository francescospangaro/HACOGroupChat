package it.polimi.packets.discovery;

import it.polimi.packets.p2p.P2PPacket;

import java.net.SocketAddress;
import java.util.Queue;

public record PacketQueue(String id, SocketAddress addr, Queue<P2PPacket> packets) implements Discovery2PeerPacket {
}
