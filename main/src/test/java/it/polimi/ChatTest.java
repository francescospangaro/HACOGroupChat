package it.polimi;

import it.polimi.discovery.DiscoveryServer;
import it.polimi.packets.MessagePacket;
import it.polimi.utility.Message;
import it.polimi.utility.MessageGUI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ChatTest {
    private static final String ID1 = "test1";
    private static final String ID2 = "test2";
    private static final String ID3 = "test3";

    private static volatile DiscoveryServer discovery;

    @BeforeAll
    static void setUp() throws IOException {
        //Delete previous backup files (just to be sure)
        deleteBackups();

        CompletableFuture.runAsync(discovery = new DiscoveryServer());
    }

    @AfterAll
    static void afterAll() throws IOException {
        discovery.close();
    }

    private static void deleteBackups() throws IOException {
        var users = Set.of(ID1, ID2, ID3);
        for (String u : users) {
            var path = Paths.get(Peer.SAVE_DIR + u);
            if (Files.exists(path))
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                }
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteBackups();
    }

    @Test
    void connect() throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<String> c1Promise = new CompletableFuture<>();
        CompletableFuture<String> c2Promise = new CompletableFuture<>();
        try (
                Peer c1 = new Peer(ID1, 12345, e -> {
                }, e -> c1Promise.complete((String) e.getNewValue()), e -> {
                });
                Peer c2 = new Peer(ID2, 12346, e -> {
                }, e -> c2Promise.complete((String) e.getNewValue()), e -> {
                })
        ) {
            String u1 = c1Promise.get(500, TimeUnit.MILLISECONDS);
            String u2 = c2Promise.get(500, TimeUnit.MILLISECONDS);

            assertEquals(ID2, u1);
            assertEquals(ID1, u2);
        }
    }

    @Test
    void createRoom() throws ExecutionException, InterruptedException, TimeoutException {
        System.out.println("-------CREATE ROOM----------------");
        CompletableFuture<String> c1Promise = new CompletableFuture<>();
        CompletableFuture<String> c2Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        try (
                Peer c1 = new Peer(ID1, 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> c1Promise.complete((String) e.getNewValue()), e -> {
                });
                Peer c2 = new Peer(ID2, 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                        e -> c2Promise.complete((String) e.getNewValue()), e -> {
                })
        ) {
            c1Promise.get(500, TimeUnit.MILLISECONDS);
            c2Promise.get(500, TimeUnit.MILLISECONDS);

            Set<String> users = Set.of(ID1, ID2);
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
        System.out.println("-------sendMsg----------------");
        CompletableFuture<String> c1Promise = new CompletableFuture<>();
        CompletableFuture<String> c2Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        CompletableFuture<Message> msg1Promise = new CompletableFuture<>();
        CompletableFuture<Message> msg2Promise = new CompletableFuture<>();
        try (
                Peer c1 = new Peer(ID1, 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> c1Promise.complete((String) e.getNewValue()),
                        e -> msg1Promise.complete(((MessageGUI) e.getNewValue()).message()));

                Peer c2 = new Peer(ID2, 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                        e -> c2Promise.complete((String) e.getNewValue()),
                        e -> msg2Promise.complete(((MessageGUI) e.getNewValue()).message()))
        ) {
            System.out.println(c1Promise.get(500, TimeUnit.MILLISECONDS));
            System.out.println(c2Promise.get(500, TimeUnit.MILLISECONDS));

            Set<String> users = Set.of(ID1, ID2);
            c1.createRoom("room", users);

            ChatRoom chat = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            chat2Promise.get(500, TimeUnit.MILLISECONDS);

            c1.sendMessage("TEST", chat, 0);

            Message m1 = msg1Promise.get(500, TimeUnit.MILLISECONDS);
            Message m2 = msg2Promise.get(1000, TimeUnit.MILLISECONDS);

            assertEquals("TEST", m1.msg());
            assertEquals(ID1, m1.sender());

            assertEquals("TEST", m2.msg());
            assertEquals(ID1, m2.sender());

        }
    }

    @Test
    void vcTest() throws ExecutionException, InterruptedException, TimeoutException {
        System.out.println("-------vcTest----------------");
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
                Peer c1 = new Peer(ID1, 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> users1.countDown(),
                        e -> msg1Promise.complete(((MessageGUI) e.getNewValue()).message()));

                Peer c2 = new Peer(ID2, 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                        e -> users2.countDown(),
                        e -> msg2Promise.complete(((MessageGUI) e.getNewValue()).message()));

                Peer c3 = new Peer(ID3, 12347, e -> chat3Promise.complete((ChatRoom) e.getNewValue()),
                        e -> users3.countDown(),
                        e -> {
                            msg3List.add(((MessageGUI) e.getNewValue()).message());
                            msg3.countDown();
                        })
        ) {
            assertTrue(users1.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users2.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users3.await(500, TimeUnit.MILLISECONDS));

            Set<String> users = Set.of(ID1, ID2, ID3);
            c1.createRoom("room", users);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat3 = chat3Promise.get(500, TimeUnit.MILLISECONDS);

            c1.degradePerformance(ID3);

            c1.sendMessage("TEST", chat1, 0);

            Message m1 = msg1Promise.get(500, TimeUnit.MILLISECONDS);
            Message m2 = msg2Promise.get(500, TimeUnit.MILLISECONDS);

            Thread.sleep(500);
            assertTrue(msg3List.isEmpty());

            assertEquals("TEST", m1.msg());
            assertEquals(ID1, m1.sender());

            assertEquals("TEST", m2.msg());
            assertEquals(ID1, m2.sender());

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
        System.out.println("-------disconnectTest----------------");
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
                Peer c1 = new Peer(ID1, 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users1.countDown();
                            else
                                disc1Promise.complete((String) e.getOldValue());
                        },
                        e -> msg1Promise.complete(((MessageGUI) e.getNewValue()).message()));

                Peer c2 = new Peer(ID2, 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users2.countDown();
                            else
                                disc2.countDown();
                        },
                        e -> msg2Promise.complete(((MessageGUI) e.getNewValue()).message()));

                Peer c3 = new Peer(ID3, 12347, e -> chat3Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users3.countDown();
                            else
                                disc3Promise.complete((String) e.getOldValue());
                        },
                        e -> msg3Promise.complete(((MessageGUI) e.getNewValue()).message()))
        ) {
            assertTrue(users1.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users2.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users3.await(500, TimeUnit.MILLISECONDS));

            Set<String> users = Set.of(ID1, ID2, ID3);
            c1.createRoom("room", users);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat3 = chat3Promise.get(500, TimeUnit.MILLISECONDS);

            c2.disconnect();

            assertTrue(disc2.await(500, TimeUnit.MILLISECONDS));
            assertEquals(ID2, disc1Promise.get(500, TimeUnit.MILLISECONDS));
            assertEquals(ID2, disc3Promise.get(500, TimeUnit.MILLISECONDS));

            c1.sendMessage("TEST", chat1, 0);

            Message m1 = msg1Promise.get(500, TimeUnit.MILLISECONDS);
            Message m3 = msg3Promise.get(500, TimeUnit.MILLISECONDS);
            assertThrows(TimeoutException.class, () -> msg2Promise.get(500, TimeUnit.MILLISECONDS));

            assertEquals("TEST", m1.msg());
            assertEquals(ID1, m1.sender());

            assertEquals("TEST", m3.msg());
            assertEquals(ID1, m3.sender());

            assertNotNull(c1.getDiscMsg(ID2));
            assertEquals(1, c1.getDiscMsg(ID2).size());
            var msg = ((MessagePacket) c1.getDiscMsg(ID2).stream().findFirst().orElseThrow()).msg();
            assertEquals("TEST", msg.msg());
            assertEquals(ID1, msg.sender());

            c2.start();

            Message m2 = msg2Promise.get(500, TimeUnit.MILLISECONDS);
            assertEquals("TEST", m2.msg());
            assertEquals(ID1, m2.sender());

            for (int i = 0; i < 50; i++) {
                if (c1.getDiscMsg(ID2) == null || c1.getDiscMsg(ID2).isEmpty())
                    break;
                Thread.sleep(10);
            }
            assertTrue(c1.getDiscMsg(ID2) == null || c1.getDiscMsg(ID2).isEmpty());
        }
    }

    @Test
    void netFailTest() throws ExecutionException, InterruptedException, TimeoutException, IOException {
        System.out.println("-------netFailTest----------------");
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        CompletableFuture<Message> msg1Promise = new CompletableFuture<>();
        CompletableFuture<Message> msg2Promise = new CompletableFuture<>();
        CompletableFuture<String> users1Promise = new CompletableFuture<>();
        CompletableFuture<String> users2Promise = new CompletableFuture<>();
        CompletableFuture<String> disc1Promise = new CompletableFuture<>();
        CompletableFuture<String> disc2Promise = new CompletableFuture<>();

        AtomicReference<Socket> socketRef = new AtomicReference<>();
        try (
                Peer c1 = new Peer(ID1, 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users1Promise.complete((String) e.getNewValue());
                            else
                                disc1Promise.complete((String) e.getOldValue());
                        },
                        e -> msg1Promise.complete(((MessageGUI) e.getNewValue()).message()));

                Peer c2 = new Peer(ID2, 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users2Promise.complete((String) e.getNewValue());
                            else
                                disc2Promise.complete((String) e.getOldValue());
                        },
                        e -> msg2Promise.complete(((MessageGUI) e.getNewValue()).message())) {
                    @Override
                    Socket createNewSocket() {
                        if (socketRef.get() == null) {
                            Socket s = new ImproperShutdownSocket();
                            socketRef.set(s);
                            return s;
                        }
                        return super.createNewSocket();
                    }
                }
        ) {
            users1Promise.get(500, TimeUnit.MILLISECONDS);
            users2Promise.get(500, TimeUnit.MILLISECONDS);

            Set<String> users = Set.of(ID1, ID2);
            c1.createRoom("room", users);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);

            socketRef.get().close();

            c2.sendMessage("TEST", chat2, 0);

            Message m2 = msg2Promise.get(500, TimeUnit.MILLISECONDS);
            assertEquals("TEST", m2.msg());
            assertEquals(ID2, m2.sender());

            assertEquals(ID1, disc2Promise.get(6, TimeUnit.SECONDS));
            var disc = c2.getDiscMsg(ID1);
            assertEquals(1, disc.size());
            var msg = ((MessagePacket) disc.stream().findFirst().orElseThrow()).msg();
            assertEquals("TEST", msg.msg());
            assertEquals(ID2, msg.sender());

            //assertThrows(TimeoutException.class, () -> msg1Promise.get(500, TimeUnit.MILLISECONDS));

            //In 5 seconds should reconnect
            Message m1 = msg1Promise.get(6, TimeUnit.SECONDS);
            assertEquals("TEST", m1.msg());
            assertEquals(ID2, m1.sender());

            for (int i = 0; i < 50; i++) {
                if (c1.getDiscMsg(ID2) == null || c1.getDiscMsg(ID2).isEmpty())
                    break;
                Thread.sleep(10);
            }
            assertTrue(c1.getDiscMsg(ID2) == null || c1.getDiscMsg(ID2).isEmpty());

            ((ImproperShutdownSocket) socketRef.get()).actuallyClose();
        }
    }

    @Test
    void ackLost_duplicatedMessage_test() throws ExecutionException, InterruptedException, TimeoutException, IOException {
        System.out.println("-------ackLost_duplicatedMessage_test----------------");
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat3Promise = new CompletableFuture<>();

        CompletableFuture<Message> msg3Promise = new CompletableFuture<>();
        CountDownLatch msg1 = new CountDownLatch(2);
        CountDownLatch msg2 = new CountDownLatch(2);
        List<Message> msg1List = new CopyOnWriteArrayList<>();
        List<Message> msg2List = new CopyOnWriteArrayList<>();

        CountDownLatch users1 = new CountDownLatch(2);
        CompletableFuture<String> userReconnect = new CompletableFuture<>();
        CountDownLatch users2 = new CountDownLatch(2);
        CountDownLatch users3 = new CountDownLatch(2);

        AtomicReference<Socket> socket_c2_c1_ref = new AtomicReference<>();

        CompletableFuture<String> disc1Promise = new CompletableFuture<>();
        CompletableFuture<String> disc2Promise = new CompletableFuture<>();
        try (
                Peer c1 = new Peer(ID1, 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                if (disc1Promise.isDone())
                                    userReconnect.complete((String) e.getNewValue());
                                else
                                    users1.countDown();
                            else
                                disc1Promise.complete((String) e.getOldValue());
                        },
                        e -> {
                            msg1List.add(((MessageGUI) e.getNewValue()).message());
                            msg1.countDown();
                        });

                Peer c2 = new Peer(ID2, 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users2.countDown();
                            else
                                disc2Promise.complete((String) e.getOldValue());
                        },
                        e -> {
                            msg2List.add(((MessageGUI) e.getNewValue()).message());
                            msg2.countDown();
                        }) {
                    @Override
                    Socket createNewSocket() {
                        if (socket_c2_c1_ref.get() == null) {
                            Socket s = new ImproperShutdownSocket();
                            socket_c2_c1_ref.set(s);
                            return s;
                        }
                        return super.createNewSocket();
                    }
                };

                Peer c3 = new Peer(ID3, 12347, e -> chat3Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users3.countDown();
                            else
                                disc2Promise.complete((String) e.getOldValue());
                        },
                        e -> msg3Promise.complete(((MessageGUI) e.getNewValue()).message()))
        ) {
            assertTrue(users1.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users2.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users3.await(500, TimeUnit.MILLISECONDS));

            Set<String> users = Set.of(ID1, ID2, ID3);
            c1.createRoom("room", users);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat3 = chat3Promise.get(500, TimeUnit.MILLISECONDS);

            socket_c2_c1_ref.get().close();

            c1.sendMessage("TEST", chat1, 0);

            var m3 = msg3Promise.get(500, TimeUnit.MILLISECONDS);
            assertEquals("TEST", m3.msg());
            assertEquals(ID1, m3.sender());

            disc1Promise.get(6, TimeUnit.SECONDS);

            c3.sendMessage("TEST2", chat3, 0);

            assertTrue(msg1.await(500, TimeUnit.MILLISECONDS));
            assertEquals(ID2, userReconnect.get(10, TimeUnit.SECONDS));

            assertEquals(2, msg1List.size());
            var m1_0 = msg1List.get(0);
            assertEquals("TEST", m1_0.msg());
            assertEquals(ID1, m1_0.sender());

            var m1_1 = msg1List.get(1);
            assertEquals("TEST2", m1_1.msg());
            assertEquals(ID3, m1_1.sender());

            assertTrue(msg2.await(10, TimeUnit.SECONDS));
            assertEquals(2, msg2List.size());
            var m2_0 = msg2List.get(0);
            assertEquals("TEST", m2_0.msg());
            assertEquals(ID1, m2_0.sender());

            var m2_1 = msg2List.get(1);
            assertEquals("TEST2", m2_1.msg());
            assertEquals(ID3, m2_1.sender());

            ((ImproperShutdownSocket) socket_c2_c1_ref.get()).actuallyClose();
        }
    }


    @Test
    void messageLostTest() throws ExecutionException, InterruptedException, TimeoutException, IOException {
        System.out.println("-------messageLostTest----------------");
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat3Promise = new CompletableFuture<>();

        CompletableFuture<Message> msg3Promise = new CompletableFuture<>();
        CountDownLatch msg1 = new CountDownLatch(2);
        CountDownLatch msg2 = new CountDownLatch(2);
        List<Message> msg1List = new CopyOnWriteArrayList<>();
        List<Message> msg2List = new CopyOnWriteArrayList<>();

        CountDownLatch users1 = new CountDownLatch(2);
        CompletableFuture<String> userReconnect = new CompletableFuture<>();
        CountDownLatch users2 = new CountDownLatch(2);
        CountDownLatch users3 = new CountDownLatch(2);

        AtomicReference<Socket> socket_c2_c1_ref = new AtomicReference<>();

        CompletableFuture<String> disc1Promise = new CompletableFuture<>();
        CompletableFuture<String> disc2Promise = new CompletableFuture<>();

        try (
                Peer c1 = new Peer(ID1, 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users1.countDown();
                            else
                                disc1Promise.complete((String) e.getOldValue());
                        },
                        e -> {
                            msg1List.add(((MessageGUI) e.getNewValue()).message());
                            msg1.countDown();
                        });

                Peer c2 = new Peer(ID2, 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                if (disc2Promise.isDone())
                                    userReconnect.complete((String) e.getNewValue());
                                else
                                    users2.countDown();
                            else
                                disc2Promise.complete((String) e.getOldValue());
                        },
                        e -> {
                            msg2List.add(((MessageGUI) e.getNewValue()).message());
                            msg2.countDown();
                        }) {
                    @Override
                    Socket createNewSocket() {
                        if (socket_c2_c1_ref.get() == null) {
                            Socket s = new ImproperShutdownSocket();
                            socket_c2_c1_ref.set(s);
                            return s;
                        }
                        return super.createNewSocket();
                    }
                };

                Peer c3 = new Peer(ID3, 12347, e -> chat3Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users3.countDown();
                            else
                                disc2Promise.complete((String) e.getOldValue());
                        },
                        e -> msg3Promise.complete(((MessageGUI) e.getNewValue()).message()))
        ) {
            assertTrue(users1.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users2.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users3.await(500, TimeUnit.MILLISECONDS));

            Set<String> users = Set.of(ID1, ID2, ID3);
            c1.createRoom("room", users);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat3 = chat3Promise.get(500, TimeUnit.MILLISECONDS);

            socket_c2_c1_ref.get().close();

            c2.sendMessage("TEST", chat2, 0);

            var m3 = msg3Promise.get(500, TimeUnit.MILLISECONDS);
            assertEquals("TEST", m3.msg());
            assertEquals(ID2, m3.sender());

            disc2Promise.get(10, TimeUnit.SECONDS);

            c3.sendMessage("TEST2", chat3, 0);

            assertTrue(msg2.await(500, TimeUnit.MILLISECONDS));

            assertEquals(2, msg2List.size());
            var m2_0 = msg2List.get(0);
            assertEquals("TEST", m2_0.msg());
            assertEquals(ID2, m2_0.sender());

            var m2_1 = msg2List.get(1);
            assertEquals("TEST2", m2_1.msg());
            assertEquals(ID3, m2_1.sender());

            assertEquals(ID1, userReconnect.get(10, TimeUnit.SECONDS));

            assertEquals(2, msg2List.size());

            assertTrue(msg1.await(2, TimeUnit.SECONDS));
            assertEquals(2, msg1List.size());
            var m1_0 = msg1List.get(0);
            assertEquals("TEST", m1_0.msg());
            assertEquals(ID2, m1_0.sender());

            var m1_1 = msg1List.get(1);
            assertEquals("TEST2", m1_1.msg());
            assertEquals(ID3, m1_1.sender());

            ((ImproperShutdownSocket) socket_c2_c1_ref.get()).actuallyClose();
        }
    }

    @Test
    void backupTest() throws ExecutionException, InterruptedException, TimeoutException {
        System.out.println("-------backupTest----------------");
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();

        CountDownLatch msg1Latch = new CountDownLatch(2);
        CountDownLatch msg2Latch = new CountDownLatch(4);

        CompletableFuture<String> users1Promise = new CompletableFuture<>();
        CompletableFuture<String> users2Promise = new CompletableFuture<>();

        try (
                Peer c1 = new Peer(ID1, 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users1Promise.complete((String) e.getNewValue());
                        },
                        e -> msg1Latch.countDown());

                Peer c2 = new Peer(ID2, 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users2Promise.complete((String) e.getNewValue());
                        },
                        e -> msg2Latch.countDown())
        ) {
            users1Promise.get(500, TimeUnit.MILLISECONDS);
            users2Promise.get(500, TimeUnit.MILLISECONDS);

            Set<String> users = Set.of(ID1, ID2);
            c1.createRoom("room", users);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);

            c2.sendMessage("TEST", chat2, 0);
            c2.sendMessage("TEST2", chat2, 0);

            assertTrue(msg1Latch.await(500, TimeUnit.MILLISECONDS));

            c1.sendMessage("TEST3", chat1, 0);
            c1.sendMessage("TEST4", chat1, 0);

            assertTrue(msg2Latch.await(500, TimeUnit.MILLISECONDS));
        }


        //Reopen peers
        CompletableFuture<Message> msg1Promise = new CompletableFuture<>();
        CompletableFuture<Message> msg2Promise = new CompletableFuture<>();

        CompletableFuture<ChatRoom> chat1Promise_new = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise_new = new CompletableFuture<>();

        CompletableFuture<String> users1Promise_new = new CompletableFuture<>();
        CompletableFuture<String> users2Promise_new = new CompletableFuture<>();

        try (
                Peer c1 = new Peer(ID1, 12345, e -> chat1Promise_new.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users1Promise_new.complete((String) e.getNewValue());
                        },
                        e -> msg1Promise.complete(((MessageGUI) e.getNewValue()).message()));

                Peer c2 = new Peer(ID2, 12346, e -> chat2Promise_new.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users2Promise_new.complete((String) e.getNewValue());
                        },
                        e -> msg2Promise.complete(((MessageGUI) e.getNewValue()).message()))
        ) {
            users1Promise.get(500, TimeUnit.MILLISECONDS);
            users2Promise.get(500, TimeUnit.MILLISECONDS);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);

            var savedList1 = chat1.getReceivedMsgs().toArray(new Message[4]);
            var savedList2 = chat2.getReceivedMsgs().toArray(new Message[4]);

            assertEquals(4, savedList1.length);
            assertEquals(4, savedList2.length);

            assertEquals("TEST", savedList1[0].msg());
            assertEquals(ID2, savedList1[0].sender());
            assertEquals("TEST2", savedList1[1].msg());
            assertEquals(ID2, savedList1[1].sender());
            assertEquals("TEST3", savedList1[2].msg());
            assertEquals(ID1, savedList1[2].sender());
            assertEquals("TEST4", savedList1[3].msg());
            assertEquals(ID1, savedList1[3].sender());
            assertArrayEquals(savedList1, savedList2);

            c2.sendMessage("new test", chat2, 0);
            c1.sendMessage("new test2", chat1, 0);

            var m1 = msg1Promise.get(500, TimeUnit.MILLISECONDS);
            assertEquals("new test", m1.msg());
            assertEquals(ID2, m1.sender());

            var m2 = msg2Promise.get(500, TimeUnit.MILLISECONDS);
            assertEquals("new test2", m2.msg());
            assertEquals(ID1, m2.sender());
        }
    }
}