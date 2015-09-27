package org.openpnp.gui.components;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

import javax.swing.JComponent;

import org.openpnp.ConfigurationListener;
import org.openpnp.JobProcessorListener;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Placement;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Head;
import org.openpnp.spi.JobProcessor;
import org.openpnp.spi.JobProcessor.JobError;
import org.openpnp.spi.JobProcessor.JobState;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.Utils2D;

public class NavigationView extends JComponent implements JobProcessorListener, MachineListener, MouseWheelListener, MouseListener {
    // we need min and max so that we have limits, think of camera trying to go
    // to 0 on this machine. crashes.
    // TODO: Don't forget conversion when this is coming from it's real source.
    // If we use this as the base units we can just convert everything else to it.
    Location machineExtents = new Location(LengthUnit.Millimeters, 430, 410, 0, 0);
    double zoomScale = 1;
    double offsetX = -machineExtents.getX() / 2, offsetY = -machineExtents.getY() / 2;
    
    public NavigationView() {
        setBackground(Color.black);
        addMouseWheelListener(this);
        addMouseListener(this);
        Configuration.get().addListener(new ConfigurationListener() {
            @Override
            public void configurationLoaded(Configuration configuration)
                    throws Exception {
            }
            
            @Override
            public void configurationComplete(Configuration configuration)
                    throws Exception {
                configuration.getMachine().addListener(NavigationView.this);
                for (JobProcessor jobProcessor : configuration.getMachine().getJobProcessors().values()) {
                    jobProcessor.addListener(NavigationView.this);
                }
            }
        });
    }
    
    /**
     * Rendering tasks:
     * bed
     * boardlocations / boards
     * placements
     * feeders
     * head
     * 
     * Do we want continuous zoom? Or just overview and focus?
     * For the latter, if the user has a large bed and small window the objects could be quite small.
     * Does that mean we need scrolling? I think we definitely do not want scrolling
     * Let's say we have a window 1280x720 to work with (mine is 1700x1200), that means a mm for my
     * bed would only be 2.9 pixels. An 8mm tape would only be 23 mm wide
     * Maybe in the overview we need contnuous scroll (no object selected) but when you select an object we
     * zoom in on it and lock the zoom? Or maybe we zoom in and you can still zoom out if you want. Why not?
     * 
     * 
     */
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        Insets ins = getInsets();
        int width = getWidth() - ins.left - ins.right;
        int height = getHeight() - ins.top - ins.bottom;
        // cancel out the insets
        g2d.translate(ins.left, ins.top);
        g2d.setClip(0, 0, width, height);
        
        // fill the background
        g2d.setColor(getBackground());
        g2d.fillRect(0, 0, width, height);
        
        // add a small border just to make things look nice
        int borderSize = 8;
        width -= borderSize * 2;
        height -= borderSize * 2;
        g2d.translate(borderSize, borderSize);
        g2d.setClip(0, 0, width, height);
        
        // center the drawing
        g2d.translate(width / 2,  height / 2);
        
        // determine the base scale which will fit the entire bed in the window
        double bedWidth = machineExtents.getX();
        double bedHeight = machineExtents.getY();
        double xScale = width / bedWidth;
        double yScale = height / bedHeight;
        double baseScale = Math.min(xScale, yScale);

        // scale the drawing to the final size.
        g2d.scale(baseScale * zoomScale, baseScale * zoomScale);

        g2d.translate(offsetX, offsetY);
        
        // draw the bed
        g2d.setColor(Color.lightGray);
        g2d.fillRect(0, 0, (int) machineExtents.getX(), (int) machineExtents.getY());
        
        Machine machine = Configuration.get().getMachine();
        JobProcessor jobProcessor = MainFrame.jobPanel.getJobProcessor();
        Job job = jobProcessor.getJob();
        if (job != null) {
            // draw the boards
            for (BoardLocation boardLocation : job.getBoardLocations()) {
                Location location = boardLocation.getLocation();
                paintCrosshair(g2d, location, Color.green);
                // draw the placements on the boards
                for (Placement placement : boardLocation.getBoard().getPlacements()) {
                    if (placement.getSide() != boardLocation.getSide()) {
                        continue;
                    }
                    Location placementLocation = Utils2D.calculateBoardPlacementLocation(boardLocation.getLocation(), boardLocation.getSide(), placement.getLocation());
                    paintCrosshair(g2d, placementLocation, Color.magenta);
                }
            }
        }
        
