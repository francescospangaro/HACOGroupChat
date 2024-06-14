package it.polimi.packets.p2p;

/**
 * First connection packet.
 *
 * @param id of the user sending it
 */
public record HelloPacket(String id) implements P2PPacket {
}
