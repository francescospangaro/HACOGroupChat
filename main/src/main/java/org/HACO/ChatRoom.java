package org.HACO;

import java.util.List;
import java.util.Random;

public class ChatRoom {
    private final List<String> users;
    private List<Message> receivedMsgs;
    private final String id;
    public ChatRoom(List<String> users){
        this.users = users;
        this.id = initId();
    }

    private String initId(){
        Random random = new Random();
        String temp = "";
        for(int i = 0; i < 10; i++){
            if(i % 4 == 0)
                temp = temp.concat(String.valueOf(random.nextInt(10)));
            else
                temp = temp.concat(Character.toString((char) random.nextInt(255)));
        }
        return temp;
    }

}
