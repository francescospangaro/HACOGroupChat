package org.HACO;

import org.HACO.discovery.DiscoveryServer;
import org.HACO.packets.Message;
import org.HACO.packets.MessageGUI;
import org.HACO.packets.MessagePacket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ChatTest {

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

        try (
                Peer c1 = new Peer("id1", 12345, e -> {
                }, e -> c1Promise.complete((String) e.getNewValue()), e -> {
                }, true);
                Peer c2 = new Peer("id2", 12346, e -> {
                }, e -> c2Promise.complete((String) e.getNewValue()), e -> {
                }, true)
        ) {
            String u1 = c1Promise.get(500, TimeUnit.MILLISECONDS);
            String u2 = c2Promise.get(500, TimeUnit.MILLISECONDS);

            assertEquals("id2", u1);
            assertEquals("id1", u2);
        }
    }

    @Test
    void createRoom() throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<String> c1Promise = new CompletableFuture<>();
        CompletableFuture<String> c2Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        try (
                Peer c1 = new Peer("id1", 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> c1Promise.complete((String) e.getNewValue()), e -> {
                }, true);
                Peer c2 = new Peer("id2", 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                        e -> c2Promise.complete((String) e.getNewValue()), e -> {
                }, true)
        ) {
            c1Promise.get(500, TimeUnit.MILLISECONDS);
            c2Promise.get(500, TimeUnit.MILLISECONDS);

            Set<String> users = Set.of("id1", "id2");
            c1.createRoom("room", users);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);

            assertEquals(users, chat1.getUsers());
            assertEquals("room", chat1.getName());

            assertEquals(users, chat2.getUsers());
            assertEquals("room", chat2.getName());
        }
    }

    @Test
    void sendMsg() throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<String> c1Promise = new CompletableFuture<>();
        CompletableFuture<String> c2Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        CompletableFuture<Message> msg1Promise = new CompletableFuture<>();
        CompletableFuture<Message> msg2Promise = new CompletableFuture<>();
        try (
                Peer c1 = new Peer("id1", 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> c1Promise.complete((String) e.getNewValue()),
                        e -> msg1Promise.complete(((MessageGUI) e.getNewValue()).message()), true);

                Peer c2 = new Peer("id2", 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                        e -> c2Promise.complete((String) e.getNewValue()),
                        e -> msg2Promise.complete(((MessageGUI) e.getNewValue()).message()), true)
        ) {
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

        }
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
        try (
                Peer c1 = new Peer("id1", 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> users1.countDown(),
                        e -> msg1Promise.complete(((MessageGUI) e.getNewValue()).message()), true);

                Peer c2 = new Peer("id2", 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                        e -> users2.countDown(),
                        e -> msg2Promise.complete(((MessageGUI) e.getNewValue()).message()), true);

                Peer c3 = new Peer("id3", 12347, e -> chat3Promise.complete((ChatRoom) e.getNewValue()),
                        e -> users3.countDown(),
                        e -> {
                            msg3List.add(((MessageGUI) e.getNewValue()).message());
                            msg3.countDown();
                        }, true)
        ) {
            assertTrue(users1.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users2.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users3.await(500, TimeUnit.MILLISECONDS));

            Set<String> users = Set.of("id1", "id2", "id3");
            c1.createRoom("room", users);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat3 = chat3Promise.get(500, TimeUnit.MILLISECONDS);

            c1.degradePerformance("id3");

            c1.sendMessage("TEST", chat1, 0);

            Message m1 = msg1Promise.get(500, TimeUnit.MILLISECONDS);
            Message m2 = msg2Promise.get(500, TimeUnit.MILLISECONDS);

            Thread.sleep(500);
            assertTrue(msg3List.isEmpty());

            assertEquals("TEST", m1.msg());
            assertEquals("id1", m1.sender());

            assertEquals("TEST", m2.msg());
            assertEquals("id1", m2.sender());

            c2.sendMessage("TEST2", chat2, 0);

            for (int i = 0; i < 50; i++) {
                Thread.sleep(10);
                if (1 == chat3.getWaiting().size())
                    break;
            }
            assertTrue(msg3List.isEmpty());
            assertEquals(1, chat3.getWaiting().size());
            assertEquals("TEST2", chat3.getWaiting().stream().findFirst().orElseThrow().msg());

            assertTrue(msg3.await(2000, TimeUnit.MILLISECONDS));

            assertEquals("TEST", msg3List.get(0).msg());
            assertEquals("TEST2", msg3List.get(1).msg());
        }
    }

    @Test
    void disconnectTest() throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat3Promise = new CompletableFuture<>();
        CompletableFuture<Message> msg1Promise = new CompletableFuture<>();
        CompletableFuture<Message> msg2Promise = new CompletableFuture<>();
        CompletableFuture<Message> msg3Promise = new CompletableFuture<>();
        CountDownLatch users1 = new CountDownLatch(2);
        CountDownLatch users2 = new CountDownLatch(2);
        CountDownLatch users3 = new CountDownLatch(2);
        CompletableFuture<String> disc1Promise = new CompletableFuture<>();
        CompletableFuture<String> disc3Promise = new CompletableFuture<>();
        CountDownLatch disc2 = new CountDownLatch(2);
        try (
                Peer c1 = new Peer("id1", 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users1.countDown();
                            else
                                disc1Promise.complete((String) e.getOldValue());
                        },
                        e -> msg1Promise.complete(((MessageGUI) e.getNewValue()).message()), true);

                Peer c2 = new Peer("id2", 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users2.countDown();
                            else
                                disc2.countDown();
                        },
                        e -> msg2Promise.complete(((MessageGUI) e.getNewValue()).message()), true);

                Peer c3 = new Peer("id3", 12347, e -> chat3Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users3.countDown();
                            else
                                disc3Promise.complete((String) e.getOldValue());
                        },
                        e -> msg3Promise.complete(((MessageGUI) e.getNewValue()).message()), true)
        ) {
            assertTrue(users1.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users2.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users3.await(500, TimeUnit.MILLISECONDS));

            Set<String> users = Set.of("id1", "id2", "id3");
            c1.createRoom("room", users);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat3 = chat3Promise.get(500, TimeUnit.MILLISECONDS);

            c2.disconnect();

            assertTrue(disc2.await(500, TimeUnit.MILLISECONDS));
            assertEquals("id2", disc1Promise.get(500, TimeUnit.MILLISECONDS));
            assertEquals("id2", disc3Promise.get(500, TimeUnit.MILLISECONDS));

            c1.sendMessage("TEST", chat1, 0);

            Message m1 = msg1Promise.get(500, TimeUnit.MILLISECONDS);
            Message m3 = msg3Promise.get(500, TimeUnit.MILLISECONDS);
            assertThrows(TimeoutException.class, () -> msg2Promise.get(500, TimeUnit.MILLISECONDS));

            assertEquals("TEST", m1.msg());
            assertEquals("id1", m1.sender());

            assertEquals("TEST", m3.msg());
            assertEquals("id1", m3.sender());

            assertNotNull(c1.getDiscMsg("id2"));
            assertEquals(1, c1.getDiscMsg("id2").size());
            var msg = ((MessagePacket) c1.getDiscMsg("id2").stream().findFirst().orElseThrow()).msg();
            assertEquals("TEST", msg.msg());
            assertEquals("id1", msg.sender());

            c2.start();

            Message m2 = msg2Promise.get(500, TimeUnit.MILLISECONDS);
            assertEquals("TEST", m2.msg());
            assertEquals("id1", m2.sender());

            for (int i = 0; i < 50; i++) {
                if (c1.getDiscMsg("id2") == null || c1.getDiscMsg("id2").isEmpty())
                    break;
                Thread.sleep(10);
            }
            assertTrue(c1.getDiscMsg("id2") == null || c1.getDiscMsg("id2").isEmpty());
        }
    }

}