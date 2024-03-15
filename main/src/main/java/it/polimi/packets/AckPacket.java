package it.polimi.packets;

public record AckPacket(long seqNum) implements SeqPacket {
}
