package it.polimi;

import it.polimi.packets.DeleteRoomPacket;
import it.polimi.packets.P2PPacket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SocketManagerTest {

    private static ExecutorService executorService;

    @BeforeAll
    static void beforeAll() {
        executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterAll
    static void afterAll() {
        executorService.shutdownNow();
    }

    @Test
    void connectTest() throws IOException {
        ServerSocket serverSocket = new ServerSocket(8888);
        CompletableFuture<P2PPacket> p1Promise = new CompletableFuture<>();
        CompletableFuture<P2PPacket> p2Promise = new CompletableFuture<>();
        CompletableFuture<Throwable> close1Promise = new CompletableFuture<>();
        CompletableFuture<Throwable> close2Promise = new CompletableFuture<>();
        AtomicReference<Throwable> ex = new AtomicReference<>();

        AtomicReference<SocketManager> socketManager2 = new AtomicReference<>();

        executorService.execute(() -> {
            try {
                Socket s = serverSocket.accept();
                socketManager2.set(new SocketManager("id", executorService, s, p1Promise::complete, (id, e) -> close1Promise.complete(e), 500));
            } catch (IOException e) {
                ex.set(new AssertionError("Failed creating the socket manager", e));
            }
        });

        Socket s = new Socket();
        s.connect(new InetSocketAddress("localhost", 8888));
        SocketManager socketManager = new SocketManager("id2", 0, "id", executorService, s, p2Promise::complete, (id, e) -> close2Promise.complete(e), 500);

        assertNull(ex.get());
        assertThrows(TimeoutException.class, () -> p1Promise.get(50, TimeUnit.MILLISECONDS));
        assertThrows(TimeoutException.class, () -> p2Promise.get(50, TimeUnit.MILLISECONDS));
        assertThrows(TimeoutException.class, () -> close1Promise.get(50, TimeUnit.MILLISECONDS));
        assertThrows(TimeoutException.class, () -> close2Promise.get(50, TimeUnit.MILLISECONDS));

        socketManager.close();
        socketManager2.get().close();
        serverSocket.close();
    }


    @Test
    void sendTest() throws IOException, ExecutionException, InterruptedException, TimeoutException {
        ServerSocket serverSocket = new ServerSocket(8888);
        CompletableFuture<P2PPacket> p1Promise = new CompletableFuture<>();
        CompletableFuture<P2PPacket> p2Promise = new CompletableFuture<>();
        CompletableFuture<Throwable> close1Promise = new CompletableFuture<>();
        CompletableFuture<Throwable> close2Promise = new CompletableFuture<>();
        AtomicReference<Throwable> ex = new AtomicReference<>();

        AtomicReference<SocketManager> socketManager2 = new AtomicReference<>();
        executorService.execute(() -> {
            try {
                Socket s = serverSocket.accept();
                socketManager2.set(new SocketManager("id", executorService, s, p1Promise::complete, (id, e) -> close1Promise.complete(e), 500));
            } catch (IOException e) {
                ex.set(new AssertionError("Failed creating the socket manager", e));
            }
        });

        Socket s = new Socket();
        s.connect(new InetSocketAddress("localhost", 8888));
        SocketManager socketManager = new SocketManager("id2", 0, "id", executorService, s, p2Promise::complete, (id, e) -> close2Promise.complete(e), 500);

        assertThrows(TimeoutException.class, () -> p1Promise.get(50, TimeUnit.MILLISECONDS));
        assertThrows(TimeoutException.class, () -> p2Promise.get(50, TimeUnit.MILLISECONDS));
        assertThrows(TimeoutException.class, () -> close1Promise.get(50, TimeUnit.MILLISECONDS));
        assertThrows(TimeoutException.class, () -> close2Promise.get(50, TimeUnit.MILLISECONDS));
        assertNull(ex.get());

        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        var p = new DeleteRoomPacket(id1);
        assertDoesNotThrow(() -> socketManager.send(p));

        assertEquals(p, p1Promise.get(500, TimeUnit.MILLISECONDS));

        var p2 = new DeleteRoomPacket(id2);
        assertDoesNotThrow(() -> socketManager2.get().send(p2));

        assertEquals(p2, p2Promise.get(500, TimeUnit.MILLISECONDS));

        assertThrows(TimeoutException.class, () -> close1Promise.get(50, TimeUnit.MILLISECONDS));
        assertThrows(TimeoutException.class, () -> close2Promise.get(50, TimeUnit.MILLISECONDS));

        socketManager.close();
        socketManager2.get().close();
        serverSocket.close();
    }


    @Test
    void multipleSendTest() throws IOException, InterruptedException {
        ServerSocket serverSocket = new ServerSocket(8888);

        CountDownLatch countDownLatch = new CountDownLatch(100);
        AtomicReference<Throwable> ex = new AtomicReference<>();

        CompletableFuture<P2PPacket> p2Promise = new CompletableFuture<>();
        CompletableFuture<Throwable> close1Promise = new CompletableFuture<>();
        CompletableFuture<Throwable> close2Promise = new CompletableFuture<>();

        AtomicReference<SocketManager> socketManager2 = new AtomicReference<>();
        List<UUID> uuids = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            uuids.add(UUID.randomUUID());
        }


        executorService.execute(() -> {
            try {
                Socket s = serverSocket.accept();
                socketManager2.set(new SocketManager("id", executorService, s, p -> {
                    if (((DeleteRoomPacket) p).id().equals(uuids.get((int) (100 - countDownLatch.getCount())))) {
                        countDownLatch.countDown();
                    } else {
                        ex.set(new AssertionError("Not FIFO"));
                    }
                }, (id, e) -> close1Promise.complete(e), 500));
            } catch (IOException e) {
                ex.set(new AssertionError("Failed creating the socket manager", e));
            }
        });

        Socket s = new Socket();
        s.connect(new InetSocketAddress("localhost", 8888));
        SocketManager socketManager = new SocketManager("id2", 0, "id", executorService, s, p2Promise::complete, (id, e) -> close2Promise.complete(e), 500);

        assertEquals(100, countDownLatch.getCount());

        for (int i = 0; i < 100; i++) {
            var p = new DeleteRoomPacket(uuids.get(i));
            assertDoesNotThrow(() -> socketManager.send(p));
        }

        assertTrue(countDownLatch.await(500, TimeUnit.MILLISECONDS));
        assertNull(ex.get());


        assertFalse(p2Promise.isDone());
        assertThrows(TimeoutException.class, () -> close1Promise.get(50, TimeUnit.MILLISECONDS));
        assertThrows(TimeoutException.class, () -> close2Promise.get(50, TimeUnit.MILLISECONDS));

        socketManager.close();
        socketManager2.get().close();
        serverSocket.close();
    }


    @Test
    void sendWithNetFailTest() throws IOException {
        ServerSocket serverSocket = new ServerSocket(8888);
        CompletableFuture<P2PPacket> p1Promise = new CompletableFuture<>();
        CompletableFuture<P2PPacket> p2Promise = new CompletableFuture<>();
        CompletableFuture<Throwable> close1Promise = new CompletableFuture<>();
        CompletableFuture<Throwable> close2Promise = new CompletableFuture<>();
        AtomicReference<Throwable> ex = new AtomicReference<>();

        AtomicReference<SocketManager> socketManager2 = new AtomicReference<>();
        executorService.execute(() -> {
            try {
                Socket s = serverSocket.accept();
                socketManager2.set(new SocketManager("id", executorService, s, p1Promise::complete, (id, e) -> close1Promise.complete(e), 500));
            } catch (IOException e) {
                ex.set(new AssertionError("Failed creating the socket manager", e));
            }
        });

        ImproperShutdownSocket s = new ImproperShutdownSocket();
        s.connect(new InetSocketAddress("localhost", 8888));
        SocketManager socketManager = new SocketManager("id2", 0, "id", executorService, s, p2Promise::complete, (id, e) -> close2Promise.complete(e), 500);

        assertThrows(TimeoutException.class, () -> p1Promise.get(50, TimeUnit.MILLISECONDS));
        assertThrows(TimeoutException.class, () -> p2Promise.get(50, TimeUnit.MILLISECONDS));
        assertThrows(TimeoutException.class, () -> close1Promise.get(50, TimeUnit.MILLISECONDS));
        assertThrows(TimeoutException.class, () -> close2Promise.get(50, TimeUnit.MILLISECONDS));
        assertNull(ex.get());

        s.close();


        var p = new DeleteRoomPacket(UUID.randomUUID());
        assertThrows(IOException.class, () -> socketManager.send(p));

        var p2 = new DeleteRoomPacket(UUID.randomUUID());
        assertThrows(IOException.class, () -> socketManager2.get().send(p2));

        assertThrows(TimeoutException.class, () -> p1Promise.get(50, TimeUnit.MILLISECONDS));
        //assertFalse(p2Promise.isDone());

        assertThrows(TimeoutException.class, () -> close1Promise.get(50, TimeUnit.MILLISECONDS));
        assertThrows(TimeoutException.class, () -> close2Promise.get(50, TimeUnit.MILLISECONDS));

        socketManager.close();
        socketManager2.get().close();
        s.actuallyClose();
        serverSocket.close();
    }


}