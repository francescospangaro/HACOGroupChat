package it.polimi;

import it.polimi.packets.AckPacket;
import it.polimi.packets.Packet;
import it.polimi.packets.SeqPacket;
import it.polimi.packets.SeqPacketImpl;
import it.polimi.packets.discovery.Discovery2PeerPacket;
import it.polimi.packets.discovery.Peer2DiscoveryPacket;
import it.polimi.packets.p2p.HelloPacket;
import it.polimi.packets.p2p.NewPeer;
import it.polimi.packets.p2p.P2PPacket;
import org.jetbrains.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.nio.channels.ClosedByInterruptException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class SocketManager implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(SocketManager.class);
    static final String CLOSE_EX_MSG = "Socket was closed";

    private final SocketAddress discoveryAddress;

    private record QueuedOutput(SeqPacket packet, CompletableFuture<Void> sent, SocketAddress address) {
    }

    private final BlockingQueue<QueuedOutput> outPacketQueue;
    private final Map<Long, CompletableFuture<Void>> waitingAcks;
    private final BlockingQueue<P2PPacket> inPacketQueue_peer;
    private final BlockingQueue<Discovery2PeerPacket> inPacketQueue_discovery;
    static final int DEFAULT_TIMEOUT = 5000;
    private final int timeout;
    private final DatagramSocket socket;
    private final ObjectOutputStream oos;
    final ByteArrayOutputStream baos;
    private final ObjectInputStream ois;
    final ByteArrayInputStream bais;
    private final byte[] buff;

    private final AtomicLong seq = new AtomicLong();
    private final String myId;
    private volatile boolean closed;

    private final Future<?> recvTask;
    private volatile boolean isRecvTaskRunning;
    private final Future<?> sendTask;
    private volatile boolean isSendTaskRunning;

    /**
     * Create a socketManager without the recipient id: will receive an {@link HelloPacket} with it and the serverPort.
     *
     * @param myId     id of the local host
     * @param executor executor service
     * @throws IOException            if an error occurs during connection (or receiving {@link HelloPacket}
     * @throws InterruptedIOException if interrupted while waiting for the {@link HelloPacket}
     */
    public SocketManager(String myId,
                         ExecutorService executor,
                         SocketAddress discoveryAddress,
                         int port)
            throws IOException {
        this(myId, executor, discoveryAddress, port, DEFAULT_TIMEOUT);
    }

    public SocketManager(String myId,
                         ExecutorService executor,
                         SocketAddress discoveryAddress,
                         int port,
                         int timeout)
            throws IOException {
        this(myId, executor, discoveryAddress, port, timeout, new DatagramSocket(port));
    }

    @VisibleForTesting
    SocketManager(String myId,
                         ExecutorService executor,
                         SocketAddress discoveryAddress,
                         int port,
                         int timeout,
                         DatagramSocket socket)
            throws IOException {
        this.timeout = timeout;
        this.socket = socket;
        this.myId = myId;
        this.discoveryAddress = discoveryAddress;

        baos = new ByteArrayOutputStream(6400);
        oos = new ObjectOutputStream(baos);
        buff = new byte[6400];
        bais = new ByteArrayInputStream(buff);
        ois = new ObjectInputStream(bais);

        outPacketQueue = new LinkedBlockingQueue<>();
        waitingAcks = new ConcurrentHashMap<>();
        inPacketQueue_peer = new LinkedBlockingQueue<>();
        inPacketQueue_discovery = new LinkedBlockingQueue<>();

        this.closed = false;

        recvTask = executor.submit(this::readLoop);
        sendTask = executor.submit(this::writeLoop);
    }

    private void readLoop() {
        try {
            SeqPacket p;
            DatagramPacket dp = new DatagramPacket(buff, buff.length);
            do {
                try {
                    LOGGER.trace(STR."[\{myId}]: Waiting packet...");
                    socket.receive(dp);
                    p = (SeqPacket) ois.readObject();


                    // Also read a null-object to make sure we received the reset from the corresponding ObjectOutputStream.
                    // In particular, we need to read an object cause ObjectInputStream only handles reset requests in
                    // readObject/readUnshared, not in readByte.
                    // We use a null reference 'cause it's the smallest object I can think of sending.
                    // By doing this, we make sure that by the time the current packet we are reading is handled, the
                    // other side has already flushed out all its data related to this packet (including the reset req),
                    // therefore we can (and some packets do) close the connection and the other side could do the same.
                    Object resetFlushObj;
                    try {
                        resetFlushObj = ois.readUnshared();
                        if (resetFlushObj != null)
                            throw new IOException("Received unexpected resetFlushObj " + resetFlushObj);
                    } catch (ClassNotFoundException | ClassCastException ex) {
                        throw new IOException("Received unexpected resetFlushObj", ex);
                    }
                } catch (ClassNotFoundException | ClassCastException ex) {
                    LOGGER.error(STR."[\{myId}] Received unexpected input packet", ex);
                    continue;
                }

                switch (p) {
                    case AckPacket ack -> {
                        LOGGER.trace(STR."[\{this.myId}] Received ack \{ack}");
//                        if (ack.seqNum() != seq.get() - 1)
//                            throw new IllegalStateException("This show never happen(?)");
                        waitingAcks.remove(ack.seqNum()).complete(null);
                    }
                    case SeqPacketImpl seqPacket -> {
                        LOGGER.info(STR."[\{this.myId}] Received packet: \{p}");
                        sendAck(seqPacket.seqNum(), dp.getSocketAddress());
                        switch (seqPacket.p()) {
                            case P2PPacket p2p -> {
                                if (p2p instanceof HelloPacket helloPacket)
                                    inPacketQueue_peer.add(new NewPeer(helloPacket.id(), dp.getSocketAddress()));
                                else
                                    inPacketQueue_peer.add(p2p);
                            }
                            case Discovery2PeerPacket d2p -> inPacketQueue_discovery.add(d2p);
                            default -> throw new IOException("Unexpected packet");
                        }
                    }
                }
            } while (!socket.isClosed() && !Thread.currentThread().isInterrupted());

        } catch (IOException e) {
//            if (!closed)
//                onClose.accept(otherId.resultNow(), e);
        } catch (Throwable t) {
            LOGGER.error(STR."[\{myId}]: Unexpected exception in read loop", t);
            throw t;
        }
    }

    private void writeLoop() {
        QueuedOutput p = null;
        try {
            do {
                p = outPacketQueue.take();

                try {
                    oos.writeObject(p);
                    // Fix memory leak, as ObjectOutputStream maintains a reference to anything
                    // you write into it, in order to implement the reference sharing mechanism.
                    // Since we don't need to share references past a single object graph, we
                    // can just reset the references after each time we write.
                    // see https://bugs.openjdk.org/browse/JDK-6525563
                    oos.reset();
                    // Write a null reference to use as a marker that the reset request was flushed
                    // and received with the packet by the other side.
                    // See the readLoop for additional details
                    oos.writeUnshared(null);
                    oos.flush();

                    socket.send(new DatagramPacket(baos.toByteArray(), baos.size(), p.address));
//                    log("Sent " + p);
                    p.sent.complete(null);
                } catch (InvalidClassException | NotSerializableException ex) {
                    p.sent.completeExceptionally(ex);
                } catch (Throwable ex) {
                    p.sent.completeExceptionally(ex);
                    throw ex;
                }
            } while (!Thread.currentThread().isInterrupted());
        } catch (InterruptedIOException | ClosedByInterruptException | InterruptedException e) {
            // Go on, interruption is expected
        } catch (IOException e) {
            // Set it here (other than in the finally-block) as we need it set before the close call
            this.isSendTaskRunning = false;

            // If the interruption flag was set, we got interrupted by close, so it's expected
            if (Thread.currentThread().isInterrupted())
                return;

            LOGGER.error("Failed to write packet {}, closing...", p, e);
            close();
        } finally {
            this.isSendTaskRunning = false;

            // Signal to everybody who is waiting that the socket got closed
            var toCancel = new ArrayList<>(outPacketQueue);
            outPacketQueue.removeAll(toCancel);
            final IOException closeEx = new IOException(CLOSE_EX_MSG);
            toCancel.forEach(q -> q.sent.completeExceptionally(closeEx));
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

    private void doSendAndWaitAck(Packet packet, SocketAddress address) throws IOException {
        long seqN = seq.getAndIncrement();
        CompletableFuture<Void> ackPromise = new CompletableFuture<>();
        waitingAcks.put(seqN, ackPromise);
        doSend(new SeqPacketImpl(packet, seqN), address);
        try {
            ackPromise.get(timeout, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        } catch (TimeoutException e) {
            throw new IOException(e);
        } catch (InterruptedException e) {
            throw new InterruptedIOException(STR."Interrupted while waiting for the ack: \{e.getMessage()}");
        }
    }

    private void doSend(SeqPacket packet, SocketAddress address) throws IOException {
        if (!isSendTaskRunning)
            throw new IOException(CLOSE_EX_MSG);

        CompletableFuture<Void> sentPromise = new CompletableFuture<>();
        outPacketQueue.add(new QueuedOutput(packet, sentPromise, address));
        try {
            sentPromise.get(timeout, TimeUnit.MILLISECONDS);
            LOGGER.info(STR."[\{this.myId}] Sent \{packet}");
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        } catch (TimeoutException e) {
            throw new IOException(e);
        } catch (InterruptedException e) {
            throw new InterruptedIOException(STR."Interrupted while waiting for the ack: \{e.getMessage()}");
        }
    }

    private void sendAck(long seq, SocketAddress addr) throws IOException {
        doSend(new AckPacket(seq), addr);
        LOGGER.trace(STR."[\{this.myId}] Sent ack for packet number \{seq}");
    }

    public P2PPacket receiveFromPeer() throws IOException {
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

    /**
     * Close the socket and stop reading packets
     */
    @Override
    public void close() {
        closed = true;
        recvTask.cancel(true);
        sendTask.cancel(true);
        socket.close();
    }

}