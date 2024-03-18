package it.polimi;

import it.polimi.packets.*;
import org.jetbrains.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class SocketManager implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(SocketManager.class);

    private static final int DEFAULT_TIMEOUT = 5000;
    private final int timeout;
    private final Socket socket;
    private final ObjectOutputStream oos;
    private final ObjectInputStream ois;

    private final Future<?> recvTask;
    private final AtomicLong seq = new AtomicLong();
    private final String myId;
    private final Consumer<P2PPacket> inPacketConsumer;
    private final BiConsumer<String, Throwable> onClose;
    private final CompletableFuture<String> otherId;
    private final CompletableFuture<Integer> serverPort;
    private volatile CompletableFuture<Void> ackPromise;
    private final Lock writeLock, sendLock;
    private volatile boolean closed;

    /**
     * Create a socketManager without the recipient id: will receive an {@link HelloPacket} with it and the serverPort.
     *
     * @param myId             id of the local host
     * @param executor         executor service
     * @param socket           socket
     * @param inPacketConsumer consumer that accept all incoming packets
     * @param onClose          hook to be called when the socket got closed
     * @throws IOException            if an error occurs during connection (or receiving {@link HelloPacket}
     * @throws InterruptedIOException if interrupted while waiting for the {@link HelloPacket}
     */
    public SocketManager(String myId,
                         ExecutorService executor,
                         Socket socket,
                         Consumer<P2PPacket> inPacketConsumer,
                         BiConsumer<String, Throwable> onClose)
            throws IOException {
        this(myId, executor, socket, inPacketConsumer, onClose, DEFAULT_TIMEOUT);
    }

    /**
     * Create a socketManager with the recipient id: sends an {@link HelloPacket} with it and the serverPort.
     *
     * @param myId             id of the local host
     * @param serverPort       port on which this peer is listening for new connection
     * @param otherId          id of the recipient
     * @param executor         executor service
     * @param socket           socket
     * @param inPacketConsumer consumer that accept all incoming packets
     * @param onClose          hook to be called when the socket got closed
     * @throws IOException            if an error occurs during connection (or sending {@link HelloPacket}
     * @throws InterruptedIOException if interrupted while sending the {@link HelloPacket}
     * @throws NullPointerException   if otherId is null
     */
    public SocketManager(String myId,
                         int serverPort,
                         String otherId,
                         ExecutorService executor,
                         Socket socket,
                         Consumer<P2PPacket> inPacketConsumer,
                         BiConsumer<String, Throwable> onClose)
            throws IOException {
        this(myId, serverPort, otherId, executor, socket, inPacketConsumer, onClose, DEFAULT_TIMEOUT);
    }

    @VisibleForTesting
    SocketManager(String myId,
                  int serverPort,
                  String otherId,
                  ExecutorService executor,
                  Socket socket,
                  Consumer<P2PPacket> inPacketConsumer,
                  BiConsumer<String, Throwable> onClose,
                  int timeout)
            throws IOException {
        this(myId, executor, socket, socket.getInputStream(), socket.getOutputStream(), inPacketConsumer, onClose, timeout);

        if (otherId == null)
            throw new NullPointerException();

        this.otherId.complete(otherId);
        this.serverPort.complete(serverPort);
        send(new HelloPacket(myId, serverPort));
    }


    @VisibleForTesting
    SocketManager(String myId,
                  ExecutorService executor,
                  Socket socket,
                  Consumer<P2PPacket> inPacketConsumer,
                  BiConsumer<String, Throwable> onClose,
                  int timeout)
            throws IOException {
        this(myId, executor, socket, socket.getInputStream(), socket.getOutputStream(), inPacketConsumer, onClose, timeout);

        try {
            this.otherId.get(timeout, TimeUnit.MILLISECONDS);
            this.serverPort.get(timeout, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        } catch (TimeoutException e) {
            throw new IOException(e);
        } catch (InterruptedException e) {
            throw new InterruptedIOException("Interrupted while waiting for the recipient id: " + e.getMessage());
        }
    }

    private SocketManager(String myId,
                          ExecutorService executor,
                          Socket socket,
                          InputStream is,
                          OutputStream os,
                          Consumer<P2PPacket> inPacketConsumer,
                          BiConsumer<String, Throwable> onClose,
                          int timeout)
            throws IOException {
        this.timeout = timeout;
        this.socket = socket;
        this.myId = myId;
        this.oos = os instanceof ObjectOutputStream oos ? oos : new ObjectOutputStream(os);
        this.ois = is instanceof ObjectInputStream ois ? ois : new ObjectInputStream(is);
        this.otherId = new CompletableFuture<>();
        this.serverPort = new CompletableFuture<>();
        this.writeLock = new ReentrantLock();
        this.sendLock = new ReentrantLock();
        this.closed = false;

        this.inPacketConsumer = inPacketConsumer;
        this.onClose = onClose;
        recvTask = executor.submit(this::readLoop);
    }

    private void readLoop() {
        try {
            do {
                SeqPacket p;
                try {
                    p = (SeqPacket) ois.readObject();
                } catch (ClassNotFoundException | ClassCastException ex) {
                    LOGGER.error(STR."[\{myId}] Received unexpected input packet", ex);
                    continue;
                }


                switch (p) {
                    case AckPacket ack -> {
                        LOGGER.trace(STR."[\{this.myId} Received ack \{ack}");
                        if (ack.seqNum() != seq.get() - 1)
                            throw new IllegalStateException("This show never happen(?)");
                        ackPromise.complete(null);
                    }
                    case SeqPacketImpl seqPacket -> {
                        LOGGER.info(STR."[\{this.myId} Received packet: \{p}");
                        sendAck(seqPacket.seqNum());
                        if (seqPacket.p() instanceof HelloPacket helloPacket) {
                            otherId.complete(helloPacket.id());
                            serverPort.complete(helloPacket.serverPort());
                        } else {
                            inPacketConsumer.accept(seqPacket.p());
                        }
                    }
                }
            } while (!socket.isClosed() && !Thread.currentThread().isInterrupted());

        } catch (IOException e) {
            if (!closed)
                onClose.accept(otherId.resultNow(), e);
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
    public void send(P2PPacket packet) throws IOException {
        try {
            sendLock.lock();
            long seqN = seq.getAndIncrement();
            ackPromise = new CompletableFuture<>();

            try {
                writeLock.lock();
                oos.writeObject(new SeqPacketImpl(packet, seqN));
                oos.flush();
            } finally {
                writeLock.unlock();
            }

            LOGGER.info(STR."[\{this.myId} Sent \{packet}");

            try {
                ackPromise.get(timeout, TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                throw new IOException(e.getCause());
            } catch (TimeoutException e) {
                throw new IOException(e);
            } catch (InterruptedException e) {
                throw new InterruptedIOException("Interrupted while waiting for the ack: " + e.getMessage());
            }
        } finally {
            sendLock.unlock();
        }
    }

    private void sendAck(long seq) throws IOException {
        try {
            writeLock.lock();
            oos.writeObject(new AckPacket(seq));
            oos.flush();
        } finally {
            writeLock.unlock();
        }
        LOGGER.trace(STR."[\{this.myId} Sent ack for\{seq}");
    }

    /**
     * Close the socket and stop reading packets
     */
    @Override
    public void close() {
        closed = true;
        recvTask.cancel(true);
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    public String getOtherId() {
        return otherId.resultNow();
    }

    public int getServerPort() {
        return serverPort.resultNow();
    }

}