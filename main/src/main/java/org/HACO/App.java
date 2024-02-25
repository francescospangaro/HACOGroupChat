package org.HACO;

import javax.swing.*;
import java.awt.*;
import java.util.Set;

public class App {
    private JPanel panel1;
    private JTextArea msgArea;
    private JButton sendButton;
    private JButton newChatButton;
    private JComboBox<ChatRoom> chatRooms;
    private JButton deleteButton;
    private JButton disconnectReconnectButton;
    private JList<String> msgList;
    private JLabel usernameLabel;
    private JLabel portLabel;
    private JLabel connectedLabel;
    private JTextField delayTime;
    private JList<String> connectedList;
    private Client client;


    public App() {
        //Want to create a new Group for chatting
        newChatButton.addActionListener(e -> {
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

            int delay;
            try {
                delay = Integer.parseInt(delayTime.getText());
                if (delay < 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException ignored) {
                delay = 0;
            }

            client.sendMessage(msg, chat, delay);
            msgArea.setText("");
        });


        deleteButton.addActionListener(e -> {
            ChatRoom toDelete = (ChatRoom) chatRooms.getSelectedItem();
            System.out.println("Deleting room " + toDelete.getId());
            client.deleteRoom(toDelete);
        });
        disconnectReconnectButton.addActionListener(e -> {
            setConnected(!client.isConnected());
        });

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            e.printStackTrace();
            JOptionPane.showMessageDialog(JOptionPane.getRootFrame(),
                    "Unexpected error. The application will terminate.\n" + e.getMessage(),
                    "Fatal error",
                    JOptionPane.ERROR_MESSAGE);

            //Try to disconnect before killing
            if (client != null && client.isConnected()) {
                client.disconnect();
            }

            System.exit(-1);
        });
    }

    public void start() {
        JFrame frame = new JFrame();
        frame.setContentPane(panel1);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setSize(d.width / 2, d.height / 2);
        frame.setVisible(true);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent windowEvent) {
                client.disconnect();
            }
        });

        delayTime.setColumns(10);

        int port = -1;
        do {
            try {
                String input = JOptionPane.showInputDialog(frame, "Insert a port", 12345);
                if (input == null) {
                    System.exit(0);
                }
                port = Integer.parseInt(input);
            } catch (NumberFormatException e) {
                System.err.println("Port not valid " + e);
            }
        } while (port <= 1024 || port > 65535);

        portLabel.setText(String.valueOf(port));

        String id;
        do {
            id = JOptionPane.showInputDialog(frame, "Insert an id");
            if (id == null) {
                System.exit(0);
            }
        } while (id.isEmpty());
        usernameLabel.setText(id);

        DefaultListModel<String> msgListModel = new DefaultListModel<>();
        msgList.setModel(msgListModel);

        DefaultListModel<String> connectedModelList = new DefaultListModel<>();
        connectedList.setModel(connectedModelList);


        client = new Client(id, port, evt -> {
            if (evt.getPropertyName().equals("ADD_ROOM")) {
                ChatRoom chat = (ChatRoom) evt.getNewValue();
                System.out.println("Room " + chat + " added in gui");
                chatRooms.addItem(chat);
            } else if (evt.getPropertyName().equals("DEL_ROOM")) {
                ChatRoom chat = (ChatRoom) evt.getOldValue();
                System.out.println("Room " + chat + " removed from gui");
                chatRooms.removeItem(chat);
            }
        }, evt -> {
            if (evt.getPropertyName().equals("USER_CONNECTED")) {
                connectedModelList.addElement((String) evt.getNewValue());
            } else {
                System.out.println("Removing " + evt.getOldValue() + " " + connectedModelList.removeElement(evt.getOldValue()));
            }
        }, evt -> {
            if (evt.getPropertyName().equals("ADD_MSG")) {
                System.out.println("Msg added in gui");
                msgListModel.addElement(evt.getNewValue().toString());
            }
        });
        System.out.println("started " + client);
    }

    private void setConnected(boolean connected) {
        if (connected) {
            client.start();
            connectedLabel.setText("connected");
            connectedLabel.setForeground(new Color(0, 153, 51));

            disconnectReconnectButton.setText("Disconnect");
        } else {
            client.disconnect();
            connectedLabel.setText("disconnected");
            connectedLabel.setForeground(Color.red);

            disconnectReconnectButton.setText("Reconnect");
        }
    }

    public static void main(String[] args) {
        new App().start();
    }
}
