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
    private JButton disconnectButton;
    private JList<String> msgList;
    private JLabel usernameLable;
    private JLabel portLable;
    private volatile Client client;

    public App() {
        //Want to create a new Group for chatting
        newButton.addActionListener(e -> {
            System.out.println(client);
            CreateRoom dialog = new CreateRoom(client.getOtherPeerAddress().keySet());

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
        });

        disconnectButton.addActionListener(e -> {
        });
    }

    public void start() {
        JFrame frame = new JFrame();
        frame.setContentPane(panel1);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setSize(d.width / 2, d.height / 2);
        frame.setVisible(true);

        Integer port = -1;
        do {
            try {
                port = Integer.parseInt(JOptionPane.showInputDialog(frame, "Insert a port", 12345));
            } catch (NumberFormatException ignored) {
                System.err.println(ignored);
            }
        } while (port <= 1024);

        portLable.setText(port.toString());

        String id = JOptionPane.showInputDialog(frame, "Insert an id");
        usernameLable.setText(id);

        DefaultListModel<String> msgListModel = new DefaultListModel<>();
        msgList.setModel(msgListModel);


        client = new Client(id, port, evt -> {
            if (evt.getPropertyName().equals("ADD_ROOM")) {
                System.out.println("Rooms added in gui");
                ChatRoom chat = (ChatRoom) evt.getNewValue();
                chatRooms.addItem(chat);
            } else if (evt.getPropertyName().equals("DEL_ROOM")) {
                System.out.println("Rooms added in gui");
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

    }

    public static void main(String[] args) {
        new App().start();
    }
}
