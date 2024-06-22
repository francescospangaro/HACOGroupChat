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
    private final Semaphore readSemaphore = new Semaphore(1);
    private volatile boolean locked;

    public ImproperShutdownSocket(int port) throws SocketException {
        super(port);
    }

    @Override
    public void send(DatagramPacket p) throws IOException {
        if (!locked)
            super.send(p);
    }

    public void lock() {
        System.out.println("locking...");
        if (locked) //Already closed
            return;
        readSemaphore.drainPermits();
        System.out.println("locked");
        locked = true;
    }

    public void unlock() {
        if (!locked) //Already unlocked
            return;

        locked = false;
        readSemaphore.release();
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
        if (locked) {
            acquireSemaphore();
        }
        super.receive(p);
    }
}
