package com.muralis.model;

public enum AggressorSide {
    BUY,    // Buyer was the aggressor — lifted the offer — isBuyerMaker = false
    SELL    // Seller was the aggressor — hit the bid  — isBuyerMaker = true
}
