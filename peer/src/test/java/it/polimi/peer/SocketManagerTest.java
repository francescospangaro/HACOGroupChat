package it.polimi.peer;

import it.polimi.ImproperShutdownSocket;
import it.polimi.packets.ByePacket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
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
    void createTest() throws IOException, InterruptedException {
        try (var _ = new PeerSocketManager("test", executorService, discAddr, 8888)) {
            Thread.sleep(5);
        }
    }


    @Test
    void sendP2PTest() throws IOException {
        System.out.println(System.getProperty("java.class.path"));
        try (PeerSocketManager s1 = new PeerSocketManager("test", executorService, discAddr, 8888);
             PeerSocketManager s2 = new PeerSocketManager("test2", executorService, discAddr, 8889)) {

            s1.send(new ByePacket("test"), new InetSocketAddress("localhost", 8889));
            ByePacket p = (ByePacket) s2.receiveFromPeer().packet();

            assertEquals("test", p.id());
        }
    }

    @Test
    void sendMultipleP2PTest() throws IOException, ExecutionException, InterruptedException, TimeoutException {
        try (PeerSocketManager s1 = new PeerSocketManager("test", executorService, discAddr, 8888);
             PeerSocketManager s2 = new PeerSocketManager("test2", executorService, discAddr, 8889)) {
            AtomicReference<Throwable> ex = new AtomicReference<>();

            Set<String> ids = ConcurrentHashMap.newKeySet();

            Future<?> sendTask = executorService.submit(() -> {
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
                        ByePacket p = (ByePacket) s2.receiveFromPeer().packet();
                        assertTrue(ids.remove(p.id()));
                    }
                } catch (IOException e) {
                    ex.set(e);
                }
            });

            sendTask.get(3, TimeUnit.SECONDS);
            recTask.get(3, TimeUnit.SECONDS);
            assertNull(ex.get());
            assertTrue(ids.isEmpty());
        }
    }

    @Test
    void sendWithNetFailTest() throws IOException, ExecutionException, InterruptedException, TimeoutException {
        final List<Throwable> ex = new CopyOnWriteArrayList<>();
        final SocketAddress a1 = new InetSocketAddress("localhost", 8888);
        final SocketAddress a2 = new InetSocketAddress("localhost", 8889);

        try (ImproperShutdownSocket socket = new ImproperShutdownSocket(8889);
             PeerSocketManager socketManager1 = new PeerSocketManager("test", executorService, discAddr, 8888, 500);
             PeerSocketManager socketManager2 = new PeerSocketManager("test2", executorService, discAddr, 500, socket)) {
            Future<?> sendTask = executorService.submit(() -> {
                try {
                    socketManager1.send(new ByePacket(""), a2);
                } catch (IOException e) {
                    ex.add(new AssertionError("Failed sending packet", e));
                }
            });

            Future<?> recTask = executorService.submit(() -> {
                try {
                    socketManager2.receiveFromPeer();
                } catch (IOException e) {
                    ex.add(new AssertionError("Failed receiving packet", e));
                }
            });

            sendTask.get(500, TimeUnit.MILLISECONDS);
            recTask.get(500, TimeUnit.MILLISECONDS);
            assertTrue(ex.isEmpty());

            //Wait that all threads have called receive()
            Thread.sleep(50);
            socket.lock();


            Future<?> sendTask2 = executorService.submit(() -> {
                try {
                    socketManager2.send(new ByePacket(""), a1);
                } catch (IOException e) {
                    ex.add(e);
                }
            });

            Future<?> recTask2 = executorService.submit(() -> {
                try {
                    socketManager1.receiveFromPeer();
                } catch (IOException e) {
                    ex.add(new AssertionError("Failed receiving packet", e));
                }
            });

            assertThrows(TimeoutException.class, () -> recTask2.get(500, TimeUnit.MILLISECONDS));
            sendTask2.get(600, TimeUnit.MILLISECONDS);
            assertEquals(1, ex.size());
            assertInstanceOf(IOException.class, ex.getLast());


            Future<?> sendTask3 = executorService.submit(() -> {
                try {
                    socketManager1.send(new ByePacket(""), a2);
                } catch (IOException e) {
                    ex.add(e);
                }
            });

            Future<?> recTask3 = executorService.submit(() -> {
                try {
                    socketManager2.receiveFromPeer();
                } catch (IOException e) {
                    ex.add(new AssertionError("Failed receiving packet", e));
                }
            });

            recTask3.get(500, TimeUnit.MILLISECONDS);
            sendTask3.get(700, TimeUnit.MILLISECONDS);
            assertEquals(2, ex.size());
            assertInstanceOf(IOException.class, ex.getLast());

            Future<?> sendTask4 = executorService.submit(() -> {
                try {
                    socketManager1.send(new ByePacket(""), a2);
                } catch (IOException e) {
                    ex.add(e);
                }
            });

            Future<?> recTask4 = executorService.submit(() -> {
                try {
                    socketManager2.receiveFromPeer();
                } catch (IOException e) {
                    ex.add(new AssertionError("Failed receiving packet", e));
                }
            });

            assertThrows(TimeoutException.class, () -> recTask4.get(500, TimeUnit.MILLISECONDS));
            sendTask4.get(600, TimeUnit.MILLISECONDS);
            assertEquals(3, ex.size());
            assertInstanceOf(IOException.class, ex.getLast());

            socket.unlock();

            Future<?> sendTask5 = executorService.submit(() -> {
                try {
                    socketManager1.send(new ByePacket(""), a2);
                } catch (IOException e) {
                    ex.add(e);
                }
            });

            recTask4.get(500, TimeUnit.MILLISECONDS);
            sendTask5.get(100, TimeUnit.MILLISECONDS);

            Future<?> sendTask6 = executorService.submit(() -> {
                try {
                    socketManager2.send(new ByePacket(""), a1);
                } catch (IOException e) {
                    ex.add(e);
                }
            });

            recTask2.get(500, TimeUnit.MILLISECONDS);
            sendTask6.get(100, TimeUnit.MILLISECONDS);
            assertEquals(3, ex.size());
        }
    }
}