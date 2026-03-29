package com.muralis.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

public class MuralisApp extends Application {

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();

        Scene scene = new Scene(root, 1100, 800);
        scene.setFill(Color.web("#0d0d0f"));

        stage.setTitle("Muralis \u2014 BTCUSDT");
        stage.setScene(scene);
        stage.show();
    }
}
