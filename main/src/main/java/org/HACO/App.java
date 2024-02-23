package org.HACO;

import javax.swing.*;
import java.awt.*;
import java.util.Set;

public class App {
    private JPanel panel1;
    private JTextArea msgArea;
    private JButton sendButton;
    private JButton newButton;
    private JComboBox<ChatRoom> chatRooms;
    private JButton deleteButton;
    private JButton disconnectReconnectButton;
    private JList<String> msgList;
    private JLabel usernameLabel;
    private JLabel portLabel;
    private JLabel connectedLabel;
    private Client client;



    public App() {
        //Want to create a new Group for chatting
        newButton.addActionListener(e -> {
            System.out.println(client);
            CreateRoom dialog = new CreateRoom(client.getIps().keySet());

            if (dialog.isConfirmed()) {
                Set<String> users = dialog.getSelectedUsers();
                users.add(client.getId());
                String name = dialog.getRoomName();

                //Request the creation of the room with this name and with all the users
                client.createRoom(name, users);
            }
        });

        //Want to send a message to a ChatRoom
        sendButton.addActionListener(e -> {
            String msg = msgArea.getText();
            //Get the ChatRoom selected by the user in which he wants to send the msg
            ChatRoom chat = (ChatRoom) chatRooms.getSelectedItem();

            client.sendMessage(msg, chat);
            msgArea.setText("");
        });

        deleteButton.addActionListener(e -> {
            ChatRoom toDelete = (ChatRoom) chatRooms.getSelectedItem();
            System.out.println("Deleting room " + toDelete.getId());
            chatRooms.removeItem(toDelete);
            client.deleteRoom(toDelete);
        });
        disconnectReconnectButton.addActionListener(e -> {
            this.setConnected(!client.getConnected());
        });
    }

    public void start() {
        JFrame frame = new JFrame();
        frame.setContentPane(panel1);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setSize(d.width / 2, d.height / 2);
        frame.setVisible(true);

        int port = -1;
        do {
            try {
                port = Integer.parseInt(JOptionPane.showInputDialog(frame, "Insert a port", 12345));
            } catch (NumberFormatException ignored) {
                System.err.println(ignored);
            }
        } while (port <= 1024);

        portLabel.setText(String.valueOf(port));

        String id = JOptionPane.showInputDialog(frame, "Insert an id");
        usernameLabel.setText(id);

        DefaultListModel<String> msgListModel = new DefaultListModel<>();
        msgList.setModel(msgListModel);


        client = new Client(id, port, evt -> {
            if (evt.getPropertyName().equals("ADD_ROOM")) {
                System.out.println("Rooms added in gui");
                ChatRoom chat = (ChatRoom) evt.getNewValue();
                chatRooms.addItem(chat);
            } else if (evt.getPropertyName().equals("DEL_ROOM")) {
                System.out.println("Rooms removed from gui");
                ChatRoom chat = (ChatRoom) evt.getOldValue();
                chatRooms.removeItem(chat);
            }
        }, evt -> {
            if (evt.getPropertyName().equals("ADD_MSG")) {
                System.out.println("Msg added in gui");
                msgListModel.addElement(evt.getNewValue().toString());
            }
        });
        System.out.println("started " + client);
        this.setConnected(true);

    }

    private void setConnected(boolean connected){
        client.setConnected(connected);

        if(connected){
            connectedLabel.setText("connected");
            connectedLabel.setForeground(new Color(0, 153, 51));

            disconnectReconnectButton.setText("Disconnect");
        }else{
            connectedLabel.setText("disconnected");
            connectedLabel.setForeground(Color.red);

            disconnectReconnectButton.setText("Reconnect");
        }
    }


    public static void main(String[] args) {
        new App().start();
    }
}
