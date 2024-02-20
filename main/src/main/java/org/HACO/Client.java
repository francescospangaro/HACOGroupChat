package org.HACO;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Client {
    private List<Integer> msgOrder;
    private List<ChatRoom> chats;
    private static Scanner input = new Scanner(System.in);

    public void startingMenu() {
        int choice;

        System.out.println("""
                Choose one:\t
                1. Create a new chatroom;\t
                2. Join a chatroom with a specific id.\t
                """);
        choice = input.nextInt();
        switch (choice) {
            case 1:
                try {
                    chats.add(createChatRoom());
                } catch (NoIpsInsertedException e) {
                    throw new RuntimeException(e);
                }
                break;
            case 2:
                joinChat();
                break;
            default:
                System.out.println("Closing the application.\n");
                disconnect();
                break;
        }
    }

    private ChatRoom createChatRoom() throws NoIpsInsertedException {
        String s;
        List<String> ips = new ArrayList<>();
        System.out.println("Insert a component's IP(nothing for exit)\n");
        s = input.nextLine();
        while (s != null) {
            ips.add(s);
            System.out.println("Insert a component's IP(nothing for exit)\n");
            s = input.nextLine();
        }
        if (ips.size() == 0)
            throw new NoIpsInsertedException();
        return new ChatRoom(ips);
    }

    private void joinChat(){

    }
    private void disconnect(){}

    private void sendMessage(){
        String msg = input.nextLine();
        //

    }
}