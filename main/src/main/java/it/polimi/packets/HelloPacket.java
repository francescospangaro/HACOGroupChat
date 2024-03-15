package it.polimi.packets;

public record HelloPacket(String id, int serverPort) implements P2PPacket {
}
