package it.polimi.packets.p2p;

import it.polimi.packets.ByePacket;
import it.polimi.packets.Packet;

public sealed interface P2PPacket extends Packet permits ByePacket, CreateRoomPacket, DelayedMessagePacket, DeleteRoomPacket, HelloPacket, MessagePacket, NewPeer {
}