        // draw the feeders
        for (Feeder feeder : machine.getFeeders()) {
            try {
                Location location = feeder.getPickLocation();
                paintCrosshair(g2d, location, Color.white);
            }
            catch (Exception e) {
                
            }
        }
        
        // draw fixed cameras
        for (Camera camera : machine.getCameras()) {
            Location location = camera.getLocation();
            paintCrosshair(g2d, location, Color.cyan);
        }
         
        // draw the head
        for (Head head : machine.getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                Location location = nozzle.getLocation();
                paintCrosshair(g2d, location, Color.red);
            }
            
            for (Camera camera : head.getCameras()) {
                Location location = camera.getLocation();
                paintCrosshair(g2d, location, Color.blue);
            }
            
            for (Actuator actuator : head.getActuators()) {
                Location location = actuator.getLocation();
                paintCrosshair(g2d, location, Color.yellow);
            }
        }
    }
    
    private void paintCrosshair(Graphics2D g2d, Location location, Color color) {
        int height = (int) machineExtents.getY();
        g2d.setColor(color);
        int x = (int) location.getX();
        int y = (int) location.getY();
        g2d.drawLine(x - 3, height - y, x + 3, height - y);
        g2d.drawLine(x, height - y - 3, x, height - y + 3);
    }
    
    @Override
    public void jobLoaded(Job job) {
        repaint();
    }

    @Override
    public void jobStateChanged(JobState state) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void jobEncounteredError(JobError error, String description) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void partProcessingStarted(BoardLocation board, Placement placement) {
    }

    @Override
    public void partPicked(BoardLocation board, Placement placement) {
    }

    @Override
    public void partPlaced(BoardLocation board, Placement placement) {
    }

    @Override
    public void partProcessingCompleted(BoardLocation board, Placement placement) {
    }

    @Override
    public void detailedStatusUpdated(String status) {
    }

    @Override
    public void machineHeadActivity(Machine machine, Head head) {
        repaint();
    }

    @Override
    public void machineEnabled(Machine machine) {
    }

    @Override
    public void machineEnableFailed(Machine machine, String reason) {
    }

    @Override
    public void machineDisabled(Machine machine, String reason) {
    }

    @Override
    public void machineDisableFailed(Machine machine, String reason) {
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        zoomScale += -e.getWheelRotation() * 0.01;
        zoomScale = Math.max(zoomScale, 1);
        // when we change the zoom we want the position under the mouse cursor
        // to remain the same. this means we need to know the unscaled position
        // under the cursor, the scaled position and the difference in offsets
        // we should have functions that get the current mouse position in
        // machine coordinates and calculate the offset for same
//        offsetX += -e.getWheelRotation() * 0.01;
//        offsetY += -e.getWheelRotation() * 0.01;
        repaint();
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        int x = e.getX();
        int y = e.getY();
        final Camera camera = Configuration.get().getMachine().getHeads().get(0).getCameras().get(0);
        final Location location = new Location(LengthUnit.Millimeters,  x / zoomScale, machineExtents.getY() - (y / zoomScale), Double.NaN, Double.NaN);
        
        MainFrame.machineControlsPanel.submitMachineTask(new Runnable() {
            public void run() {
                try {
                    MovableUtils.moveToLocationAtSafeZ(camera, location, 1.0);
                }
                catch (Exception e) {
                    MessageBoxes.errorBox(getTopLevelAncestor(),
                            "Move Error", e);
                }
            }
        });
    }

    @Override
    public void mousePressed(MouseEvent e) {
    }

    @Override
    public void mouseReleased(MouseEvent e) {
    }

    @Override
    public void mouseEntered(MouseEvent e) {
    }

    @Override
    public void mouseExited(MouseEvent e) {
    }
}
