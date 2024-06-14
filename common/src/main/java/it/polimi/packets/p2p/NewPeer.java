package it.polimi.packets.p2p;

import java.net.SocketAddress;

public record NewPeer(String id, SocketAddress addr) implements P2PPacket {
}
