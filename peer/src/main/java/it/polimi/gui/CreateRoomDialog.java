package it.polimi.gui;

import javax.swing.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.HashSet;
import java.util.Set;

public class CreateRoomDialog extends JDialog {
    private JPanel contentPane;
    private JButton buttonOK;
    private JButton buttonCancel;
    private JScrollPane scrollPane;
    private JList<String> userList;
    private JTextField roomName;

    private boolean confirmed = false;

    public CreateRoomDialog(Set<String> users) {
        setContentPane(contentPane);
        setModal(true);
        getRootPane().setDefaultButton(buttonOK);

        buttonOK.addActionListener(e -> onOK());

        buttonCancel.addActionListener(e -> onCancel());

        // call onCancel() when cross is clicked
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                onCancel();
            }
        });

        DefaultListModel<String> listModel = new DefaultListModel<>();
        listModel.addAll(users);
        userList.setModel(listModel);

        pack();
        // call onCancel() on ESCAPE
        contentPane.registerKeyboardAction(e -> onCancel(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

        setVisible(true);
    }

    private void onOK() {
        // add your code here
        if (!roomName.getText().isBlank()) {
            confirmed = true;
            dispose();
        }
    }

    private void onCancel() {
        // add your code here if necessary
        dispose();
    }

    public String getRoomName() {
        return roomName.getText().trim();
    }

    public Set<String> getSelectedUsers() {
        return new HashSet<>(userList.getSelectedValuesList());
    }

    public boolean isConfirmed() {
        return confirmed;
    }
}
