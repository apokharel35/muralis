package com.muralis.ui;

import javafx.scene.paint.Color;

public enum ColorScheme {

    DARK(
        Color.web("#0d0d0f"),   // background
        Color.web("#111114"),   // panelBackground
        Color.web("#131316"),   // rowAlternate
        Color.web("#1a1a2e"),   // spreadFill
        Color.web("#0d2b1a"),   // bestBidHighlight
        Color.web("#2b0d0d"),   // bestAskHighlight
        Color.web("#1a6b3a"),   // bidBar
        Color.web("#6b1a1a"),   // askBar
        Color.web("#c8c8d0"),   // priceText
        Color.web("#4dff91"),   // bestBidText
        Color.web("#ff4d4d"),   // bestAskText
        Color.web("#8888aa"),   // spreadPriceText
        Color.web("#909098"),   // qtyText
        Color.web("#1db954"),   // buyBubbleFill
        Color.web("#4dff91"),   // buyBubbleStroke
        Color.web("#e63946"),   // sellBubbleFill
        Color.web("#ff6b6b"),   // sellBubbleStroke
        Color.web("#ffffff"),   // bubbleQtyText
        Color.web("#1db954"),   // statusConnected
        Color.web("#f4a261"),   // statusConnecting
        Color.web("#f4a261"),   // statusReconnecting
        Color.web("#e63946"),   // statusDisconnected
        Color.web("#1e1e24"),   // gridLine
        Color.web("#2a2a35"),   // panelDivider
        Color.web("#1db954"),   // deltaBuyTint
        Color.web("#e63946"),   // deltaSellTint
        Color.web("#2a9d8f"),   // volumeBar
        Color.web("#8ecfc5"),   // volumeBarText
        Color.web("#080810"),   // heatmapBackground
        Color.web("#0a1628"),   // heatmapThin
        Color.web("#1a3a6b"),   // heatmapMid
        Color.web("#f4a261"),   // heatmapThick
        Color.web("#e63946"),   // heatmapMax
        Color.web("#e63946"),   // bboAsk
        Color.web("#2a9d8f")    // bboBid
    ),

    LIGHT(
        Color.web("#f5f5f7"),   // background
        Color.web("#ebebee"),   // panelBackground
        Color.web("#efeff2"),   // rowAlternate
        Color.web("#e8e8f5"),   // spreadFill
        Color.web("#d4f0e0"),   // bestBidHighlight
        Color.web("#f0d4d4"),   // bestAskHighlight
        Color.web("#2d9e5f"),   // bidBar
        Color.web("#9e2d2d"),   // askBar
        Color.web("#1a1a2e"),   // priceText
        Color.web("#0a7a3a"),   // bestBidText
        Color.web("#9e1a1a"),   // bestAskText
        Color.web("#6666aa"),   // spreadPriceText
        Color.web("#555566"),   // qtyText
        Color.web("#1db954"),   // buyBubbleFill
        Color.web("#0a7a3a"),   // buyBubbleStroke
        Color.web("#e63946"),   // sellBubbleFill
        Color.web("#9e1a1a"),   // sellBubbleStroke
        Color.web("#ffffff"),   // bubbleQtyText
        Color.web("#1db954"),   // statusConnected
        Color.web("#e07820"),   // statusConnecting
        Color.web("#e07820"),   // statusReconnecting
        Color.web("#e63946"),   // statusDisconnected
        Color.web("#dcdce0"),   // gridLine
        Color.web("#c0c0cc"),   // panelDivider
        Color.web("#1db954"),   // deltaBuyTint
        Color.web("#e63946"),   // deltaSellTint
        Color.web("#2a9d8f"),   // volumeBar
        Color.web("#1a6e64"),   // volumeBarText
        Color.web("#f0f0f5"),   // heatmapBackground
        Color.web("#dde8f5"),   // heatmapThin
        Color.web("#7bafd4"),   // heatmapMid
        Color.web("#e07820"),   // heatmapThick
        Color.web("#c0392b"),   // heatmapMax
        Color.web("#c0392b"),   // bboAsk
        Color.web("#1a6e64")    // bboBid
    );

