package org.openpnp.gui.components;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.EventHandler;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

import org.openpnp.CameraListener;
import org.openpnp.ConfigurationListener;
import org.openpnp.JobProcessorListener;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Head;
import org.openpnp.spi.JobProcessor;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;

@SuppressWarnings("serial")
public class FxNavigationView extends JFXPanel {
    Location machineExtentsBottomLeft = new Location(LengthUnit.Millimeters, 0, 0, 0, 0);
    Location machineExtentsTopRight = new Location(LengthUnit.Millimeters, 400, 400, 0, 0);

    Map<Camera, CameraImageView> cameraImageViews = new HashMap<>();
    
    Scene scene;
    Pane root;
    Group machine;
    Group bed;
    Group boards;
    
    Scale zoomTx = new Scale(1, 1, 0, 0);
    Translate viewTx = new Translate(100, 100);

    public FxNavigationView() {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                setScene(createScene());
                Configuration.get().addListener(configurationListener);
            }
        });
    }

    private Scene createScene() {
        root = new Pane();
        // Flip Y so the coordinate system is that of OpenPnP
        root.setScaleY(-1);
        scene = new Scene(root, Color.BLACK);
        
        machine = new Group();
        machine.getTransforms().add(zoomTx);
        machine.getTransforms().add(viewTx);
        root.getChildren().add(machine);
        
        bed = new Group();
        Rectangle bedRect = new Rectangle(
                machineExtentsBottomLeft.getX(),
                machineExtentsBottomLeft.getY(),
                machineExtentsTopRight.getX(),
                machineExtentsTopRight.getY());
        bedRect.setFill(Color.rgb(97, 98, 100));
        bed.getChildren().add(bedRect);
        machine.getChildren().add(bed);

        boards = new Group();
        bed.getChildren().add(boards);
        
        scene.setOnScroll(zoomHandler);
        return scene;
    }
    
    Point2D getPixelLocation(double x, double y) {
        return machine.sceneToLocal(x, y);
    }
    
    EventHandler<ScrollEvent> zoomHandler = new EventHandler<ScrollEvent>() {
        @Override
        public void handle(final ScrollEvent e) {
            e.consume();
            Point2D before = getPixelLocation(e.getX(), e.getY());
            double scale = zoomTx.getX();
            scale += (e.getDeltaY() * scale * 0.001);
            scale = Math.max(scale, 0.1);
            zoomTx.setX(scale);
            zoomTx.setY(scale);
            Point2D after = getPixelLocation(e.getX(), e.getY());
            Point2D delta = after.subtract(before);
            viewTx.setX(viewTx.getX() + delta.getX());
            viewTx.setY(viewTx.getY() + delta.getY());
        }
    };
    
    JobProcessorListener jobProcessorListener = new JobProcessorListener.Adapter() {
        @Override
        public void jobLoaded(final Job job) {
            Platform.runLater(new Runnable() {
                public void run() {
                    boards.getChildren().clear();
                    for (BoardLocation boardLocation : job.getBoardLocations()){
                        Location location = boardLocation.getLocation().convertToUnits(LengthUnit.Millimeters);
                        Group board = new Group();
                        board.getChildren().add(new Rectangle(80, 50, Color.GREEN));
                        board.setTranslateX(location.getX());
                        board.setTranslateY(location.getY());
                        boards.getChildren().add(board);
                    }
                }
            });
        }
    };
    
    ConfigurationListener configurationListener = new ConfigurationListener.Adapter() {
        @Override
        public void configurationComplete(Configuration configuration)
                throws Exception {
            final Machine machine = configuration.getMachine();
            machine.addListener(machineListener);
            // TODO: This doesn't really work in the new JobProcessor world
            // because the JobProcessor gets swapped out when changing tabs.
            // Need to figure out how to reference the current one and
            // maintain listeners across switches.
            for (JobProcessor jobProcessor : machine.getJobProcessors().values()) {
                jobProcessor.addListener(jobProcessorListener);
            }
            Platform.runLater(new Runnable() {
                public void run() {
                    for (Camera camera : machine.getCameras()) {
                        CameraImageView view = new CameraImageView(camera);
                        cameraImageViews.put(camera, view);
                        FxNavigationView
                            .this
                            .machine
                            .getChildren()
                            .add(view);
                    }
                    for (Head head : machine.getHeads()) {
                        for (Camera camera : head.getCameras()) {
                            CameraImageView view = new CameraImageView(camera);
                            cameraImageViews.put(camera, view);
                            FxNavigationView
                                .this
                                .machine
                                .getChildren()
                                .add(view);
                        }
                    }
                }
            });
        }
    };
    
    MachineListener machineListener = new MachineListener.Adapter() {
        @Override
        public void machineHeadActivity(Machine machine, Head head) {
            // Reposition anything that might have moved.
            for (Camera camera : head.getCameras()) {
                final Location location = camera.getLocation().convertToUnits(LengthUnit.Millimeters);
                final CameraImageView view = cameraImageViews.get(camera);
                Platform.runLater(new Runnable() {
                    public void run() {
                        view.setX(location.getX());
                        view.setY(location.getY());
                    }
                });
            }
        }
    };
    
    class CameraImageView extends ImageView implements CameraListener {
        final Camera camera;
        
        public CameraImageView(Camera camera) {
            this.camera = camera;
            Location unitsPerPixel = camera
                    .getUnitsPerPixel()
                    .convertToUnits(LengthUnit.Millimeters);
            setFitWidth(unitsPerPixel.getX() * camera.getWidth());
            setFitHeight(unitsPerPixel.getY() * camera.getHeight());
            // Images are flipped with respect to display coordinates, so
            // flip em back.
            setScaleY(-1);
            setOnMouseClicked(new EventHandler<MouseEvent>() {
                @Override
                public void handle(MouseEvent e) {
                    setOpacity(getOpacity() == 1 ? 0.1 : 1);
                }
            });
            camera.startContinuousCapture(this, 10);
        }
        
        public void frameReceived(BufferedImage img) {
            setImage(SwingFXUtils.toFXImage(img, null));
        }
    }
}
