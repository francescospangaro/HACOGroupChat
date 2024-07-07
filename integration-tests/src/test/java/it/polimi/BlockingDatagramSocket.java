package it.polimi;

import java.io.IOException;
import java.net.*;
import java.util.HashSet;
import java.util.Set;

public class BlockingDatagramSocket extends DatagramSocket {

    private final Set<SocketAddress> blocked;

    public BlockingDatagramSocket(int port) throws SocketException {
        super(port);
        blocked = new HashSet<>();
    }

    @Override
    public void send(DatagramPacket p) throws IOException {
        if (!blocked.contains(p.getSocketAddress()))
            super.send(p);
    }

    public void lock(SocketAddress addr) {
        blocked.add(addr);
        System.out.println("locked for " + addr);
    }

    public void unlock(SocketAddress addr) {
        blocked.remove(addr);
        System.out.println("unlocked for "+addr);
    }
}
