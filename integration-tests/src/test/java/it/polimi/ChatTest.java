package it.polimi;

import it.polimi.discovery.DiscoveryServer;
import it.polimi.messages.Message;
import it.polimi.messages.StringMessage;
import it.polimi.packets.p2p.MessagePacket;
import it.polimi.packets.p2p.P2PPacket;
import it.polimi.peer.*;
import it.polimi.peer.exceptions.DiscoveryUnreachableException;
import it.polimi.peer.utility.MessageGUI;
import org.junit.jupiter.api.*;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
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
    static void beforeAll() throws IOException {
        //Delete previous backup files (just to be sure)
        deleteBackups();
    }

    private static void deleteBackups() throws IOException {
        var users = Set.of(ID1, ID2, ID3);
        for (String u : users) {
            var path = Paths.get(BackupManager.SAVE_DIR + u);
            if (Files.exists(path))
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                }
        }
    }

    @BeforeEach
    void setUp() {
        discovery = new DiscoveryServer();
        CompletableFuture.runAsync(() -> discovery.start());
    }

    @AfterEach
    void tearDown() throws IOException {
        discovery.close();
        deleteBackups();
    }

    @Test
    void connect() throws ExecutionException, InterruptedException, TimeoutException, IOException, DiscoveryUnreachableException {
        CompletableFuture<String> c1Promise = new CompletableFuture<>();
        CompletableFuture<String> c2Promise = new CompletableFuture<>();
        try (
                PeerNetManager p1 = new PeerNetManager(ID1, 12345, e -> {
                }, e -> c1Promise.complete((String) e.getNewValue()), e -> {
                });
                PeerNetManager p2 = new PeerNetManager(ID2, 12346, e -> {
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
    void createRoom() throws ExecutionException, InterruptedException, TimeoutException, IOException, DiscoveryUnreachableException {
        System.out.println("-------CREATE ROOM----------------");
        CompletableFuture<String> c1Promise = new CompletableFuture<>();
        CompletableFuture<String> c2Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        try (
                PeerNetManager p1 = new PeerNetManager(ID1, 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> c1Promise.complete((String) e.getNewValue()), e -> {
                });
                PeerNetManager p2 = new PeerNetManager(ID2, 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                        e -> c2Promise.complete((String) e.getNewValue()), e -> {
                })
        ) {
            c1Promise.get(500, TimeUnit.MILLISECONDS);
            c2Promise.get(500, TimeUnit.MILLISECONDS);

            Set<String> users = Set.of(ID1, ID2);
            p1.getController().createRoom("room", users);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);

            assertEquals(users, chat1.getUsers());
            assertEquals("room", chat1.getName());

            assertEquals(users, chat2.getUsers());
            assertEquals("room", chat2.getName());
        }
    }

    @Test
    void sendMsg() throws ExecutionException, InterruptedException, TimeoutException, IOException, DiscoveryUnreachableException {
        System.out.println("-------sendMsg----------------");
        CompletableFuture<String> c1Promise = new CompletableFuture<>();
        CompletableFuture<String> c2Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        CompletableFuture<StringMessage> msg1Promise = new CompletableFuture<>();
        CompletableFuture<StringMessage> msg2Promise = new CompletableFuture<>();
        try (
                PeerNetManager p1 = new PeerNetManager(ID1, 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> c1Promise.complete((String) e.getNewValue()),
                        e -> msg1Promise.complete((StringMessage) ((MessageGUI) e.getNewValue()).message()));

                PeerNetManager p2 = new PeerNetManager(ID2, 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                        e -> c2Promise.complete((String) e.getNewValue()),
                        e -> msg2Promise.complete((StringMessage) ((MessageGUI) e.getNewValue()).message()))
        ) {
            PeerController c1 = p1.getController();
            System.out.println(c1Promise.get(500, TimeUnit.MILLISECONDS));
            System.out.println(c2Promise.get(500, TimeUnit.MILLISECONDS));

            Set<String> users = Set.of(ID1, ID2);
            c1.createRoom("room", users);

            ChatRoom chat = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            chat2Promise.get(500, TimeUnit.MILLISECONDS);

            c1.sendMessage("TEST", chat);

            StringMessage m1 = msg1Promise.get(500, TimeUnit.MILLISECONDS);
            StringMessage m2 = msg2Promise.get(1000, TimeUnit.MILLISECONDS);

            assertEquals("TEST", m1.msg());
            assertEquals(ID1, m1.sender());

            assertEquals("TEST", m2.msg());
            assertEquals(ID1, m2.sender());

        }
    }

    @Test
    void vcTest() throws ExecutionException, InterruptedException, TimeoutException, IOException, DiscoveryUnreachableException {
        System.out.println("-------vcTest----------------");
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat3Promise = new CompletableFuture<>();
        CompletableFuture<StringMessage> msg1Promise = new CompletableFuture<>();
        CompletableFuture<StringMessage> msg2Promise = new CompletableFuture<>();
        List<StringMessage> msg3List = new CopyOnWriteArrayList<>();
        CountDownLatch msg3 = new CountDownLatch(2);
        CountDownLatch users1 = new CountDownLatch(2);
        CountDownLatch users2 = new CountDownLatch(2);
        CountDownLatch users3 = new CountDownLatch(2);
        try (
                PeerNetManager p1 = new PeerNetManager(ID1, 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> users1.countDown(),
                        e -> msg1Promise.complete((StringMessage) ((MessageGUI) e.getNewValue()).message()));

                PeerNetManager p2 = new PeerNetManager(ID2, 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                        e -> users2.countDown(),
                        e -> msg2Promise.complete((StringMessage) ((MessageGUI) e.getNewValue()).message()));

                PeerNetManager p3 = new PeerNetManager(ID3, 12347, e -> chat3Promise.complete((ChatRoom) e.getNewValue()),
                        e -> users3.countDown(),
                        e -> {
                            msg3List.add((StringMessage) ((MessageGUI) e.getNewValue()).message());
                            msg3.countDown();
                        })
        ) {
            PeerController c1 = p1.getController();
            PeerController c2 = p2.getController();
            assertTrue(users1.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users2.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users3.await(500, TimeUnit.MILLISECONDS));

            Set<String> users = Set.of(ID1, ID2, ID3);
            c1.createRoom("room", users);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat3 = chat3Promise.get(500, TimeUnit.MILLISECONDS);

            c1.degradeConnection(ID3);

            c1.sendMessage("TEST", chat1);

            StringMessage m1 = msg1Promise.get(500, TimeUnit.MILLISECONDS);
            StringMessage m2 = msg2Promise.get(500, TimeUnit.MILLISECONDS);

            Thread.sleep(500);
            assertTrue(msg3List.isEmpty());

            assertEquals("TEST", m1.msg());
            assertEquals(ID1, m1.sender());

            assertEquals("TEST", m2.msg());
            assertEquals(ID1, m2.sender());

            c2.sendMessage("TEST2", chat2);

            for (int i = 0; i < 50; i++) {
                Thread.sleep(10);
                if (1 == chat3.getWaitingMessages().size())
                    break;
            }
            assertTrue(msg3List.isEmpty());
            assertEquals(1, chat3.getWaitingMessages().size());
            assertEquals("TEST2", ((StringMessage) chat3.getWaitingMessages().stream().findFirst().orElseThrow()).msg());

            assertTrue(msg3.await(2000, TimeUnit.MILLISECONDS));

            assertEquals("TEST", msg3List.get(0).msg());
            assertEquals("TEST2", msg3List.get(1).msg());
        }
    }

    @Test
    void disconnectTest() throws ExecutionException, InterruptedException, TimeoutException, IOException, DiscoveryUnreachableException {
        System.out.println("-------disconnectTest----------------");
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat3Promise = new CompletableFuture<>();
        CompletableFuture<StringMessage> msg1Promise = new CompletableFuture<>();
        CompletableFuture<StringMessage> msg2Promise = new CompletableFuture<>();
        CompletableFuture<StringMessage> msg3Promise = new CompletableFuture<>();
        CountDownLatch users1 = new CountDownLatch(2);
        CountDownLatch users2 = new CountDownLatch(2);
        CountDownLatch users3 = new CountDownLatch(2);
        CompletableFuture<String> disc1Promise = new CompletableFuture<>();
        CompletableFuture<String> disc3Promise = new CompletableFuture<>();
        CountDownLatch disc2 = new CountDownLatch(2);
        try (
                PeerNetManager p1 = new PeerNetManager(ID1, 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users1.countDown();
                            else
                                disc1Promise.complete((String) e.getOldValue());
                        },
                        e -> msg1Promise.complete((StringMessage) ((MessageGUI) e.getNewValue()).message()));

                PeerNetManager p2 = new PeerNetManager(ID2, 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users2.countDown();
                            else
                                disc2.countDown();
                        },
                        e -> msg2Promise.complete((StringMessage) ((MessageGUI) e.getNewValue()).message()));

                PeerNetManager p3 = new PeerNetManager(ID3, 12347, e -> chat3Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users3.countDown();
                            else
                                disc3Promise.complete((String) e.getOldValue());
                        },
                        e -> msg3Promise.complete((StringMessage) ((MessageGUI) e.getNewValue()).message()))
        ) {
            PeerController c1 = p1.getController();
            assertTrue(users1.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users2.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users3.await(500, TimeUnit.MILLISECONDS));

            Set<String> users = Set.of(ID1, ID2, ID3);
            c1.createRoom("room", users);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat3 = chat3Promise.get(500, TimeUnit.MILLISECONDS);

            p2.disconnect();

            assertTrue(disc2.await(500, TimeUnit.MILLISECONDS));
            assertEquals(ID2, disc1Promise.get(500, TimeUnit.MILLISECONDS));
            assertEquals(ID2, disc3Promise.get(500, TimeUnit.MILLISECONDS));

            c1.sendMessage("TEST", chat1);

            StringMessage m1 = msg1Promise.get(500, TimeUnit.MILLISECONDS);
            StringMessage m3 = msg3Promise.get(500, TimeUnit.MILLISECONDS);
            assertThrows(TimeoutException.class, () -> msg2Promise.get(500, TimeUnit.MILLISECONDS));

            assertEquals("TEST", m1.msg());
            assertEquals(ID1, m1.sender());

            assertEquals("TEST", m3.msg());
            assertEquals(ID1, m3.sender());

            assertNotNull(c1.getDisconnectMsgs(ID2));
            assertEquals(1, c1.getDisconnectMsgs(ID2).size());
            var msg = ((MessagePacket) c1.getDisconnectMsgs(ID2).stream().findFirst().orElseThrow()).msg();
            assertEquals("TEST", msg.msg());
            assertEquals(ID1, msg.sender());

            p2.start();

            StringMessage m2 = msg2Promise.get(500, TimeUnit.MILLISECONDS);
            assertEquals("TEST", m2.msg());
            assertEquals(ID1, m2.sender());

            for (int i = 0; i < 50; i++) {
                if (c1.getDisconnectMsgs(ID2) == null || c1.getDisconnectMsgs(ID2).isEmpty())
                    break;
                Thread.sleep(10);
            }
            assertTrue(c1.getDisconnectMsgs(ID2) == null || c1.getDisconnectMsgs(ID2).isEmpty());
        }
    }

    @Test
    void netFailTest() throws ExecutionException, InterruptedException, TimeoutException, IOException, DiscoveryUnreachableException {
        System.out.println("-------netFailTest----------------");
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        CompletableFuture<StringMessage> msg1Promise = new CompletableFuture<>();
        CompletableFuture<StringMessage> msg2Promise = new CompletableFuture<>();
        CompletableFuture<String> users1Promise = new CompletableFuture<>();
        CompletableFuture<String> users2Promise = new CompletableFuture<>();
        CompletableFuture<String> disc1Promise = new CompletableFuture<>();
        CompletableFuture<String> disc2Promise = new CompletableFuture<>();

        AtomicReference<ImproperShutdownSocket> socketRef = new AtomicReference<>();
        try (
                PeerNetManager p1 = new PeerNetManager(ID1, 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users1Promise.complete((String) e.getNewValue());
                            else
                                disc1Promise.complete((String) e.getOldValue());
                        },
                        e -> msg1Promise.complete((StringMessage) ((MessageGUI) e.getNewValue()).message()));

                PeerNetManager p2 = new PeerNetManager(ID2, 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users2Promise.complete((String) e.getNewValue());
                            else
                                disc2Promise.complete((String) e.getOldValue());
                        },
                        e -> msg2Promise.complete((StringMessage) ((MessageGUI) e.getNewValue()).message())) {
                    @Override
                    protected PeerSocketManager createSocketManager() throws IOException {
                        if (socketRef.get() == null) {
                            ImproperShutdownSocket s = new ImproperShutdownSocket(port);
                            socketRef.set(s);
                            return new PeerSocketManager(getId(), executorService, discoveryAddr, 1000, s);
                        }
                        return super.createSocketManager();
                    }
                }
        ) {
            PeerController c1 = p1.getController();
            PeerController c2 = p2.getController();
            users1Promise.get(500, TimeUnit.MILLISECONDS);
            users2Promise.get(500, TimeUnit.MILLISECONDS);

            Set<String> users = Set.of(ID1, ID2);
            c1.createRoom("room", users);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);

            Thread.sleep(10);
            socketRef.get().lock();

            c2.sendMessage("TEST", chat2);

            StringMessage m2 = msg2Promise.get(500, TimeUnit.MILLISECONDS);
            assertEquals("TEST", m2.msg());
            assertEquals(ID2, m2.sender());

            assertEquals(ID1, disc2Promise.get(2, TimeUnit.SECONDS));
            var disc = c2.getDisconnectMsgs(ID1);
            assertEquals(1, disc.size());
            var msg = ((MessagePacket) disc.stream().findFirst().orElseThrow()).msg();
            assertEquals("TEST", msg.msg());
            assertEquals(ID2, msg.sender());

            assertThrows(TimeoutException.class, () -> msg1Promise.get(500, TimeUnit.MILLISECONDS));

            socketRef.get().unlock();

            //In 5 seconds should reconnect
            StringMessage m1 = msg1Promise.get(6, TimeUnit.SECONDS);
            assertEquals("TEST", m1.msg());
            assertEquals(ID2, m1.sender());

            for (int i = 0; i < 50; i++) {
                if (c1.getDisconnectMsgs(ID2) == null || c1.getDisconnectMsgs(ID2).isEmpty())
                    break;
                Thread.sleep(10);
            }
            assertTrue(c1.getDisconnectMsgs(ID2) == null || c1.getDisconnectMsgs(ID2).isEmpty());

        }
    }

    @Test
    void ackLost_duplicatedMessage_test() throws ExecutionException, InterruptedException, TimeoutException, IOException, DiscoveryUnreachableException {
        System.out.println("-------ackLost_duplicatedMessage_test----------------");
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat3Promise = new CompletableFuture<>();

        CompletableFuture<StringMessage> msg3Promise = new CompletableFuture<>();
        CountDownLatch msg1 = new CountDownLatch(2);
        CountDownLatch msg2 = new CountDownLatch(2);
        List<StringMessage> msg1List = new CopyOnWriteArrayList<>();
        List<StringMessage> msg2List = new CopyOnWriteArrayList<>();

        CountDownLatch users1 = new CountDownLatch(2);
        CompletableFuture<String> p1_userReconnect = new CompletableFuture<>();
        CountDownLatch users2 = new CountDownLatch(2);
        CountDownLatch users3 = new CountDownLatch(2);
        CompletableFuture<String> p3_userReconnect = new CompletableFuture<>();

        AtomicReference<ImproperShutdownSocket> socket_p2 = new AtomicReference<>();

        CompletableFuture<String> disc1Promise = new CompletableFuture<>();
        CompletableFuture<String> disc2Promise = new CompletableFuture<>();
        CompletableFuture<String> disc3Promise = new CompletableFuture<>();
        try (
                PeerNetManager p1 = new PeerNetManager(ID1, 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                if (disc1Promise.isDone())
                                    p1_userReconnect.complete((String) e.getNewValue());
                                else
                                    users1.countDown();
                            else
                                disc1Promise.complete((String) e.getOldValue());
                        },
                        e -> {
                            msg1List.add((StringMessage) ((MessageGUI) e.getNewValue()).message());
                            msg1.countDown();
                        });

                PeerNetManager p2 = new PeerNetManager(ID2, 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users2.countDown();
                            else
                                disc2Promise.complete((String) e.getOldValue());
                        },
                        e -> {
                            msg2List.add((StringMessage) ((MessageGUI) e.getNewValue()).message());
                            msg2.countDown();
                        }) {
                    @Override
                    protected PeerSocketManager createSocketManager() throws IOException {
                        if (socket_p2.get() == null) {
                            ImproperShutdownSocket s = new ImproperShutdownSocket(port);
                            socket_p2.set(s);
                            return new PeerSocketManager(getId(), executorService, discoveryAddr, 1000, s);
                        }
                        return super.createSocketManager();
                    }
                };

                PeerNetManager p3 = new PeerNetManager(ID3, 12347, e -> chat3Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                if (disc1Promise.isDone())
                                    p3_userReconnect.complete((String) e.getNewValue());
                                else
                                    users3.countDown();
                            else
                                disc3Promise.complete((String) e.getOldValue());
                        },
                        e -> msg3Promise.complete((StringMessage) ((MessageGUI) e.getNewValue()).message()))
        ) {
            PeerController c1 = p1.getController();
            PeerController c3 = p3.getController();
            assertTrue(users1.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users2.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users3.await(500, TimeUnit.MILLISECONDS));

            Set<String> users = Set.of(ID1, ID2, ID3);
            c1.createRoom("room", users);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat3 = chat3Promise.get(500, TimeUnit.MILLISECONDS);

            Thread.sleep(10);
            socket_p2.get().lock();

            c1.sendMessage("TEST", chat1);

            var m3 = msg3Promise.get(500, TimeUnit.MILLISECONDS);
            assertEquals("TEST", m3.msg());
            assertEquals(ID1, m3.sender());

            disc1Promise.get(2, TimeUnit.SECONDS);

            c3.sendMessage("TEST2", chat3);

            assertTrue(msg1.await(500, TimeUnit.MILLISECONDS));

            assertEquals(2, msg1List.size());
            var m1_0 = msg1List.get(0);
            assertEquals("TEST", m1_0.msg());
            assertEquals(ID1, m1_0.sender());

            var m1_1 = msg1List.get(1);
            assertEquals("TEST2", m1_1.msg());
            assertEquals(ID3, m1_1.sender());

            assertFalse(msg2.await(500, TimeUnit.MILLISECONDS));
            assertEquals(1, msg2.getCount());
            assertEquals(1, msg2List.size());
            var m2_0 = msg2List.get(0);
            assertEquals("TEST", m2_0.msg());
            assertEquals(ID1, m2_0.sender());

            disc3Promise.get(2, TimeUnit.SECONDS);

            socket_p2.get().unlock();

            assertEquals(ID2, p1_userReconnect.get(3, TimeUnit.SECONDS));
            assertEquals(ID2, p3_userReconnect.get(3, TimeUnit.SECONDS));

            //Peer p1 still has to resend msg1 to p2 because the ack got lost,
            // p2 will ignore the packet
            // Peer p3 still ha to resend msg2 to p2, because the packet got lost
            //Waits for 1 seconds and check.
            Thread.sleep(1000);
            assertTrue(msg2.await(500, TimeUnit.MILLISECONDS));

            assertEquals(2, msg2List.size());
            var m2_1 = msg2List.get(1);
            assertEquals("TEST2", m2_1.msg());
            assertEquals(ID3, m2_1.sender());
        }
    }


    @Test
    void messageLostTest() throws ExecutionException, InterruptedException, TimeoutException, IOException, DiscoveryUnreachableException {
        System.out.println("-------messageLostTest----------------");
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat3Promise = new CompletableFuture<>();

        CompletableFuture<StringMessage> msg3Promise = new CompletableFuture<>();
        CountDownLatch msg1 = new CountDownLatch(2);
        CountDownLatch msg2 = new CountDownLatch(2);
        List<StringMessage> msg1List = new CopyOnWriteArrayList<>();
        List<StringMessage> msg2List = new CopyOnWriteArrayList<>();

        CountDownLatch users1 = new CountDownLatch(2);
        CompletableFuture<String> userReconnect = new CompletableFuture<>();
        CountDownLatch users2 = new CountDownLatch(2);
        CountDownLatch users3 = new CountDownLatch(2);

        AtomicReference<BlockingDatagramSocket> socket_p2_ref = new AtomicReference<>();

        CompletableFuture<String> disc1Promise = new CompletableFuture<>();
        CompletableFuture<String> disc2Promise = new CompletableFuture<>();
        CompletableFuture<String> disc3Promise = new CompletableFuture<>();

        try (
                PeerNetManager p1 = new PeerNetManager(ID1, 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users1.countDown();
                            else
                                disc1Promise.complete((String) e.getOldValue());
                        },
                        e -> {
                            msg1List.add((StringMessage) ((MessageGUI) e.getNewValue()).message());
                            msg1.countDown();
                        });

                PeerNetManager p2 = new PeerNetManager(ID2, 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
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
                            msg2List.add((StringMessage) ((MessageGUI) e.getNewValue()).message());
                            msg2.countDown();
                        }) {
                    @Override
                    protected PeerSocketManager createSocketManager() throws IOException {
                        if (socket_p2_ref.get() == null) {
                            BlockingDatagramSocket s = new BlockingDatagramSocket(port);
                            socket_p2_ref.set(s);
                            return new PeerSocketManager(getId(), executorService, discoveryAddr, 1000, s);
                        }
                        return super.createSocketManager();
                    }

                };

                PeerNetManager p3 = new PeerNetManager(ID3, 12347, e -> chat3Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users3.countDown();
                            else
                                disc3Promise.complete((String) e.getOldValue());
                        },
                        e -> msg3Promise.complete((StringMessage) ((MessageGUI) e.getNewValue()).message()))
        ) {
            PeerController c1 = p1.getController();
            PeerController c2 = p2.getController();
            PeerController c3 = p3.getController();
            assertTrue(users1.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users2.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users3.await(500, TimeUnit.MILLISECONDS));

            Set<String> users = Set.of(ID1, ID2, ID3);
            c1.createRoom("room", users);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat3 = chat3Promise.get(500, TimeUnit.MILLISECONDS);

            Thread.sleep(10);
            final SocketAddress addr1 = new InetSocketAddress("localhost", 12345);
            socket_p2_ref.get().lock(addr1);

            c2.sendMessage("TEST", chat2);

            var m3 = msg3Promise.get(500, TimeUnit.MILLISECONDS);
            assertEquals("TEST", m3.msg());
            assertEquals(ID2, m3.sender());

            String disconnected = disc2Promise.get(10, TimeUnit.SECONDS);
            assertEquals(ID1, disconnected);

            assertTrue(msg1List.isEmpty());
            assertTrue(chat1.getWaitingMessages().isEmpty());

            assertEquals(1, c2.getDisconnectMsgs(ID1).size());
            MessagePacket mp = (MessagePacket) c2.getDisconnectMsgs(ID1).toArray(new P2PPacket[1])[0];
            assertEquals("TEST", mp.msg().msg());
            assertEquals(ID2, mp.msg().sender());

            c3.sendMessage("TEST2", chat3);

            assertTrue(msg2.await(500, TimeUnit.MILLISECONDS));

            assertEquals(2, msg2List.size());
            var m2_0 = msg2List.get(0);
            assertEquals("TEST", m2_0.msg());
            assertEquals(ID2, m2_0.sender());

            var m2_1 = msg2List.get(1);
            assertEquals("TEST2", m2_1.msg());
            assertEquals(ID3, m2_1.sender());

            Thread.sleep(200);
            var waiting = chat1.getWaitingMessages();
            assertEquals(1, waiting.size());
            StringMessage w_mess = (StringMessage) waiting.toArray(new Message[1])[0];
            assertEquals("TEST2", w_mess.msg());
            assertEquals(ID3, w_mess.sender());

            socket_p2_ref.get().unlock(addr1);

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

            assertTrue(chat1.getWaitingMessages().isEmpty());
            assertTrue(c2.getDisconnectMsgs(ID1).isEmpty());
        }
    }


    @Test
    void messageForwarded2Discovery() throws ExecutionException, InterruptedException, TimeoutException, IOException, DiscoveryUnreachableException {
        System.out.println("-------messageForwarded2Discovery----------------");
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat3Promise = new CompletableFuture<>();

        CompletableFuture<StringMessage> msg3Promise = new CompletableFuture<>();
        CountDownLatch msg1 = new CountDownLatch(2);
        CountDownLatch msg2 = new CountDownLatch(2);
        List<StringMessage> msg1List = new CopyOnWriteArrayList<>();
        List<StringMessage> msg2List = new CopyOnWriteArrayList<>();

        CountDownLatch users1 = new CountDownLatch(2);
        CompletableFuture<String> userReconnect = new CompletableFuture<>();
        CountDownLatch users2 = new CountDownLatch(2);
        CountDownLatch users3 = new CountDownLatch(2);

        AtomicReference<BlockingDatagramSocket> socket_p2_ref = new AtomicReference<>();

        CompletableFuture<String> disc1Promise = new CompletableFuture<>();
        CompletableFuture<String> disc2Promise = new CompletableFuture<>();
        CompletableFuture<String> disc3Promise = new CompletableFuture<>();

        try (
                PeerNetManager p1 = new PeerNetManager(ID1, 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users1.countDown();
                            else
                                disc1Promise.complete((String) e.getOldValue());
                        },
                        e -> {
                            msg1List.add((StringMessage) ((MessageGUI) e.getNewValue()).message());
                            msg1.countDown();
                        });

                PeerNetManager p2 = new PeerNetManager(ID2, 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
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
                            msg2List.add((StringMessage) ((MessageGUI) e.getNewValue()).message());
                            msg2.countDown();
                        }) {
                    @Override
                    protected PeerSocketManager createSocketManager() throws IOException {
                        if (socket_p2_ref.get() == null) {
                            BlockingDatagramSocket s = new BlockingDatagramSocket(port);
                            socket_p2_ref.set(s);
                            return new PeerSocketManager(getId(), executorService, discoveryAddr, 1000, s);
                        }
                        return super.createSocketManager();
                    }

                };

                PeerNetManager p3 = new PeerNetManager(ID3, 12347, e -> chat3Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users3.countDown();
                            else
                                disc3Promise.complete((String) e.getOldValue());
                        },
                        e -> msg3Promise.complete((StringMessage) ((MessageGUI) e.getNewValue()).message()))
        ) {
            PeerController c1 = p1.getController();
            PeerController c2 = p2.getController();
            PeerController c3 = p3.getController();
            assertTrue(users1.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users2.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users3.await(500, TimeUnit.MILLISECONDS));

            Set<String> users = Set.of(ID1, ID2, ID3);
            c1.createRoom("room", users);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat3 = chat3Promise.get(500, TimeUnit.MILLISECONDS);

            Thread.sleep(10);
            final SocketAddress addr1 = new InetSocketAddress("localhost", 12345);
            socket_p2_ref.get().lock(addr1);

            c2.sendMessage("TEST", chat2);

            var m3 = msg3Promise.get(500, TimeUnit.MILLISECONDS);
            assertEquals("TEST", m3.msg());
            assertEquals(ID2, m3.sender());

            String disconnected = disc2Promise.get(10, TimeUnit.SECONDS);
            assertEquals(ID1, disconnected);

            assertTrue(msg1List.isEmpty());
            assertTrue(chat1.getWaitingMessages().isEmpty());

            assertEquals(1, c2.getDisconnectMsgs(ID1).size());
            MessagePacket mp = (MessagePacket) c2.getDisconnectMsgs(ID1).toArray(new P2PPacket[1])[0];
            assertEquals("TEST", mp.msg().msg());
            assertEquals(ID2, mp.msg().sender());

            c3.sendMessage("TEST2", chat3);

            assertTrue(msg2.await(500, TimeUnit.MILLISECONDS));

            assertEquals(2, msg2List.size());
            var m2_0 = msg2List.get(0);
            assertEquals("TEST", m2_0.msg());
            assertEquals(ID2, m2_0.sender());

            var m2_1 = msg2List.get(1);
            assertEquals("TEST2", m2_1.msg());
            assertEquals(ID3, m2_1.sender());

            Thread.sleep(200);
            var waiting = chat1.getWaitingMessages();
            assertEquals(1, waiting.size());
            StringMessage w_mess = (StringMessage) waiting.toArray(new Message[1])[0];
            assertEquals("TEST2", w_mess.msg());
            assertEquals(ID3, w_mess.sender());

            p2.disconnect();

            assertEquals(2, msg2List.size());

            assertTrue(msg1.await(2, TimeUnit.SECONDS));
            assertEquals(2, msg1List.size());
            var m1_0 = msg1List.get(0);
            assertEquals("TEST", m1_0.msg());
            assertEquals(ID2, m1_0.sender());

            var m1_1 = msg1List.get(1);
            assertEquals("TEST2", m1_1.msg());
            assertEquals(ID3, m1_1.sender());

            assertEquals(ID2, disc1Promise.get(500, TimeUnit.MILLISECONDS));
            assertEquals(ID2, disc3Promise.get(500, TimeUnit.MILLISECONDS));

            assertTrue(chat1.getWaitingMessages().isEmpty());
            assertNull(c2.getDisconnectMsgs(ID1));
        }
    }


    @Test
    void bigMessageForwarded2Discovery() throws ExecutionException, InterruptedException, TimeoutException, IOException, DiscoveryUnreachableException {
        System.out.println("-------messageForwarded2Discovery----------------");
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat3Promise = new CompletableFuture<>();

        CompletableFuture<StringMessage> msg3Promise = new CompletableFuture<>();
        CountDownLatch msg1 = new CountDownLatch(2);
        CountDownLatch msg2 = new CountDownLatch(2);
        List<StringMessage> msg1List = new CopyOnWriteArrayList<>();
        List<StringMessage> msg2List = new CopyOnWriteArrayList<>();

        CountDownLatch users1 = new CountDownLatch(2);
        CompletableFuture<String> userReconnect = new CompletableFuture<>();
        CountDownLatch users2 = new CountDownLatch(2);
        CountDownLatch users3 = new CountDownLatch(2);

        AtomicReference<BlockingDatagramSocket> socket_p2_ref = new AtomicReference<>();

        CompletableFuture<String> disc1Promise = new CompletableFuture<>();
        CompletableFuture<String> disc2Promise = new CompletableFuture<>();
        CompletableFuture<String> disc3Promise = new CompletableFuture<>();

        try (
                PeerNetManager p1 = new PeerNetManager(ID1, 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users1.countDown();
                            else
                                disc1Promise.complete((String) e.getOldValue());
                        },
                        e -> {
                            msg1List.add((StringMessage) ((MessageGUI) e.getNewValue()).message());
                            msg1.countDown();
                        });

                PeerNetManager p2 = new PeerNetManager(ID2, 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
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
                            msg2List.add((StringMessage) ((MessageGUI) e.getNewValue()).message());
                            msg2.countDown();
                        }) {
                    @Override
                    protected PeerSocketManager createSocketManager() throws IOException {
                        if (socket_p2_ref.get() == null) {
                            BlockingDatagramSocket s = new BlockingDatagramSocket(port);
                            socket_p2_ref.set(s);
                            return new PeerSocketManager(getId(), executorService, discoveryAddr, 1000, s);
                        }
                        return super.createSocketManager();
                    }

                };

                PeerNetManager p3 = new PeerNetManager(ID3, 12347, e -> chat3Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users3.countDown();
                            else
                                disc3Promise.complete((String) e.getOldValue());
                        },
                        e -> msg3Promise.complete((StringMessage) ((MessageGUI) e.getNewValue()).message()))
        ) {
            PeerController c1 = p1.getController();
            PeerController c2 = p2.getController();
            PeerController c3 = p3.getController();
            assertTrue(users1.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users2.await(500, TimeUnit.MILLISECONDS));
            assertTrue(users3.await(500, TimeUnit.MILLISECONDS));

            Set<String> users = Set.of(ID1, ID2, ID3);
            c1.createRoom("room", users);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat3 = chat3Promise.get(500, TimeUnit.MILLISECONDS);

            Thread.sleep(10);
            final SocketAddress addr1 = new InetSocketAddress("localhost", 12345);
            socket_p2_ref.get().lock(addr1);

            c2.sendMessage("TEST", chat2);

            var m3 = msg3Promise.get(500, TimeUnit.MILLISECONDS);
            assertEquals("TEST", m3.msg());
            assertEquals(ID2, m3.sender());

            String disconnected = disc2Promise.get(10, TimeUnit.SECONDS);
            assertEquals(ID1, disconnected);

            assertTrue(msg1List.isEmpty());
            assertTrue(chat1.getWaitingMessages().isEmpty());

            assertEquals(1, c2.getDisconnectMsgs(ID1).size());
            MessagePacket mp = (MessagePacket) c2.getDisconnectMsgs(ID1).toArray(new P2PPacket[1])[0];
            assertEquals("TEST", mp.msg().msg());
            assertEquals(ID2, mp.msg().sender());

            c3.sendMessage("TEST2", chat3);

            assertTrue(msg2.await(500, TimeUnit.MILLISECONDS));

            assertEquals(2, msg2List.size());
            var m2_0 = msg2List.get(0);
            assertEquals("TEST", m2_0.msg());
            assertEquals(ID2, m2_0.sender());

            var m2_1 = msg2List.get(1);
            assertEquals("TEST2", m2_1.msg());
            assertEquals(ID3, m2_1.sender());

            Thread.sleep(200);
            var waiting = chat1.getWaitingMessages();
            assertEquals(1, waiting.size());
            StringMessage w_mess = (StringMessage) waiting.toArray(new Message[1])[0];
            assertEquals("TEST2", w_mess.msg());
            assertEquals(ID3, w_mess.sender());

            for(int i =0;i<5000;i++){
                c2.sendMessage(STR."Veeeeeery long message \{i}", chat2);
            }

            p2.disconnect();

            assertEquals(2, msg2List.size());

            assertTrue(msg1.await(2, TimeUnit.SECONDS));
            assertEquals(2, msg1List.size());
            var m1_0 = msg1List.get(0);
            assertEquals("TEST", m1_0.msg());
            assertEquals(ID2, m1_0.sender());

            var m1_1 = msg1List.get(1);
            assertEquals("TEST2", m1_1.msg());
            assertEquals(ID3, m1_1.sender());

            assertEquals(ID2, disc1Promise.get(500, TimeUnit.MILLISECONDS));
            assertEquals(ID2, disc3Promise.get(500, TimeUnit.MILLISECONDS));

            assertTrue(chat1.getWaitingMessages().isEmpty());
            assertNull(c2.getDisconnectMsgs(ID1));
        }
    }

    @Test
    void backupTest() throws ExecutionException, InterruptedException, TimeoutException, IOException, DiscoveryUnreachableException {
        System.out.println("-------backupTest----------------");
        CompletableFuture<ChatRoom> chat1Promise = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise = new CompletableFuture<>();

        CountDownLatch msg1Latch = new CountDownLatch(2);
        CountDownLatch msg2Latch = new CountDownLatch(4);

        CompletableFuture<String> users1Promise = new CompletableFuture<>();
        CompletableFuture<String> users2Promise = new CompletableFuture<>();

        try (
                PeerNetManager p1 = new PeerNetManager(ID1, 12345, e -> chat1Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users1Promise.complete((String) e.getNewValue());
                        },
                        e -> msg1Latch.countDown());

                PeerNetManager p2 = new PeerNetManager(ID2, 12346, e -> chat2Promise.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users2Promise.complete((String) e.getNewValue());
                        },
                        e -> msg2Latch.countDown())
        ) {
            PeerController c1 = p1.getController();
            PeerController c2 = p2.getController();

            users1Promise.get(500, TimeUnit.MILLISECONDS);
            users2Promise.get(500, TimeUnit.MILLISECONDS);

            Set<String> users = Set.of(ID1, ID2);
            c1.createRoom("room", users);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);

            c2.sendMessage("TEST", chat2);
            c2.sendMessage("TEST2", chat2);

            assertTrue(msg1Latch.await(500, TimeUnit.MILLISECONDS));

            c1.sendMessage("TEST3", chat1);
            c1.sendMessage("TEST4", chat1);

            assertTrue(msg2Latch.await(500, TimeUnit.MILLISECONDS));
        }


        //Reopen peers
        CompletableFuture<StringMessage> msg1Promise = new CompletableFuture<>();
        CompletableFuture<StringMessage> msg2Promise = new CompletableFuture<>();

        CompletableFuture<ChatRoom> chat1Promise_new = new CompletableFuture<>();
        CompletableFuture<ChatRoom> chat2Promise_new = new CompletableFuture<>();

        CompletableFuture<String> users1Promise_new = new CompletableFuture<>();
        CompletableFuture<String> users2Promise_new = new CompletableFuture<>();

        try (
                PeerNetManager p1 = new PeerNetManager(ID1, 12345, e -> chat1Promise_new.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users1Promise_new.complete((String) e.getNewValue());
                        },
                        e -> msg1Promise.complete((StringMessage) ((MessageGUI) e.getNewValue()).message()));

                PeerNetManager p2 = new PeerNetManager(ID2, 12346, e -> chat2Promise_new.complete((ChatRoom) e.getNewValue()),
                        e -> {
                            if (e.getPropertyName().equals("USER_CONNECTED"))
                                users2Promise_new.complete((String) e.getNewValue());
                        },
                        e -> msg2Promise.complete((StringMessage) ((MessageGUI) e.getNewValue()).message()))
        ) {
            PeerController c1 = p1.getController();
            PeerController c2 = p2.getController();

            users1Promise.get(500, TimeUnit.MILLISECONDS);
            users2Promise.get(500, TimeUnit.MILLISECONDS);

            ChatRoom chat1 = chat1Promise.get(500, TimeUnit.MILLISECONDS);
            ChatRoom chat2 = chat2Promise.get(500, TimeUnit.MILLISECONDS);

            var savedList1 = chat1.getReceivedMsgs().toArray(new StringMessage[4]);
            var savedList2 = chat2.getReceivedMsgs().toArray(new StringMessage[4]);

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

            c2.sendMessage("new test", chat2);
            c1.sendMessage("new test2", chat1);

            var m1 = msg1Promise.get(500, TimeUnit.MILLISECONDS);
            assertEquals("new test", m1.msg());
            assertEquals(ID2, m1.sender());

            var m2 = msg2Promise.get(500, TimeUnit.MILLISECONDS);
            assertEquals("new test2", m2.msg());
            assertEquals(ID1, m2.sender());
        }
    }
}
