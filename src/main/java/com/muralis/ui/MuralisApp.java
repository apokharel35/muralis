package com.muralis.ui;

import com.muralis.engine.RenderConfig;
import com.muralis.engine.RenderSnapshot;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.util.concurrent.atomic.AtomicReference;

public class MuralisApp extends Application {

    public static AtomicReference<RenderSnapshot> snapshotRef;
    public static RenderConfig                    renderConfig;
    public static Runnable                        shutdownCallback;

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();

        Scene scene = new Scene(root, 1100, 800);
        scene.setFill(Color.web("#0d0d0f"));

        stage.setTitle("Muralis \u2014 BTCUSDT");
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        if (shutdownCallback != null) {
            shutdownCallback.run();
        }
    }
}
