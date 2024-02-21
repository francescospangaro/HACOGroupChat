package org.HACO.packets;

import org.HACO.Client;

import java.util.List;

public record Message(int processId, String dataSent, Client sender, String chatId, List<Integer> vectorClocks) {
}
