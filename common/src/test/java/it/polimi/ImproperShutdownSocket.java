package it.polimi;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class ImproperShutdownSocket extends DatagramSocket {
    private final Semaphore readSemaphore = new Semaphore(Integer.MAX_VALUE);
    private volatile boolean discardOutput;

    public ImproperShutdownSocket(int port) throws SocketException {
        super(port);
    }

    @Override
    public void send(DatagramPacket p) throws IOException {
        if (!discardOutput)
            super.send(p);
    }

    public void lock() {
        System.out.println("locking...");
        if (discardOutput) //Already closed
            return;
        readSemaphore.drainPermits();
        System.out.println("locked");
        discardOutput = true;
    }

    public void unlock() {
        if (!discardOutput) //Already unlocked
            return;

        readSemaphore.release(Integer.MAX_VALUE - 100);
        discardOutput = false;
        System.out.println("unlocked");
    }

    private void acquireSemaphore() throws IOException {
        try {
            var soTimeout = getSoTimeout();
            if (soTimeout <= 0) {
                readSemaphore.acquire();
            } else {
                if (!readSemaphore.tryAcquire(soTimeout, TimeUnit.MILLISECONDS))
                    throw new SocketTimeoutException();
            }
        } catch (InterruptedException e) {
            throw (IOException) new ClosedByInterruptException().initCause(e);
        }
    }

    @Override
    public void receive(DatagramPacket p) throws IOException {
        acquireSemaphore();
        try {
            super.receive(p);
        } finally {
            readSemaphore.release();
        }
    }
}
