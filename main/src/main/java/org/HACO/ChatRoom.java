package org.HACO;

import java.util.Random;

public class ChatRoom {
    private final int maxUsers;
    private final String id;
    public ChatRoom(int maxUsers){
        this.maxUsers = maxUsers;
        this.id = initId();
    }

    private String initId(){
        Random random = new Random();
        String temp = "";
        for(int i = 0; i < 10; i++){
            if(i % 4 == 0)
                temp = temp.concat(String.valueOf(random.nextInt()));
            else
                temp = temp.concat(Character.toString((char) random.nextInt()));
        }
        return temp;
    }

}
