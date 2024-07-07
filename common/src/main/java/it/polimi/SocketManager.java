package it.polimi;

import it.polimi.packets.AckPacket;
import it.polimi.packets.Packet;
import it.polimi.packets.SeqPacket;
import it.polimi.packets.SeqPacketImpl;
import it.polimi.packets.p2p.HelloPacket;
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

public abstract class SocketManager implements Closeable {
    static final Logger LOGGER = LoggerFactory.getLogger(SocketManager.class);
    protected static final String CLOSE_EX_MSG = "Socket was closed";
    private static final int BUFF_SIZE = 65000;

    private record QueuedOutput(SeqPacket packet, CompletableFuture<Void> sent, SocketAddress address) {
    }

    public record PacketAndSender<T extends Packet>(T packet, SocketAddress sender) {
    }

    private final BlockingQueue<QueuedOutput> outPacketQueue;
    private final Map<Long, CompletableFuture<Void>> waitingAcks;
    protected static final int DEFAULT_TIMEOUT = 5000;
    private final int timeout;
    private final DatagramSocket socket;
    private ObjectOutputStream oos;
    private ByteArrayOutputStream baos;
    private ObjectInputStream ois;
    private ByteArrayInputStream bais;
    private final byte[] buff;

    private final AtomicLong seq = new AtomicLong();
    final String myId;
    private volatile boolean closed;

    private Future<?> recvTask;
    protected volatile boolean isRecvTaskRunning;
    private Future<?> sendTask;
    private volatile boolean canSendNewPackets;
    private final ExecutorService executor;
    private final CompletableFuture<Void> sendTaskFinish, recvTaskFinish;

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
                         int port)
            throws IOException {
        this(myId, executor, port, DEFAULT_TIMEOUT);
    }

    public SocketManager(String myId,
                         ExecutorService executor,
                         int port,
                         int timeout)
            throws IOException {
        this(myId, executor, timeout, new DatagramSocket(port));
    }

    @VisibleForTesting
    protected SocketManager(String myId,
                            ExecutorService executor,
                            int timeout,
                            DatagramSocket socket) {
        this.timeout = timeout;
        this.socket = socket;
        this.myId = myId;
        sendTaskFinish = new CompletableFuture<>();
        recvTaskFinish = new CompletableFuture<>();

        buff = new byte[BUFF_SIZE];

        outPacketQueue = new LinkedBlockingQueue<>();
        waitingAcks = new ConcurrentHashMap<>();

        this.closed = false;
        this.executor = executor;
    }

    protected void start() {
        canSendNewPackets = true;
        recvTask = executor.submit(this::readLoop);
        sendTask = executor.submit(this::writeLoop);
        isRecvTaskRunning = true;
    }

    private void readLoop() {
        try {
            SeqPacket p;
            DatagramPacket dp = new DatagramPacket(buff, buff.length);
            do {
                try {
                    LOGGER.trace(STR."[\{myId}]: Waiting packet...");
                    socket.receive(dp);
                    bais = new ByteArrayInputStream(buff);
                    ois = new ObjectInputStream(bais);
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
                            throw new IOException(STR."Received unexpected resetFlushObj \{resetFlushObj}");
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
                        var ackPromise = waitingAcks.remove(ack.seqNum());
                        if (ackPromise != null)
                            ackPromise.complete(null);
                        else
                            LOGGER.warn(STR."[\{myId}]: Received unexpected ack \{ack}. Ignored.");
                    }
                    case SeqPacketImpl seqPacket -> {
                        LOGGER.info(STR."[\{this.myId}] Received packet: \{seqPacket}");
                        sendAck(seqPacket.seqNum(), dp.getSocketAddress());
                        handlePacket(seqPacket, dp.getSocketAddress());
                    }
                }
            } while (!socket.isClosed() && !Thread.currentThread().isInterrupted());

        } catch (IOException e) {
            // If it's an interrupted exception or the interruption flag was set
            if (e instanceof InterruptedIOException
                    || e instanceof ClosedByInterruptException
                    || Thread.currentThread().isInterrupted())
                return;

            LOGGER.error(STR."[\{myId}]: Failed to read packet, closing...", e);
            close();
        } catch (Throwable t) {
            LOGGER.error(STR."[\{myId}]: Unexpected exception in read loop", t);
            throw t;
        } finally {
            this.isRecvTaskRunning = false;
            recvTaskFinish.complete(null);
        }
    }

    protected abstract void handlePacket(SeqPacketImpl p, SocketAddress sender) throws IOException;

    private void writeLoop() {
        QueuedOutput p = null;
        try {
            do {
                p = outPacketQueue.take();

                try {
                    baos = new ByteArrayOutputStream(BUFF_SIZE);
                    oos = new ObjectOutputStream(baos);

                    oos.writeObject(p.packet);
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
                    LOGGER.trace(STR."[\{myId}]: Sent " + p);
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
            // If the interruption flag was set, we got interrupted by close, so it's expected
            if (Thread.currentThread().isInterrupted())
                return;

            LOGGER.error(STR."[\{myId}]: Failed to write packet {}, closing...", p, e);
            close();
        } finally {
            // Signal to everybody who is waiting that the socket got closed
            var toCancel = new ArrayList<>(outPacketQueue);
            outPacketQueue.removeAll(toCancel);
            final IOException closeEx = new IOException(CLOSE_EX_MSG);
            toCancel.forEach(q -> q.sent.completeExceptionally(closeEx));
            sendTaskFinish.complete(null);
        }
    }

    protected void doSendAndWaitAck(Packet packet, SocketAddress address) throws IOException {
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
        if (!canSendNewPackets)
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

    public boolean isClosed() {
        return closed;
    }

    /**
     * Close the socket and stop reading packets
     */
    @Override
    public void close() {
        closed = true;
        canSendNewPackets = false;

        outPacketQueue.forEach(p -> {
            try {
                LOGGER.trace(STR."[\{myId}]: Waiting for \{p} before closing the socket");
                p.sent.get(timeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                LOGGER.error("Socket closed with enqueued packets", e);
            }
        });

        recvTask.cancel(true);
        sendTask.cancel(true);

        try {
            recvTaskFinish.get(500, TimeUnit.MILLISECONDS);
            sendTaskFinish.get(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new UncheckedIOException((IOException) new InterruptedIOException().initCause(e));
        } catch (ExecutionException e) {
            throw new UncheckedIOException(new IOException(e.getCause()));
        } catch (TimeoutException e) {
            throw new UncheckedIOException(new IOException(e));
        }

        socket.close();
        LOGGER.info(STR."[\{myId}]: SocketManager closed.");
    }

}
