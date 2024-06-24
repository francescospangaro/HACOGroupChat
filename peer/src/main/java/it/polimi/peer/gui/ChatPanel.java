package it.polimi.peer.gui;

import it.polimi.peer.ChatRoom;
import it.polimi.peer.exceptions.DiscoveryUnreachableException;
import it.polimi.peer.PeerController;
import it.polimi.peer.PeerNetManager;
import it.polimi.peer.utility.MessageGUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatPanel {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatPanel.class);

    private JPanel panel;
    private JTextArea msgArea;
    private JButton sendButton;
    private JButton newChatButton;
    private JComboBox<ChatRoom> chatRooms;
    private JButton deleteButton;
    private JButton disconnectReconnectButton;
    private JList<MessageBubble> msgList;
    private JLabel usernameLabel;
    private JLabel portLabel;
    private JLabel connectedLabel;
    private JList<String> connectedList;
    private JLabel chatLabel;
    private JPanel left;
    private JCheckBox detailedViewCheckBox;
    private JButton delaysButton;
    private volatile PeerNetManager peerNetManager;
    private volatile PeerController peerController;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private DefaultListModel<MessageBubble> msgListModel = new DefaultListModel<>();

    public ChatPanel(JFrame frame, String discovery, String user, int port) {
        //Want to create a new Group for chatting
        newChatButton.addActionListener(e -> executorService.execute(() -> {
            CreateRoomDialog dialog = new CreateRoomDialog(peerNetManager.getIps().keySet());

            if (dialog.isConfirmed()) {
                Set<String> users = dialog.getSelectedUsers();
                users.add(peerNetManager.getId());
                String name = dialog.getRoomName();

                //Request the creation of the room with this name and with all the users
                peerController.createRoom(name, users);
            }
        }));

        //Want to send a message to a ChatRoom
        sendButton.addActionListener(e -> executorService.execute(this::send));

        msgArea.registerKeyboardAction(e -> {
            if (sendButton.isEnabled()) {
                executorService.execute(this::send);
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK), JComponent.WHEN_FOCUSED);

        deleteButton.addActionListener(e -> executorService.execute(() -> {
            ChatRoom toDelete = (ChatRoom) chatRooms.getSelectedItem();
            peerController.deleteRoom(toDelete);
        }));

        disconnectReconnectButton.addActionListener(e ->
                setConnected(!peerNetManager.isConnected())
        );

        chatRooms.addItemListener(e -> {
            if (e.getID() == ItemEvent.ITEM_STATE_CHANGED) {
                if (chatRooms.getItemCount() > 0 && chatRooms.getSelectedItem() != null) {
                    chatLabel.setText("Chat: " + ((ChatRoom) chatRooms.getSelectedItem()).getName());
                    msgListModel.clear();
                    msgListModel.addAll(((ChatRoom) chatRooms.getSelectedItem()).getReceivedMsgs().stream().map(m -> {
                        if (m.sender().equals(user))
                            return new RightArrowBubble(m.sender(), detailedViewCheckBox.isSelected() ? m.toDetailedString() : m.toString());
                        else
                            return new LeftArrowBubble(m.sender(), detailedViewCheckBox.isSelected() ? m.toDetailedString() : m.toString());
                    }).toList());
                    msgList.ensureIndexIsVisible(msgListModel.size() - 1);
                } else {
                    chatLabel.setText("Chat: -");
                    msgListModel.clear();
                }
            }
        });

        Dimension colDim = new Dimension(Math.max(300, panel.getWidth() / 3), -1);
        left.setMinimumSize(colDim);
        left.setPreferredSize(colDim);
        left.setMaximumSize(colDim);
        panel.revalidate();
        panel.repaint();

        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent windowEvent) {
                if (peerNetManager != null) {
                    try {
                        peerNetManager.close();
                        executorService.shutdownNow();
                        System.exit(0);
                    } catch (DiscoveryUnreachableException e) {
                        JOptionPane.showMessageDialog(JOptionPane.getRootFrame(),
                                "Failed to disconnect: discovery unreachable",
                                "Error",
                                JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        });
        panel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                super.componentResized(e);
                var d = new Dimension(Math.max(300, e.getComponent().getWidth() / 3), -1);
                left.setMinimumSize(d);
                left.setPreferredSize(d);
                left.setMaximumSize(d);
                panel.revalidate();
                panel.repaint();
            }
        });

        sendButton.setEnabled(false);
        deleteButton.setEnabled(false);
        disconnectReconnectButton.setEnabled(false);
        newChatButton.setEnabled(false);

        portLabel.setText(String.valueOf(port));
        usernameLabel.setText(user);

        msgList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> value);
        msgList.setModel(msgListModel);

        DefaultListModel<String> connectedModelList = new DefaultListModel<>();
        connectedList.setModel(connectedModelList);

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            LOGGER.error(STR."Unexpected error in thread \{t}. The application will terminate", e);
            JOptionPane.showMessageDialog(JOptionPane.getRootFrame(),
                    "Unexpected error. The application will terminate.\n" + e,
                    "Fatal error",
                    JOptionPane.ERROR_MESSAGE);

            //Try to disconnect before killing
            if (peerNetManager != null && peerNetManager.isConnected()) {
                try {
                    peerNetManager.close();
                } catch (DiscoveryUnreachableException ex) {
                    LOGGER.error("Can't reach the discovery server", ex);
                }
            }

            System.exit(-1);
        });

        executorService.execute(() -> {
            try {
                peerNetManager = new PeerNetManager(discovery, user, port, evt -> SwingUtilities.invokeLater(() -> {
                    if (evt.getPropertyName().equals("ADD_ROOM")) {
                        ChatRoom chat = (ChatRoom) evt.getNewValue();
                        LOGGER.trace(STR."Room \{chat} added in gui");
                        chatRooms.addItem(chat);
                        sendButton.setEnabled(true);
                        deleteButton.setEnabled(true);
                    } else if (evt.getPropertyName().equals("DEL_ROOM")) {
                        ChatRoom chat = (ChatRoom) evt.getOldValue();
                        LOGGER.trace(STR."Room \{chat} removed from gui");
                        chatRooms.removeItem(chat);
                        if (chatRooms.getItemCount() == 0) {
                            sendButton.setEnabled(false);
                            deleteButton.setEnabled(false);
                        }
                    }
                }), evt -> SwingUtilities.invokeLater(() -> {
                    if (evt.getPropertyName().equals("USER_CONNECTED")) {
                        connectedModelList.addElement((String) evt.getNewValue());
                    } else {
                        connectedModelList.removeElement(evt.getOldValue());
                    }
                }), evt -> {
                    if (evt.getPropertyName().equals("ADD_MSG")) {
                        MessageGUI mgui = (MessageGUI) evt.getNewValue();

                        LOGGER.trace(STR."Msg \{mgui} added in gui");

                        if (((ChatRoom) chatRooms.getSelectedItem()).getId().equals(mgui.chatRoom().getId())) {
                            SwingUtilities.invokeLater(() -> {
                                if (mgui.message().sender().equals(user))
                                    msgListModel.addElement(new RightArrowBubble(mgui.message().sender(), detailedViewCheckBox.isSelected() ? mgui.message().toDetailedString() : mgui.message().toString()));
                                else
                                    msgListModel.addElement(new LeftArrowBubble(mgui.message().sender(), detailedViewCheckBox.isSelected() ? mgui.message().toDetailedString() : mgui.message().toString()));
                                msgList.ensureIndexIsVisible(msgListModel.size() - 1);
                            });
                        }
                    }
                });
            } catch (IOException e) {
                JOptionPane.showMessageDialog(JOptionPane.getRootFrame(),
                        "Can't establish cthe connection. The application will terminate.\n" + e,
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                System.exit(-1);
            }
            LOGGER.info(STR."Started \{peerNetManager}");
            peerController = peerNetManager.getController();
            SwingUtilities.invokeLater(() -> {
                connectedLabel.setText("Connected");
                connectedLabel.setForeground(new Color(0, 153, 51));
                disconnectReconnectButton.setEnabled(true);
                newChatButton.setEnabled(true);
            });
        });
        detailedViewCheckBox.addActionListener(e -> {
            msgListModel.clear();
            msgListModel.addAll(((ChatRoom) chatRooms.getSelectedItem()).getReceivedMsgs().stream().map(m -> {
                if (m.sender().equals(user))
                    return new RightArrowBubble(m.sender(), detailedViewCheckBox.isSelected() ? m.toDetailedString() : m.toString());
                else
                    return new LeftArrowBubble(m.sender(), detailedViewCheckBox.isSelected() ? m.toDetailedString() : m.toString());
            }).toList());
        });

        delaysButton.addActionListener(e -> {
            DegradeConnectionsDialog dialog = new DegradeConnectionsDialog(peerNetManager.getIps().keySet(), peerController.getDegradedConnections());

            if (dialog.isConfirmed()) {
                peerController.resetDegradedConnections();
                peerController.degradeConnections(dialog.getDelays());
            }
        });
    }

    private void send() {
        String msg = msgArea.getText().trim();

        SwingUtilities.invokeLater(() -> msgArea.setText(""));

        if (msg.isEmpty())
            return;

        //Get the ChatRoom selected by the user in which he wants to send the msg
        ChatRoom chat = (ChatRoom) chatRooms.getSelectedItem();

        peerController.sendMessage(msg, chat);
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void setConnected(boolean connected) {
        if (connected) {
            disconnectReconnectButton.setEnabled(false);
            connectedLabel.setText("Connecting...");
            connectedLabel.setForeground(Color.yellow);
            executorService.execute(() -> {
                try {
                    peerNetManager.start();
                    peerController = peerNetManager.getController();
                    SwingUtilities.invokeLater(() -> {
                        connectedLabel.setText("Connected");
                        connectedLabel.setForeground(new Color(0, 153, 51));

                        disconnectReconnectButton.setText("Disconnect");
                        disconnectReconnectButton.setEnabled(true);
                    });
                } catch (IOException e) {
                    JOptionPane.showMessageDialog(JOptionPane.getRootFrame(),
                            "Can't establish cthe connection.\n" + e,
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                    SwingUtilities.invokeLater(() -> {
                        connectedLabel.setText("Disconnected");
                        connectedLabel.setForeground(Color.red);

                        disconnectReconnectButton.setText("Reconnect");
                        disconnectReconnectButton.setEnabled(true);
                    });
                }
            });
        } else {
            disconnectReconnectButton.setEnabled(false);
            executorService.execute(() -> {
                try {
                    peerNetManager.disconnect();
                    SwingUtilities.invokeLater(() -> {
                        connectedLabel.setText("Disconnected");
                        connectedLabel.setForeground(Color.red);

                        disconnectReconnectButton.setText("Reconnect");
                        disconnectReconnectButton.setEnabled(true);
                    });
                } catch (DiscoveryUnreachableException e) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(JOptionPane.getRootFrame(),
                                "Failed to disconnect: discovery unreachable",
                                "Error",
                                JOptionPane.ERROR_MESSAGE);
                        disconnectReconnectButton.setEnabled(true);
                    });
                }
            });
        }
    }

    public JPanel getRootPanel() {
        return panel;
    }
}
