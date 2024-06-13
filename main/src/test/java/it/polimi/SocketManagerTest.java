package it.polimi;

import it.polimi.packets.ByePacket;
import it.polimi.packets.p2p.DeleteRoomPacket;
import it.polimi.packets.p2p.P2PPacket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SocketManagerTest {

    private static ExecutorService executorService;
    private static SocketAddress discAddr;

    @BeforeAll
    static void beforeAll() {
        executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterAll
    static void afterAll() {
        executorService.shutdownNow();
    }

    @Test
    void createTest() {
        assertDoesNotThrow(() -> new SocketManager("test", executorService, discAddr, 8888));
    }


    @Test
    void sendP2PTest() throws IOException {
        SocketManager s1 = new SocketManager("test", executorService, discAddr, 8888);
        SocketManager s2 = new SocketManager("test2", executorService, discAddr, 8889);

        s1.send(new ByePacket("test"), new InetSocketAddress("localhost", 8888));
        ByePacket p = (ByePacket) s2.receiveFromPeer();

        assertEquals("test", p.id());
    }

    @Test
    void sendMultipleP2PTest() throws IOException {
        SocketManager s1 = new SocketManager("test", executorService, discAddr, 8888);
        SocketManager s2 = new SocketManager("test2", executorService, discAddr, 8889);
        AtomicReference<Throwable> ex = new AtomicReference<>();

        Set<String> ids = ConcurrentHashMap.newKeySet();

        executorService.execute(() -> {
            try {
                int c = 0;
                for (int i = 0; i < 1000; i++) {
                    String val = String.valueOf(c++);
                    ids.add(val);
                    s1.send(new ByePacket(val), new InetSocketAddress("localhost", 8888));
                }
            } catch (IOException e) {
                ex.set(e);
            }
        });

        executorService.execute(() -> {
            try {
                do {
                    ByePacket p = (ByePacket) s2.receiveFromPeer();
                    assertTrue(ids.remove(p.id()));
                } while (!ids.isEmpty());
            } catch (IOException e) {
                ex.set(e);
            }
        });

        assertNull(ex.get());
        assertTrue(ids.isEmpty());
    }
}