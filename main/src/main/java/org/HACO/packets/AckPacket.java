package org.HACO.packets;

import java.io.Serializable;

public record AckPacket(long seqNum) implements SeqPacket {
}
