package org.HACO;

import org.HACO.discovery.DiscoveryServer;
import org.HACO.packets.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VCTest {

    private static volatile DiscoveryServer discovery;

    @BeforeAll
    static void setUp() {
        CompletableFuture.runAsync(discovery = new DiscoveryServer());
    }

    @AfterAll
    static void afterAll() throws IOException {
        discovery.close();
    }

    @Test
    void connect() throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<String> c1Promise = new CompletableFuture<>();
        CompletableFuture<String> c2Promise = new CompletableFuture<>();
        Client c1 = new Client("id1", 12345, e -> {
        }, e -> c1Promise.complete((String) e.getNewValue()), e -> {
        });

        Client c2 = new Client("id2", 12346, e -> {
        }, e -> c2Promise.complete((String) e.getNewValue()), e -> {
        });

        String u1 = c1Promise.get(500, TimeUnit.MILLISECONDS);
        String u2 = c2Promise.get(500, TimeUnit.MILLISECONDS);

        assertEquals("id2", u1);
        assertEquals("id1", u2);

        c1.disconnect();
        c2.disconnect();
    }

    @Test
    void createRoom() throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<String> c1Promise = new CompletableFuture<>();
        CompletableFuture<String> c2Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        Client c1 = new Client("id1", 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                e -> c1Promise.complete((String) e.getNewValue()), e -> {
        });
        Client c2 = new Client("id2", 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                e -> c2Promise.complete((String) e.getNewValue()), e -> {
        });
        c1Promise.get(500, TimeUnit.MILLISECONDS);
        c2Promise.get(500, TimeUnit.MILLISECONDS);

        Set<String> users = Set.of("id1", "id2");
        c1.createRoom("room", users);

        ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
        ChatRoom chat2 = chat1Promise.get(500, TimeUnit.MILLISECONDS);

        assertEquals(users, chat1.getUsers());
        assertEquals("room", chat1.getName());

        assertEquals(users, chat2.getUsers());
        assertEquals("room", chat2.getName());

        c1.disconnect();
        c2.disconnect();
    }

    @Test
    void sendMsg() throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<String> c1Promise = new CompletableFuture<>();
        CompletableFuture<String> c2Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        CompletableFuture<Message> msg1Promise = new CompletableFuture<>();
        CompletableFuture<Message> msg2Promise = new CompletableFuture<>();
        Client c1 = new Client("id1", 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                e -> c1Promise.complete((String) e.getNewValue()),
                e -> msg1Promise.complete((Message) e.getNewValue()));

        Client c2 = new Client("id2", 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                e -> c2Promise.complete((String) e.getNewValue()),
                e -> msg2Promise.complete((Message) e.getNewValue()));

        System.out.println(c1Promise.get(500, TimeUnit.MILLISECONDS));
        System.out.println(c2Promise.get(500, TimeUnit.MILLISECONDS));

        Set<String> users = Set.of("id1", "id2");
        c1.createRoom("room", users);

        ChatRoom chat = chat1Promise.get(500, TimeUnit.MILLISECONDS);
        chat2Promise.get(500, TimeUnit.MILLISECONDS);

        c1.sendMessage("TEST", chat, 0);

        Message m1 = msg1Promise.get(500, TimeUnit.MILLISECONDS);
        Message m2 = msg2Promise.get(1000, TimeUnit.MILLISECONDS);

        assertEquals("TEST", m1.msg());
        assertEquals("id1", m1.sender());

        assertEquals("TEST", m2.msg());
        assertEquals("id1", m2.sender());

        c1.disconnect();
        c2.disconnect();
    }

    @Test
    void vcTest() throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat3Promise = new CompletableFuture<>();
        CompletableFuture<Message> msg1Promise = new CompletableFuture<>();
        CompletableFuture<Message> msg2Promise = new CompletableFuture<>();
        List<Message> msg3List = new CopyOnWriteArrayList<>();
        CountDownLatch msg3 = new CountDownLatch(2);
        CountDownLatch users1 = new CountDownLatch(2);
        CountDownLatch users2 = new CountDownLatch(2);
        CountDownLatch users3 = new CountDownLatch(2);
        Client c1 = new Client("id1", 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                e -> {
                    System.out.println("PD " + e);
                    users1.countDown();
                },
                e -> msg1Promise.complete((Message) e.getNewValue()));

        Client c2 = new Client("id2", 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                e -> users2.countDown(),
                e -> msg2Promise.complete((Message) e.getNewValue()));

        Client c3 = new Client("id3", 12347, e -> chat3Promise.complete((ChatRoom) e.getNewValue()),
                e -> users3.countDown(),
                e -> {
                    msg3List.add((Message) e.getNewValue());
                    msg3.countDown();
                });
        users1.await();
        users2.await();
        users3.await();

        Set<String> users = Set.of("id1", "id2", "id3");
        c1.createRoom("room", users);

        ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
        ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);
        ChatRoom chat3 = chat3Promise.get(500, TimeUnit.MILLISECONDS);

        c1.degradePerformance("id3");

        c1.sendMessage("TEST", chat1, 0);

        Message m1 = msg1Promise.get(500, TimeUnit.MILLISECONDS);
        Message m2 = msg2Promise.get(500, TimeUnit.MILLISECONDS);

        Thread.sleep(100);
        assertTrue(msg3List.isEmpty());

        assertEquals("TEST", m1.msg());
        assertEquals("id1", m1.sender());

        assertEquals("TEST", m2.msg());
        assertEquals("id1", m2.sender());

        c2.sendMessage("TEST2", chat2, 0);

        Thread.sleep(100);
        assertTrue(msg3List.isEmpty());

        assertTrue(msg3.await(2000, TimeUnit.MILLISECONDS));

        assertEquals("TEST", msg3List.get(0).msg());
        assertEquals("TEST2", msg3List.get(1).msg());

        c1.disconnect();
        c2.disconnect();
        c3.disconnect();
    }

}