    public final Color background;
    public final Color panelBackground;
    public final Color rowAlternate;
    public final Color spreadFill;
    public final Color bestBidHighlight;
    public final Color bestAskHighlight;
    public final Color bidBar;
    public final Color askBar;
    public final Color priceText;
    public final Color bestBidText;
    public final Color bestAskText;
    public final Color spreadPriceText;
    public final Color qtyText;
    public final Color buyBubbleFill;
    public final Color buyBubbleStroke;
    public final Color sellBubbleFill;
    public final Color sellBubbleStroke;
    public final Color bubbleQtyText;
    public final Color statusConnected;
    public final Color statusConnecting;
    public final Color statusReconnecting;
    public final Color statusDisconnected;
    public final Color gridLine;
    public final Color panelDivider;
    public final Color deltaBuyTint;
    public final Color deltaSellTint;
    public final Color volumeBar;
    public final Color volumeBarText;
    public final Color heatmapBackground;
    public final Color heatmapThin;
    public final Color heatmapMid;
    public final Color heatmapThick;
    public final Color heatmapMax;
    public final Color bboAsk;
    public final Color bboBid;

    ColorScheme(
        Color background,
        Color panelBackground,
        Color rowAlternate,
        Color spreadFill,
        Color bestBidHighlight,
        Color bestAskHighlight,
        Color bidBar,
        Color askBar,
        Color priceText,
        Color bestBidText,
        Color bestAskText,
        Color spreadPriceText,
        Color qtyText,
        Color buyBubbleFill,
        Color buyBubbleStroke,
        Color sellBubbleFill,
        Color sellBubbleStroke,
        Color bubbleQtyText,
        Color statusConnected,
        Color statusConnecting,
        Color statusReconnecting,
        Color statusDisconnected,
        Color gridLine,
        Color panelDivider,
        Color deltaBuyTint,
        Color deltaSellTint,
        Color volumeBar,
        Color volumeBarText,
        Color heatmapBackground,
        Color heatmapThin,
        Color heatmapMid,
        Color heatmapThick,
        Color heatmapMax,
        Color bboAsk,
        Color bboBid
    ) {
        this.background = background;
        this.panelBackground = panelBackground;
        this.rowAlternate = rowAlternate;
        this.spreadFill = spreadFill;
        this.bestBidHighlight = bestBidHighlight;
        this.bestAskHighlight = bestAskHighlight;
        this.bidBar = bidBar;
        this.askBar = askBar;
        this.priceText = priceText;
        this.bestBidText = bestBidText;
        this.bestAskText = bestAskText;
        this.spreadPriceText = spreadPriceText;
        this.qtyText = qtyText;
        this.buyBubbleFill = buyBubbleFill;
        this.buyBubbleStroke = buyBubbleStroke;
        this.sellBubbleFill = sellBubbleFill;
        this.sellBubbleStroke = sellBubbleStroke;
        this.bubbleQtyText = bubbleQtyText;
        this.statusConnected = statusConnected;
        this.statusConnecting = statusConnecting;
        this.statusReconnecting = statusReconnecting;
        this.statusDisconnected = statusDisconnected;
        this.gridLine = gridLine;
        this.panelDivider = panelDivider;
        this.deltaBuyTint = deltaBuyTint;
        this.deltaSellTint = deltaSellTint;
        this.volumeBar = volumeBar;
        this.volumeBarText = volumeBarText;
        this.heatmapBackground = heatmapBackground;
        this.heatmapThin = heatmapThin;
        this.heatmapMid = heatmapMid;
        this.heatmapThick = heatmapThick;
        this.heatmapMax = heatmapMax;
        this.bboAsk = bboAsk;
        this.bboBid = bboBid;
    }
}
