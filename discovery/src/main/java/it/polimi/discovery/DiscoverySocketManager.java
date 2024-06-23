package it.polimi.discovery;

import it.polimi.SocketManager;
import it.polimi.packets.SeqPacketImpl;
import it.polimi.packets.discovery.Discovery2PeerPacket;
import it.polimi.packets.discovery.Peer2DiscoveryPacket;
import it.polimi.packets.p2p.HelloPacket;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;

public class DiscoverySocketManager extends SocketManager {

    private final BlockingQueue<PacketAndSender<Peer2DiscoveryPacket>> inPacketQueue;


    /**
     * Create a socketManager without the recipient id: will receive an {@link HelloPacket} with it and the serverPort.
     *
     * @param myId     id of the local host
     * @param executor executor service
     * @throws IOException            if an error occurs during connection (or receiving {@link HelloPacket}
     * @throws InterruptedIOException if interrupted while waiting for the {@link HelloPacket}
     */
    public DiscoverySocketManager(String myId,
                                  ExecutorService executor,
                                  int port)
            throws IOException {
        this(myId, executor, port, DEFAULT_TIMEOUT);
    }

    public DiscoverySocketManager(String myId,
                                  ExecutorService executor,
                                  int port,
                                  int timeout)
            throws IOException {
        this(myId, executor, timeout, new DatagramSocket(port));
    }

    @VisibleForTesting
    DiscoverySocketManager(String myId,
                           ExecutorService executor,
                           int timeout,
                           DatagramSocket socket) {
        super(myId, executor, timeout, socket);
        this.inPacketQueue = new LinkedBlockingQueue<>();
        start();
    }

    @Override
    protected void handlePacket(SeqPacketImpl seqPacket, SocketAddress sender) throws IOException {
        switch (seqPacket.p()) {
            case Peer2DiscoveryPacket p2d -> inPacketQueue.add(new PacketAndSender<>(p2d, sender));
            default -> throw new IOException("Unexpected packet");
        }
    }


    /**
     * Send a packet and wait for an ack. This is a blocking method.
     * This method is thread-safe, only 1 thread at time can send packets.
     * The packet will be wrapped in a {@link SeqPacketImpl} to be sent
     *
     * @param packet packet to be sent
     * @throws IOException            if an error occurs during communication (i.e. ack not received)
     * @throws InterruptedIOException if interrupted while waiting for the ack
     */
    public void send(Discovery2PeerPacket packet, SocketAddress address) throws IOException {
        doSendAndWaitAck(packet, address);
    }

    public PacketAndSender<Peer2DiscoveryPacket> receive() throws IOException {
        try {
            return inPacketQueue.take();
        } catch (InterruptedException e) {
            throw (IOException) new InterruptedIOException("Failed to receive packet from peer").initCause(e);
        }
    }

}