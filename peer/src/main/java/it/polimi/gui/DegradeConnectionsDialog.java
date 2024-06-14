package it.polimi.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class DegradeConnectionsDialog extends JDialog {
    private JPanel contentPane;
    private JButton buttonOK;
    private JButton buttonCancel;
    private JPanel delaysPanel;
    private boolean confirmed;

    public DegradeConnectionsDialog(Set<String> users, Map<String, Integer> delays) {
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

        delaysPanel.setLayout(new GridLayout(users.size(), 1));
        users.forEach(u -> delaysPanel.add(new Row(u, delays.getOrDefault(u, 0))));
        pack();
        confirmed = false;

        // call onCancel() on ESCAPE
        contentPane.registerKeyboardAction(e -> onCancel(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

        setVisible(true);
    }

    private void onOK() {
        // add your code here
        confirmed = true;
        dispose();
    }

    private void onCancel() {
        // add your code here if necessary
        dispose();
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public Map<String, Integer> getDelays() {
        Map<String, Integer> m = new HashMap<>();

        Arrays.stream(delaysPanel.getComponents())
                .map(r -> (Row) r)
                .forEach(r -> {
                    if (r.getDelay() > 0)
                        m.put(r.user, r.getDelay());
                });
        return m;
    }

    private class Row extends JPanel {
        private final String user;
        private final JTextField delay;

        public Row(String user, int delay) {
            this.user = user;
            setLayout(new GridLayout(1, 2));
            add(new JLabel(user));
            add(this.delay = new JTextField(String.valueOf(delay)));
            pack();
        }

        public int getDelay() {
            int d = 0;
            try {
                d = Integer.parseInt(delay.getText());
            } catch (NumberFormatException _) {
            }
            if (d < 0)
                d = 0;
            return d;
        }
    }

}
