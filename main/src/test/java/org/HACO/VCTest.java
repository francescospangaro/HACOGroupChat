package org.HACO;

import org.HACO.discovery.DiscoveryServer;
import org.HACO.packets.Message;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VCTest {


    @BeforeAll
    static void setUp() {
        CompletableFuture.runAsync(new DiscoveryServer());
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

}