package com.muralis.ui;

import com.muralis.engine.RenderConfig;
import com.muralis.engine.RenderSnapshot;
import com.muralis.model.ConnectionState;
import com.muralis.model.InstrumentSpec;
import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.concurrent.atomic.AtomicReference;

public class MuralisApp extends Application {

    // Injected before launch via static fields (JavaFX Application does not
    // support constructor injection — see SPEC-rendering.md Section 9.1)
    public static AtomicReference<RenderSnapshot> snapshotRef;
    public static RenderConfig                    renderConfig;
    public static InstrumentSpec                  instrumentSpec;
    public static Runnable                        shutdownCallback;
    public static Runnable                        deltaResetCallback;
    public static Runnable                        volumeResetCallback;

    @Override
    public void start(Stage stage) {
        HeatmapCanvas heatmapCanvas = new HeatmapCanvas(snapshotRef, renderConfig);
        heatmapCanvas.setPrefWidth(600);

        LadderCanvas ladderCanvas = new LadderCanvas(snapshotRef, renderConfig, heatmapCanvas);

        // ── Status bar (TOP, 28px) ─────────────────────────────────────────
        // Section 7: 8px Circle dot (radius = 4), symbol label, theme toggle
        Circle statusDot = new Circle(4.0);
        statusDot.setFill(ColorScheme.DARK.statusConnecting);

        Label symbolLabel = new Label("BTCUSDT");
        symbolLabel.setTextFill(ColorScheme.DARK.priceText);

        Button themeButton = new Button("\u2299 Dark");

        // Spacer pushes theme button to the right
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox statusBar = new HBox(6, statusDot, symbolLabel, spacer, themeButton);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPrefHeight(28.0);
        statusBar.setPadding(new Insets(0, 8, 0, 8));
        statusBar.setStyle(
            "-fx-background-color: #0d0d0f;" +
            "-fx-border-color: #1e1e24;" +
            "-fx-border-width: 0 0 1 0;"
        );

        // ── Pulse Timeline (amber for CONNECTING / RECONNECTING) ───────────
        // Section 7.2: 1Hz opacity oscillation 0.4 ↔ 1.0
        Timeline pulse = new Timeline(
            new KeyFrame(Duration.ZERO,          new KeyValue(statusDot.opacityProperty(), 1.0)),
            new KeyFrame(Duration.seconds(0.5),  new KeyValue(statusDot.opacityProperty(), 0.4)),
            new KeyFrame(Duration.seconds(1.0),  new KeyValue(statusDot.opacityProperty(), 1.0))
        );
        pulse.setCycleCount(Animation.INDEFINITE);

        // Track previous state so AnimationTimer doesn't reset pulse every frame
        final ConnectionState[] prevState = { null };

        // Theme toggle — switches LadderCanvas (which owns BubblePainter) and
        // resets prevState so next frame updates the dot to the new scheme's color
        themeButton.setOnAction(e -> {
            ColorScheme current = ladderCanvas.colorScheme();
            ColorScheme next    = (current == ColorScheme.DARK) ? ColorScheme.LIGHT : ColorScheme.DARK;
            ladderCanvas.setColorScheme(next);
            themeButton.setText(next == ColorScheme.DARK ? "\u2299 Dark" : "\u2299 Light");
            prevState[0] = null;   // force dot color refresh on next AnimationTimer tick
        });

        // ── Control bar (BOTTOM, 36px) ─────────────────────────────────────
        // Section 8

        // ── Delta tint controls ────────────────────────────────────────
        Label deltaTintLabel = new Label("50%");
        deltaTintLabel.setTextFill(ColorScheme.DARK.priceText);

        Slider deltaTintSlider = new Slider(0.1, 1.0, 0.5);
        deltaTintSlider.setMajorTickUnit(0.2);
        deltaTintSlider.setSnapToTicks(false);
        deltaTintSlider.setPrefWidth(120.0);
        deltaTintSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            renderConfig.setDeltaTintIntensity(newVal.doubleValue());
            deltaTintLabel.setText((int)(newVal.doubleValue() * 100) + "%");
        });

        CheckBox deltaTintToggle = new CheckBox("Delta");
        deltaTintToggle.setSelected(true);
        deltaTintToggle.setTextFill(ColorScheme.DARK.priceText);
        deltaTintToggle.selectedProperty().addListener((obs, oldVal, newVal) -> {
            renderConfig.setDeltaTintEnabled(newVal);
            deltaTintSlider.setDisable(!newVal);
        });

        Button resetDelta = new Button("Reset \u0394");
        resetDelta.setOnAction(e -> {
            if (deltaResetCallback != null) deltaResetCallback.run();
        });

        // ── Volume profile controls ────────────────────────────────────
        CheckBox volumeToggle = new CheckBox("Vol");
        volumeToggle.setSelected(true);
        volumeToggle.setTextFill(ColorScheme.DARK.priceText);
        volumeToggle.selectedProperty().addListener((obs, oldVal, newVal) ->
            renderConfig.setVolumeProfileEnabled(newVal)
        );

        Button resetVolume = new Button("Reset Vol");
        resetVolume.setOnAction(e -> {
            if (volumeResetCallback != null) volumeResetCallback.run();
        });

        Button zoomOut = new Button("\u2212");   // Unicode minus sign
        Button zoomIn  = new Button("+");
        zoomOut.setOnAction(e -> ladderCanvas.adjustZoom(-2.0));
        zoomIn.setOnAction(e  -> ladderCanvas.adjustZoom(+2.0));

        Button centreButton = new Button("Centre");
        centreButton.setOnAction(e -> ladderCanvas.resetScroll());

        HBox controlBar = new HBox(8,
                deltaTintToggle, deltaTintSlider, deltaTintLabel, resetDelta,
                volumeToggle, resetVolume,
                zoomOut, zoomIn, centreButton);
        controlBar.setAlignment(Pos.CENTER_LEFT);
        controlBar.setPrefHeight(36.0);
        controlBar.setPadding(new Insets(4, 6, 4, 6));
        controlBar.setStyle("-fx-background-color: #0d0d0f;");

        // ── Root BorderPane ────────────────────────────────────────────────
        BorderPane root = new BorderPane();
        root.setTop(statusBar);
        root.setLeft(heatmapCanvas);
        root.setCenter(ladderCanvas);
        root.setBottom(controlBar);

        // ── Status AnimationTimer ──────────────────────────────────────────
        // Reads snap on every frame; updates dot only when connectionState changes
        new AnimationTimer() {
            @Override
            public void handle(long nowNanos) {
                RenderSnapshot snap = snapshotRef.get();
                if (snap == null) return;

                ConnectionState state = snap.connectionState();
                if (state == prevState[0]) return;   // no change — skip update
                prevState[0] = state;

                ColorScheme scheme = ladderCanvas.colorScheme();
                switch (state) {
                    case CONNECTING -> {
                        statusDot.setFill(scheme.statusConnecting);
                        if (pulse.getStatus() != Animation.Status.RUNNING) pulse.play();
                    }
                    case RECONNECTING -> {
                        statusDot.setFill(scheme.statusReconnecting);
                        if (pulse.getStatus() != Animation.Status.RUNNING) pulse.play();
                    }
                    case CONNECTED -> {
                        statusDot.setFill(scheme.statusConnected);
                        pulse.stop();
                        statusDot.setOpacity(1.0);
                    }
                    case DISCONNECTED -> {
                        statusDot.setFill(scheme.statusDisconnected);
                        pulse.stop();
                        statusDot.setOpacity(1.0);
                    }
                }
            }
        }.start();

        // ── Scene and stage ────────────────────────────────────────────────
        Scene scene = new Scene(root, 1580, 800);
        scene.setFill(Color.web("#0d0d0f"));

        stage.setTitle("Muralis \u2014 BTCUSDT");
        stage.setMinWidth(1500);
        stage.setMinHeight(600);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            if (shutdownCallback != null) shutdownCallback.run();
            Platform.exit();
        });
        stage.show();
    }

    @Override
    public void stop() {
        if (shutdownCallback != null) {
            shutdownCallback.run();
        }
    }
}
