package it.polimi.messages;

import java.util.Map;

/**
 * String message object
 *
 * @param msg
 * @param vectorClocks
 * @param sender
 */
public record StringMessage(String msg, Map<String, Integer> vectorClocks, String sender) implements Message {
    @Override
    public String toString() {
        StringBuilder temp = new StringBuilder();
        for (int i = 0; i < msg.length(); i += 30) {
            int min = Math.min(i + 30, msg.length());
            temp.append(msg, i, min).append('\n');
            if ((min != msg.length()) &&
                (msg.charAt(i + 30) != ' ') &&
                (msg.charAt(i + 29) != ' ')) {
                temp.append('-');
            }
        }
        return temp.toString();
    }
}
