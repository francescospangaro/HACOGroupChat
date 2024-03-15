package it.polimi.packets.discovery;

import java.io.Serializable;

public sealed interface Discovery2PeerPacket extends Serializable permits IPsPacket {
}
