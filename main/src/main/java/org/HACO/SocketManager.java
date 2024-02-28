package org.HACO;

import org.HACO.packets.*;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class SocketManager {

    private final Socket socket;
    private final ObjectOutputStream oos;
    private final ObjectInputStream ois;

    private final Future<?> recvTask;
    private final AtomicLong seq = new AtomicLong();
    private final String myId;
    private final Consumer<P2PPacket> inPacketConsumer;
    private final BiConsumer<String, Throwable> onClose;
    private CompletableFuture<String> otherId;
    private volatile CompletableFuture<Void> ackPromise;

    public SocketManager(String myId,
                         String otherId,
                         ExecutorService executor,
                         Socket socket,
                         Consumer<P2PPacket> inPacketConsumer,
                         BiConsumer<String, Throwable> onClose)
            throws IOException {
        this(myId, otherId, executor, socket, socket.getInputStream(), socket.getOutputStream(), inPacketConsumer, onClose);
    }

    private SocketManager(String myId,
                          String otherId,
                          ExecutorService executor,
                          Socket socket,
                          InputStream is,
                          OutputStream os,
                          Consumer<P2PPacket> inPacketConsumer,
                          BiConsumer<String, Throwable> onClose)
            throws IOException {
        this.socket = socket;
        this.myId = myId;
        this.oos = os instanceof ObjectOutputStream oos ? oos : new ObjectOutputStream(os);
        this.ois = is instanceof ObjectInputStream ois ? ois : new ObjectInputStream(is);
        this.otherId = new CompletableFuture<>();

        recvTask = executor.submit(this::readLoop);
        this.inPacketConsumer = inPacketConsumer;
        this.onClose = onClose;

        if (otherId != null) {
            this.otherId.complete(otherId);
            send(new HelloPacket(myId));
        }

        try {
            this.otherId.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        } catch (TimeoutException e) {
            throw new IOException(e);
        }
        ackPromise = new CompletableFuture<>();
    }

    private void readLoop() {
        try {
            boolean wasClosed = false;
            do {
                Packet p;
                try {
                    p = (Packet) ois.readObject();
                } catch (ClassNotFoundException | ClassCastException ex) {
                    System.err.println("[" + myId + "] Received unexpected input packet" + ex);
                    continue;
                }


                switch (p) {
                    case AckPacket ack -> {
                        System.out.println("Receiver ack" + ack);
                        if (ack.seqNum() != seq.get() - 1)
                            throw new IllegalStateException("This show never happen(?)");
                        ackPromise.complete(null);
                    }
                    case SeqPacketImpl seqPacket -> {
                        System.out.println("Received packet: " + p);
                        sendAck(seqPacket.seqNum());
                        if (seqPacket.p() instanceof HelloPacket helloPacket) {
                            otherId.complete(helloPacket.id());
                        } else {
                            inPacketConsumer.accept(seqPacket.p());
                        }
                    }
                    default -> throw new IllegalStateException("Unexpected value: " + p);
                }
            } while (!wasClosed && !Thread.currentThread().isInterrupted());

        } catch (IOException e) {
            close();
            onClose.accept(otherId.resultNow(), e);
        }
    }


    public synchronized void send(P2PPacket packet) throws IOException {
        long seqN = seq.getAndIncrement();
        ackPromise = new CompletableFuture<>();

        oos.writeObject(new SeqPacketImpl(packet, seqN));
        System.out.println("Sent " + packet);

        try {
            ackPromise.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        } catch (TimeoutException e) {
            throw new IOException(e);
        }
    }

    private void sendAck(long seq) throws IOException {
        oos.writeObject(new AckPacket(seq));
        System.out.println("Sent ack for" + seq);
    }

    public void close() {
        recvTask.cancel(true);
        try {
            socket.close();
        } catch (IOException e) {
            //ignored (?)
        }
    }

    public String getOtherId() {
        return otherId.resultNow();
    }

}