package it.polimi.packets;

/**
 * First connection packet.
 *
 * @param id of the user sending it
 * @param serverPort
 */
public record HelloPacket(String id, int serverPort) implements P2PPacket {
}
