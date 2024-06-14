package it.polimi;

import it.polimi.packets.ByePacket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Set;
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
    @SuppressWarnings("EmptyTryBlock")
    void createTest() throws IOException {
        try (var _ = new PeerSocketManager("test", executorService, discAddr, 8888)) {
        }
    }


    @Test
    void sendP2PTest() throws IOException {
        System.out.println(System.getProperty("java.class.path"));
        try (PeerSocketManager s1 = new PeerSocketManager("test", executorService, discAddr, 8888);
             PeerSocketManager s2 = new PeerSocketManager("test2", executorService, discAddr, 8889)) {

            s1.send(new ByePacket("test"), new InetSocketAddress("localhost", 8889));
            ByePacket p = (ByePacket) s2.receiveFromPeer();

            assertEquals("test", p.id());
        }
    }

    @Test
    void sendMultipleP2PTest() throws IOException, ExecutionException, InterruptedException {
        try (PeerSocketManager s1 = new PeerSocketManager("test", executorService, discAddr, 8888);
             PeerSocketManager s2 = new PeerSocketManager("test2", executorService, discAddr, 8889)) {
            AtomicReference<Throwable> ex = new AtomicReference<>();

            Set<String> ids = ConcurrentHashMap.newKeySet();

            executorService.execute(() -> {
                try {
                    int c = 0;
                    for (int i = 0; i < 1000; i++) {
                        String val = String.valueOf(c++);
                        ids.add(val);
                        s1.send(new ByePacket(val), new InetSocketAddress("localhost", 8889));
                    }
                } catch (IOException e) {
                    ex.set(e);
                }
            });

            Future<?> recTask = executorService.submit(() -> {
                try {
                    for (int i = 0; i < 1000; i++) {
                        ByePacket p = (ByePacket) s2.receiveFromPeer();
                        assertTrue(ids.remove(p.id()));
                    }
                } catch (IOException e) {
                    ex.set(e);
                }
            });

            recTask.get();
            assertNull(ex.get());
            assertTrue(ids.isEmpty());
        }
    }
}