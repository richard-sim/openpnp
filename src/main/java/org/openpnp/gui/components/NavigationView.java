package org.openpnp.gui.components;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;

import javax.swing.JComponent;

import org.openpnp.CameraListener;
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
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.Utils2D;

public class NavigationView 
    extends JComponent 
    implements JobProcessorListener, MachineListener, MouseWheelListener, 
        MouseListener, KeyListener {
    // we need min and max so that we have limits, think of camera trying to go
    // to 0 on this machine. crashes.
    
    // MUST always be in mm, if something sets it it should be converted first.
    private Location machineExtents = new Location(LengthUnit.Millimeters, 430, 410, 0, 0);
    
    // MUST always be in mm, if something sets it it should be converted first.
    private Location lookingAt = machineExtents.multiply(0.5, 0.5, 1, 1).derive(null, null, 1.0, null);
    
    // Determine the base scale. This is the scaling factor needed to fit
    // the entire machine in the window.
    // TODO: It would simplify things if we just calculate this once and
    // set it as Z on lookingAt during startup. There's no reason to
    // recalculate it every time since we only actually set it once.
//    double bedWidth = machineExtents.getX();
//    double bedHeight = machineExtents.getY();
//    double xScale = width / bedWidth;
//    double yScale = height / bedHeight;
//    double baseScale = Math.min(xScale, yScale);
//    double scale = baseScale * lookingAt.getZ();
    
    private boolean dimCameras = false;
    
    
    /**
     * Contains the AffineTransform that was last used to render the component.
     * This is used elsewhere to convert component coordinates back to machine
     * coordinates.
     */
    private AffineTransform transform;

    private HashMap<Camera, BufferedImage> cameraImages = new HashMap<>();
    
    public NavigationView() {
        setBackground(Color.black);
        addMouseWheelListener(this);
        addMouseListener(this);
        addKeyListener(this);
        Configuration.get().addListener(new ConfigurationListener() {
            @Override
            public void configurationLoaded(Configuration configuration)
                    throws Exception {
            }
            
            @Override
            public void configurationComplete(Configuration configuration)
                    throws Exception {
                Machine machine = configuration.getMachine();
                machine.addListener(NavigationView.this);
                // TODO: This doesn't really work in the new JobProcessor world
                // because the JobProcessor gets swapped out when changing tabs.
                // Need to figure out how to reference the current one and
                // maintain listeners across switches.
                for (JobProcessor jobProcessor : machine.getJobProcessors().values()) {
                    jobProcessor.addListener(NavigationView.this);
                }
                for (Camera camera : machine.getCameras()) {
                    camera.startContinuousCapture(
                            new NavCameraListener(camera), 
                            24);
                }
                for (Head head : machine.getHeads()) {
                    for (Camera camera : head.getCameras()) {
                        camera.startContinuousCapture(
                                new NavCameraListener(camera), 
                                24);
                    }
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

        // Scale the drawing to the zoom level
        transform.scale(lookingAt.getZ(), lookingAt.getZ());
        
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
        g2d.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING, 
                RenderingHints.VALUE_ANTIALIAS_ON);
        
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
//                paintCrosshair(g2d, location, Color.red);
            }
            
            for (Camera camera : head.getCameras()) {
                paintCamera(g2d, camera);
            }
            
            for (Actuator actuator : head.getActuators()) {
                Location location = actuator.getLocation();
//                paintCrosshair(g2d, location, Color.yellow);
            }
        }
        
        // Dispose of the Graphics we created.
        g2d.dispose();
    }
    
    private void paintCamera(Graphics2D g2d, Camera camera) {
        Location location = camera.getLocation();
        location = location.convertToUnits(LengthUnit.Millimeters);
//        paintCrosshair(g2d, location, Color.blue);
        BufferedImage img = cameraImages.get(camera);
        if (img == null) {
            return;
        }
        
        // we need to scale the image so that 1 pixel = 1mm
        // and it needs to be centered on the location
        double width = camera.getWidth();
        double height = camera.getHeight();
        Location upp = camera.getUnitsPerPixel().convertToUnits(LengthUnit.Millimeters);
        double scaledWidth = width * upp.getX();
        double scaledHeight = height * upp.getY();

        int dx1 = (int) (location.getX() - (scaledWidth / 2));
        int dy1 = (int) (location.getY() + (scaledHeight / 2));
        int dx2 = (int) (location.getX() + (scaledWidth / 2));
        int dy2 = (int) (location.getY() - (scaledHeight / 2));
        
        int sx1 = 0;
        int sy1 = 0;
        int sx2 = (int) width;
        int sy2 = (int) height;
        
        if (dimCameras) {
            if (img.getType() != BufferedImage.TYPE_INT_ARGB) {
                img = OpenCvUtils.convertBufferedImage(
                        img, 
                        BufferedImage.TYPE_INT_ARGB);
            }
            Composite oldComp = g2d.getComposite();
            AlphaComposite comp = AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, 
                    0.1f);
            g2d.setComposite(comp);
            g2d.drawImage(
                    img,
                    dx1,
                    dy1,
                    dx2,
                    dy2,
                    sx1,
                    sy1,
                    sx2,
                    sy2,
                    null);
            g2d.setComposite(oldComp);
        }
        else {
            g2d.drawImage(
                    img,
                    dx1,
                    dy1,
                    dx2,
                    dy2,
                    sx1,
                    sy1,
                    sx2,
                    sy2,
                    null);
        }
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
        scale += -e.getWheelRotation() * scale * scaleIncrement;
        
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
        if (e.isControlDown()) {
            // jog
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
        else {
            // toggle camera dim
            dimCameras = !dimCameras;
            repaint();
        }
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
    public void keyTyped(KeyEvent e) {
    }

    @Override
    public void keyPressed(KeyEvent e) {
    }

    @Override
    public void keyReleased(KeyEvent e) {
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
    
    class NavCameraListener implements CameraListener {
        private final Camera camera;
        
        public NavCameraListener(Camera camera) {
            this.camera = camera;
        }

        @Override
        public void frameReceived(BufferedImage img) {
            cameraImages.put(camera, img);
            repaint();
        }
    }
}
