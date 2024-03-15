package it.polimi.packets;

import java.io.Serializable;

public sealed interface SeqPacket extends Serializable permits AckPacket, SeqPacketImpl {
    long seqNum();
}
