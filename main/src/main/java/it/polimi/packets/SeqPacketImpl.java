package it.polimi.packets;

public record SeqPacketImpl(P2PPacket p, long seqNum) implements SeqPacket {
}
