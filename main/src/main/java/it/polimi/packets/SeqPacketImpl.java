package it.polimi.packets;

public record SeqPacketImpl(Packet p, long seqNum) implements SeqPacket {
}
