package it.polimi.gui;

import it.polimi.utility.Randomize;

import javax.swing.*;

public class ConnectionPanel {
    private JTextField discoveryText;
    private JTextField userText;
    private JTextField portText;
    private JButton connectButton;
    private JPanel panel;
    private JLabel errorLabel;

    public ConnectionPanel(JFrame frame) {
        connectButton.addActionListener(e -> {
            String discAddr = discoveryText.getText();

            int port;
            try {
                port = Integer.parseInt(portText.getText());
                if (port <= 1024 || port > 65535) {
                    errorLabel.setText("Invalid data");
                    return;
                }
            } catch (NumberFormatException ignored) {
                errorLabel.setText("Invalid data");
                return;
            }

            String user = userText.getText();
            if (user.isEmpty()) {
                errorLabel.setText("Invalid data");
                return;
            }

            frame.setContentPane(new ChatPanel(frame, discAddr, user, port).getRootPanel());
        });
        frame.getRootPane().setDefaultButton(connectButton);

        portText.setText(String.valueOf(Randomize.generateRandomPort()));
        userText.setText(Randomize.generateRandomString(5));
    }

    public JPanel getRootPanel() {
        return panel;
    }

}
