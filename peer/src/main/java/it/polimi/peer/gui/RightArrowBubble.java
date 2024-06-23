package it.polimi.peer.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;

public class RightArrowBubble extends MessageBubble {
    private int strokeThickness = 3;
    private int radius = 10;
    private int arrowSize = 12;
    private int padding = strokeThickness / 2;

    public RightArrowBubble(String sender, String msg) {
        super(sender, msg);
        msgPanelLayout.setHorizontalGroup(
                msgPanelLayout.createParallelGroup(GroupLayout.Alignment.LEADING)
                        .addGroup(msgPanelLayout.createSequentialGroup()
                                .addGap(10, 10, 10)
                                .addComponent(this.msg)
                                .addContainerGap(20, Short.MAX_VALUE))
                        .addGroup(msgPanelLayout.createSequentialGroup()
                                .addGap(10, 10, 10)
                                .addComponent(this.sender)
                                .addContainerGap(20, Short.MAX_VALUE))
        );
    }

    @Override
    protected void paintComponent(final Graphics g) {
        final Graphics2D g2d = (Graphics2D) g;
        g2d.setColor(new Color(0.5f, 0.5f, 1f));
        int bottomLineY = getHeight() - strokeThickness;
        int width = getWidth() - arrowSize - (strokeThickness * 2);
        g2d.fillRect(padding, padding, width, bottomLineY);
        RoundRectangle2D.Double rect = new RoundRectangle2D.Double(padding, padding, width, bottomLineY,  radius, radius);
        Polygon arrow = new Polygon();
        arrow.addPoint(width, 8);
        arrow.addPoint(width + arrowSize, 10);
        arrow.addPoint(width, 12);
        Area area = new Area(rect);
        area.add(new Area(arrow));
        g2d.setRenderingHints(new RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON));
        g2d.setStroke(new BasicStroke(strokeThickness));
        g2d.draw(area);
    }
}
