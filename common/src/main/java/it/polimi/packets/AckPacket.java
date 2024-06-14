package it.polimi.packets;

/**
 * Self documented
 * @param seqNum
 */
public record AckPacket(long seqNum) implements SeqPacket {
}
