package org.openpnp.gui.components;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;

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
    
    // MUST always be in mm, if something sets it it should be converted first.
    private Location machineExtents = new Location(LengthUnit.Millimeters, 430, 410, 0, 0);
    
    // MUST always be in mm, if something sets it it should be converted first.
    private Location lookingAt = machineExtents.multiply(0.5, 0.5, 1, 1).derive(null, null, 1.0, null);
    
    /**
     * Contains the AffineTransform that was last used to render the component.
     * This is used elsewhere to convert component coordinates back to machine
     * coordinates.
     */
    private AffineTransform transform;
    
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
    
    private void updateTransform() {
        AffineTransform transform = new AffineTransform();
        
        int width = getWidth();
        int height = getHeight();
        
        // Center the drawing
        transform.translate(width / 2,  height / 2);
        
        // Determine the base scale. This is the scaling factor needed to fit
        // the entire machine in the window.
        double bedWidth = machineExtents.getX();
        double bedHeight = machineExtents.getY();
        double xScale = width / bedWidth;
        double yScale = height / bedHeight;
        double baseScale = Math.min(xScale, yScale);
        double scale = baseScale * lookingAt.getZ();
        // Scale the drawing by the baseScale times the zoomed scale
        transform.scale(scale, scale);
        // Move to the lookingAt position
        transform.translate(-lookingAt.getX(), lookingAt.getY());
        // Flip the drawing in Y so that our coordinate system matches that
        // of the machine.
        transform.scale(1,  -1);
        
        this.transform = transform;
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        // Create a new Graphics so we don't break the original.
        Graphics2D g2d = (Graphics2D) g.create();
        
        // Paint the background
        g2d.setColor(getBackground());
        g2d.fillRect(0, 0, getWidth(), getHeight());
        
        // All rendering is done in mm, where 1mm = 1px. Any Locations that
        // are used for rendering must first be converted to mm.
        
        updateTransform();
        g2d.transform(transform);
        
        // Draw the bed
        g2d.setColor(Color.lightGray);
        g2d.fillRect(0, 0, (int) machineExtents.getX(), (int) machineExtents.getY());
        
        Machine machine = Configuration.get().getMachine();
        JobProcessor jobProcessor = MainFrame.jobPanel.getJobProcessor();
        Job job = jobProcessor.getJob();
        if (job != null) {
            // Draw the boards
            for (BoardLocation boardLocation : job.getBoardLocations()) {
                Location location = boardLocation.getLocation();
                paintCrosshair(g2d, location, Color.green);
                // Draw the placements on the boards
                for (Placement placement : boardLocation.getBoard().getPlacements()) {
                    if (placement.getSide() != boardLocation.getSide()) {
                        continue;
                    }
                    Location placementLocation = Utils2D.calculateBoardPlacementLocation(boardLocation.getLocation(), boardLocation.getSide(), placement.getLocation());
                    paintCrosshair(g2d, placementLocation, Color.magenta);
                }
            }
        }
        
        // Draw the feeders
        for (Feeder feeder : machine.getFeeders()) {
            try {
                Location location = feeder.getPickLocation();
                paintCrosshair(g2d, location, Color.white);
            }
            catch (Exception e) {
                
            }
        }
        
        // Draw fixed cameras
        for (Camera camera : machine.getCameras()) {
            Location location = camera.getLocation();
            paintCrosshair(g2d, location, Color.cyan);
        }
         
        // Draw the head
        for (Head head : machine.getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                Location location = nozzle.getLocation();
                paintCrosshair(g2d, location, Color.red);
            }
            
            for (Camera camera : head.getCameras()) {
                Location location = camera.getLocation();
                paintCrosshair(g2d, location, Color.blue);
                // TODO: Draw camera image, properly scaled.
            }
            
            for (Actuator actuator : head.getActuators()) {
                Location location = actuator.getLocation();
                paintCrosshair(g2d, location, Color.yellow);
            }
        }
        
        // Dispose of the Graphics we created.
        g2d.dispose();
    }
    
    private void paintCrosshair(Graphics2D g2d, Location location, Color color) {
        location = location.convertToUnits(LengthUnit.Millimeters);
        g2d.setColor(color);
        int x = (int) location.getX();
        int y = (int) location.getY();
        g2d.drawLine(x - 3, y, x + 3, y);
        g2d.drawLine(x, y - 3, x, y + 3);
    }
    
    private Location getPixelLocation(double x, double y) {
        Point2D point = new Point2D.Double(x, y);
        try {
            transform.inverseTransform(point, point);
        }
        catch (Exception e) {
        }
        return lookingAt.derive(point.getX(), point.getY(), null, null);
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        double minimumScale = 0.1;
        double scaleIncrement = 0.01;
        
        double scale = lookingAt.getZ();
        scale += -e.getWheelRotation() * scaleIncrement;
        
        // limit the scale to 10% so that it doesn't just turn into a dot
        scale = Math.max(scale, minimumScale);
        
        // Get the offsets from lookingAt to where the mouse was when the
        // scroll event happened
        Location location1 = getPixelLocation(e.getX(), e.getY());
        
        // Update the scale
        lookingAt = lookingAt.derive(null, null, scale, null);
        
        // And the transform
        updateTransform();

        // Get the newly scaled location
        Location location2 = getPixelLocation(e.getX(), e.getY());

        // Get the delta between the two locations.
        Location delta = location2.subtract(location1);
        
        // Reset Z and C since we don't want to mess with them
        delta = delta.derive(null, null, 0.0, 0.0);
        
        // And offset lookingAt by the delta
        lookingAt = lookingAt.subtract(delta);
        
        // If the user hit the minimum scale, center the table.
        // This helps them find it if it gets lost.
        if (scale == minimumScale) {
            lookingAt = machineExtents
                    .multiply(0.5, 0.5, 1, 1)
                    .derive(null, null, minimumScale, null);
        }
        
        // Repaint will update the transform and we're ready to go.
        repaint();
    }
    
    @Override
    public void mouseClicked(MouseEvent e) {
        final Camera camera = Configuration.get().getMachine().getHeads().get(0).getCameras().get(0);
        Location clickLocation = getPixelLocation(e.getX(), e.getY())
                .convertToUnits(camera.getLocation().getUnits());
        final Location location = camera.getLocation().derive(
                clickLocation.getX(), 
                clickLocation.getY(), 
                null, 
                null);
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
    
    @Override
    public void jobLoaded(Job job) {
        repaint();
    }

    @Override
    public void jobStateChanged(JobState state) {
    }

    @Override
    public void jobEncounteredError(JobError error, String description) {
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
}
