package org.HACO.packets;

public record AckPacket(long seqNum) implements SeqPacket {
}
