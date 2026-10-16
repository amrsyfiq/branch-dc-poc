package com.branchdc.events;

import java.math.BigDecimal;

/** A product as one branch sells it: the DC's product data plus that branch's price. */
public record ProductPrice(String sku, String name, BigDecimal price, String currency) {
}
