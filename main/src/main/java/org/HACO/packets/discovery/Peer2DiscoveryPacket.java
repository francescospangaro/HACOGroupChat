package org.HACO.packets.discovery;

import java.io.Serializable;

public sealed interface Peer2DiscoveryPacket extends Serializable permits ByePacket, UpdateIpPacket {
}
