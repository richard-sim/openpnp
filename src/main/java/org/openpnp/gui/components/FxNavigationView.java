package org.openpnp.gui.components;

import java.awt.image.BufferedImage;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.EventHandler;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

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

public class FxNavigationView extends JFXPanel {
    Location machineExtentsBottomLeft = new Location(LengthUnit.Millimeters, 0, 0, 0, 0);
    Location machineExtentsTopRight = new Location(LengthUnit.Millimeters, 400, 400, 0, 0);
    
    Scene scene;
    Group root;
    Group machine;
    Group bed;
    Group boards;

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
        root = new Group();
        // Flip Y so the coordinate system is that of OpenPnP
        root.setScaleY(-1);
        scene = new Scene(root, Color.BLACK);
        
        machine = new Group();
        root.getChildren().add(machine);
        
        bed = new Group();
        bed.getChildren().add(new Rectangle(400, 400, Color.GRAY));
        machine.getChildren().add(bed);

        boards = new Group();
        bed.getChildren().add(boards);
        
        scene.setOnScroll(new EventHandler<ScrollEvent>() {
            @Override
            public void handle(final ScrollEvent e) {
                double scale = machine.getScaleX();
                scale += (e.getDeltaY() * 0.001);
                scale = Math.max(scale, 0.1);
                machine.setScaleX(scale);
                machine.setScaleY(scale);
            }
        });
        return scene;
    }
    
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
//            for (Camera camera : machine.getCameras()) {
//                camera.startContinuousCapture(
//                        new NavCameraListener(camera), 
//                        24);
//            }
            Platform.runLater(new Runnable() {
                public void run() {
                    for (Head head : machine.getHeads()) {
                        for (Camera camera : head.getCameras()) {
                            final ImageView imageView = new ImageView();
                            Location unitsPerPixel = camera.getUnitsPerPixel().convertToUnits(LengthUnit.Millimeters);
                            imageView.setFitWidth(unitsPerPixel.getX() * camera.getWidth());
                            imageView.setFitHeight(unitsPerPixel.getY() * camera.getHeight());
                            FxNavigationView.this.machine.getChildren().add(imageView);
                            camera.startContinuousCapture(new CameraListener() {
                                @Override
                                public void frameReceived(BufferedImage img) {
                                    imageView.setImage(SwingFXUtils.toFXImage(img, null));
                                }
                            }, 10);
                        }
                    }
                }
            });
        }
    };
    
    MachineListener machineListener = new MachineListener.Adapter() {
        @Override
        public void machineHeadActivity(Machine machine, Head head) {
            // TODO: Move the head, refresh movable cameras, etc.
        }
    };
}
