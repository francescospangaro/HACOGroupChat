package org.HACO;

import org.HACO.packets.P2PPacket;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class CheckReconnection extends Thread {
    private Set<Client> allPeers = ConcurrentHashMap.newKeySet();
    private Set<Client> toCheck = ConcurrentHashMap.newKeySet();
    private ChatRoom chatRoom;

    public CheckReconnection(Set<Client> toCheck, Set<Client> allPeers, ChatRoom chatRoom) {
        this.toCheck.addAll(toCheck);
        this.allPeers.addAll(allPeers);
        this.chatRoom = chatRoom;
    }

    @Override
    public void run() {
        while (!this.isInterrupted()) {
            Set<Client> reconnected = ConcurrentHashMap.newKeySet();
            Set<Client> alwaysConnected = ConcurrentHashMap.newKeySet();
            for (Client c : toCheck) {
                if (toCheck.contains(c) && !chatRoom.getDisconnectedPeers().contains(c))
                    reconnected.add(c);
            }
            alwaysConnected.addAll(allPeers.stream().filter(x -> allPeers.contains(x) && !toCheck.contains(x)).collect(Collectors.toSet()));
            for (Client c : alwaysConnected) {
                for (Client d : reconnected) {
                    List<P2PPacket> toSend = new CopyOnWriteArrayList<>(chatRoom.getDisconnectMsgs().get(d.getId()));
                    for (P2PPacket m : toSend)
                        c.sendSinglePeer(m, d.getId());
                }
            }
            toCheck = ConcurrentHashMap.newKeySet();
            toCheck.addAll(chatRoom.getDisconnectedPeers());
        }
    }
}
