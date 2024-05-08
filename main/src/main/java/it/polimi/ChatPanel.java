package it.polimi;

import it.polimi.utility.Message;
import it.polimi.utility.MessageGUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
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
    private JList<String> msgList;
    private JLabel usernameLabel;
    private JLabel portLabel;
    private JLabel connectedLabel;
    private JTextField delayTime;
    private JList<String> connectedList;
    private JLabel chatLabel;
    private JPanel left;
    private JCheckBox detailedViewCheckBox;
    private volatile Peer peer;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private DefaultListModel<String> msgListModel = new DefaultListModel<>();

    public ChatPanel(JFrame frame, String discovery, String user, int port) {
        //Want to create a new Group for chatting
        newChatButton.addActionListener(e -> executorService.execute(() -> {
            CreateRoomDialog dialog = new CreateRoomDialog(peer.getIps().keySet());

            if (dialog.isConfirmed()) {
                Set<String> users = dialog.getSelectedUsers();
                users.add(peer.getId());
                String name = dialog.getRoomName();

                //Request the creation of the room with this name and with all the users
                peer.createRoom(name, users);
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
            peer.deleteRoom(toDelete);
        }));

        disconnectReconnectButton.addActionListener(e ->
                setConnected(!peer.isConnected())
        );

        chatRooms.addItemListener(e -> {
            if (e.getID() == ItemEvent.ITEM_STATE_CHANGED) {
                if (chatRooms.getItemCount() > 0 && chatRooms.getSelectedItem() != null) {
                    chatLabel.setText("Chat: " + ((ChatRoom) chatRooms.getSelectedItem()).getName());
                    msgListModel.clear();
                    if (detailedViewCheckBox.isSelected())
                        msgListModel.addAll(((ChatRoom) chatRooms.getSelectedItem()).getReceivedMsgs().stream().map(Message::toDetailedString).toList());
                    else
                        msgListModel.addAll(((ChatRoom) chatRooms.getSelectedItem()).getReceivedMsgs().stream().map(Message::toString).toList());
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

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent windowEvent) {
                if (peer != null)
                    peer.close();
                executorService.shutdownNow();
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

        delayTime.setColumns(10);

        portLabel.setText(String.valueOf(port));
        usernameLabel.setText(user);

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
            if (peer != null && peer.isConnected()) {
                peer.close();
            }

            System.exit(-1);
        });

        executorService.execute(() -> {
            peer = new Peer(discovery, user, port, evt -> SwingUtilities.invokeLater(() -> {
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
                            if (detailedViewCheckBox.isSelected())
                                msgListModel.addElement(mgui.message().toDetailedString());
                            else
                                msgListModel.addElement(mgui.message().toString());
                            msgList.ensureIndexIsVisible(msgListModel.size() - 1);
                        });
                    }
                }
            });
            LOGGER.info(STR."Started \{peer}");
            SwingUtilities.invokeLater(() -> {
                connectedLabel.setText("Connected");
                connectedLabel.setForeground(new Color(0, 153, 51));
                disconnectReconnectButton.setEnabled(true);
                newChatButton.setEnabled(true);
            });
        });
        detailedViewCheckBox.addActionListener(e -> {
            msgListModel.clear();
            if (detailedViewCheckBox.isSelected())
                msgListModel.addAll(((ChatRoom) chatRooms.getSelectedItem()).getReceivedMsgs().stream().map(Message::toDetailedString).toList());
            else
                msgListModel.addAll(((ChatRoom) chatRooms.getSelectedItem()).getReceivedMsgs().stream().map(Message::toString).toList());
        });
    }

    private void send() {
        String msg = msgArea.getText().trim();

        SwingUtilities.invokeLater(() -> msgArea.setText(""));

        if (msg.isEmpty())
            return;

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
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void setConnected(boolean connected) {
        if (connected) {
            disconnectReconnectButton.setEnabled(false);
            executorService.execute(() -> {
                connectedLabel.setText("Connecting...");
                connectedLabel.setForeground(Color.yellow);
                peer.start();
                SwingUtilities.invokeLater(() -> {
                    connectedLabel.setText("Connected");
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
                    connectedLabel.setText("Disconnected");
                    connectedLabel.setForeground(Color.red);

                    disconnectReconnectButton.setText("Reconnect");
                    disconnectReconnectButton.setEnabled(true);
                });
            });
        }
    }

    public JPanel getRootPanel() {
        return panel;
    }
}
