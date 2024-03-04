package org.HACO;

import org.HACO.packets.Message;
import org.HACO.packets.MessageGUI;
import org.HACO.utility.Randomize;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

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
    private JLabel chatLable;
    private volatile Peer peer;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private DefaultListModel<String> msgListModel = new DefaultListModel<>();

    public App() {
        //Want to create a new Group for chatting
        newChatButton.addActionListener(e -> executorService.execute(() -> {
            System.out.println(peer);
            CreateRoom dialog = new CreateRoom(peer.getIps().keySet());

            if (dialog.isConfirmed()) {
                Set<String> users = dialog.getSelectedUsers();
                users.add(peer.getId());
                String name = dialog.getRoomName();

                //Request the creation of the room with this name and with all the users
                peer.createRoom(name, users);
            }
        }));

        //Want to send a message to a ChatRoom
        sendButton.addActionListener(e -> executorService.execute(() -> {
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

            peer.sendMessage(msg, chat, delay);

            SwingUtilities.invokeLater(() -> msgArea.setText(""));
        }));


        deleteButton.addActionListener(e -> executorService.execute(() -> {
            ChatRoom toDelete = (ChatRoom) chatRooms.getSelectedItem();
            System.out.println("Deleting room " + toDelete.getId());
            peer.deleteRoom(toDelete);
        }));

        disconnectReconnectButton.addActionListener(e ->
                setConnected(!peer.isConnected())
        );

        chatRooms.addItemListener(e -> {
            if (e.getID() == ItemEvent.ITEM_STATE_CHANGED) {
                if (chatRooms.getItemCount() > 0 && chatRooms.getSelectedItem() != null) {
                    chatLable.setText("Chat: " + ((ChatRoom) chatRooms.getSelectedItem()).getName());
                    msgListModel.clear();

                    msgListModel.addAll(0, ((ChatRoom) chatRooms.getSelectedItem()).getReceivedMsgs().stream()
                            .map(Message::toString)
                            .collect(Collectors.toList()));
                } else {
                    msgListModel.clear();
                }
            }
        });


        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            e.printStackTrace();
            JOptionPane.showMessageDialog(JOptionPane.getRootFrame(),
                    "Unexpected error. The application will terminate.\n" + e.getMessage(),
                    "Fatal error",
                    JOptionPane.ERROR_MESSAGE);

            //Try to disconnect before killing
            if (peer != null && peer.isConnected()) {
                peer.close();
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
                peer.close();
            }
        });

        sendButton.setEnabled(false);
        deleteButton.setEnabled(false);
        delayTime.setColumns(10);

        int port = -1;
        do {
            try {
                String input = JOptionPane.showInputDialog(frame, "Insert a port", Randomize.generateRandomUDPPort());
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
            id = JOptionPane.showInputDialog(frame, "Insert an id", Randomize.generateRandomString(5));
            if (id == null) {
                System.exit(0);
            }
        } while (id.isEmpty());
        usernameLabel.setText(id);


        msgList.setModel(msgListModel);

        DefaultListModel<String> connectedModelList = new DefaultListModel<>();
        connectedList.setModel(connectedModelList);


        peer = new Peer(id, port, evt -> {
            SwingUtilities.invokeLater(() -> {
                if (evt.getPropertyName().equals("ADD_ROOM")) {
                    ChatRoom chat = (ChatRoom) evt.getNewValue();
                    System.out.println("Room " + chat + " added in gui");
                    chatRooms.addItem(chat);
                    sendButton.setEnabled(true);
                    deleteButton.setEnabled(true);
                } else if (evt.getPropertyName().equals("DEL_ROOM")) {
                    ChatRoom chat = (ChatRoom) evt.getOldValue();
                    System.out.println("Room " + chat + " removed from gui");
                    chatRooms.removeItem(chat);
                    if (chatRooms.getItemCount() == 0) {
                        sendButton.setEnabled(false);
                        deleteButton.setEnabled(false);
                    }
                }
            });
        }, evt -> {
            SwingUtilities.invokeLater(() -> {
                if (evt.getPropertyName().equals("USER_CONNECTED")) {
                    connectedModelList.addElement((String) evt.getNewValue());
                } else {
                    System.out.println("Removing " + evt.getOldValue() + " " + connectedModelList.removeElement(evt.getOldValue()));
                }
            });
        }, evt -> {
            if (evt.getPropertyName().equals("ADD_MSG")) {
                System.out.println("Msg added in gui");

                MessageGUI mgui = (MessageGUI) evt.getNewValue();

                if (((ChatRoom) chatRooms.getSelectedItem()).getId().equals(mgui.chatRoom().getId())) {
                    SwingUtilities.invokeLater(() -> {
                        msgListModel.addElement(mgui.message().toString());
                        msgListModel.clear();
                        msgListModel.addAll(0, mgui.chatRoom().getReceivedMsgs().stream()
                                .map(Message::toString)
                                .collect(Collectors.toList()));

                        chatLable.setText("Chat: " + mgui.chatRoom());
                    });
                }
            }
        }, false);
        System.out.println("started " + peer);
    }

    private void setConnected(boolean connected) {
        if (connected) {
            disconnectReconnectButton.setEnabled(false);
            executorService.execute(() -> {
                peer.start();
                SwingUtilities.invokeLater(() -> {
                    connectedLabel.setText("connected");
                    connectedLabel.setForeground(new Color(0, 153, 51));

                    disconnectReconnectButton.setText("Disconnect");
                    disconnectReconnectButton.setEnabled(true);
                });
            });
        } else {
            disconnectReconnectButton.setEnabled(false);
            executorService.execute(() -> {
                peer.disconnect();
                SwingUtilities.invokeLater(() -> {
                    connectedLabel.setText("disconnected");
                    connectedLabel.setForeground(Color.red);

                    disconnectReconnectButton.setText("Reconnect");
                    disconnectReconnectButton.setEnabled(true);
                });
            });
        }
    }

    public static void main(String[] args) {
        new App().start();
    }
}
