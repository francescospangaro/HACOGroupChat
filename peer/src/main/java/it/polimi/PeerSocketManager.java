package it.polimi;

import it.polimi.packets.SeqPacketImpl;
import it.polimi.packets.discovery.Discovery2PeerPacket;
import it.polimi.packets.discovery.Peer2DiscoveryPacket;
import it.polimi.packets.p2p.HelloPacket;
import it.polimi.packets.p2p.P2PPacket;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;

public class PeerSocketManager extends SocketManager {

    private final SocketAddress discoveryAddress;

    private final BlockingQueue<PacketAndSender<P2PPacket>> inPacketQueue_peer;
    private final BlockingQueue<Discovery2PeerPacket> inPacketQueue_discovery;

    /**
     * Create a socketManager without the recipient id: will receive an {@link HelloPacket} with it and the serverPort.
     *
     * @param myId     id of the local host
     * @param executor executor service
     * @throws IOException            if an error occurs during connection (or receiving {@link HelloPacket}
     * @throws InterruptedIOException if interrupted while waiting for the {@link HelloPacket}
     */
    public PeerSocketManager(String myId,
                             ExecutorService executor,
                             SocketAddress discoveryAddress,
                             int port)
            throws IOException {
        this(myId, executor, discoveryAddress, port, DEFAULT_TIMEOUT);
    }

    public PeerSocketManager(String myId,
                             ExecutorService executor,
                             SocketAddress discoveryAddress,
                             int port,
                             int timeout)
            throws IOException {
        this(myId, executor, discoveryAddress, timeout, new DatagramSocket(port));
    }

    @VisibleForTesting
    PeerSocketManager(String myId,
                      ExecutorService executor,
                      SocketAddress discoveryAddress,
                      int timeout,
                      DatagramSocket socket) {
        super(myId, executor, timeout, socket);
        this.discoveryAddress = discoveryAddress;
        this.inPacketQueue_discovery = new LinkedBlockingQueue<>();
        this.inPacketQueue_peer = new LinkedBlockingQueue<>();
        start();
    }

    @Override
    protected void handlePacket(SeqPacketImpl seqPacket, SocketAddress sender) throws IOException {
        switch (seqPacket.p()) {
            case P2PPacket p2p -> inPacketQueue_peer.add(new PacketAndSender<>(p2p, sender));
            case Discovery2PeerPacket d2p -> inPacketQueue_discovery.add(d2p);
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
    public void send(P2PPacket packet, SocketAddress address) throws IOException {
        doSendAndWaitAck(packet, address);
    }

    /**
     * Send a packet to the discovery server and wait for an ack. This is a blocking method.
     * This method is thread-safe, only 1 thread at time can send packets.
     * The packet will be wrapped in a {@link SeqPacketImpl} to be sent
     *
     * @param packet packet to be sent
     * @throws IOException            if an error occurs during communication (i.e. ack not received)
     * @throws InterruptedIOException if interrupted while waiting for the ack
     */
    public void sendToDiscovery(Peer2DiscoveryPacket packet) throws IOException {
        doSendAndWaitAck(packet, discoveryAddress);
    }

    public PacketAndSender<P2PPacket> receiveFromPeer() throws IOException {
        try {
            return inPacketQueue_peer.take();
        } catch (InterruptedException e) {
            throw (IOException) new InterruptedIOException("Failed to receive packet from peer").initCause(e);
        }
    }

    public Discovery2PeerPacket receiveFromDiscovery() throws IOException {
        try {
            return inPacketQueue_discovery.take();
        } catch (InterruptedException e) {
            throw (IOException) new InterruptedIOException("Failed to receive packet from peer").initCause(e);
        }
    }

}