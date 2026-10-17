package dev.learning.mall.catalog.api;

import java.math.BigDecimal;

public record ProductView(long id, String name, String description, String category,
                          String illustration, BigDecimal price, int stock) {}
