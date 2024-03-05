package org.HACO.packets;

import java.io.Serializable;

public sealed interface SeqPacket extends Serializable permits AckPacket, SeqPacketImpl {
    long seqNum();
}
