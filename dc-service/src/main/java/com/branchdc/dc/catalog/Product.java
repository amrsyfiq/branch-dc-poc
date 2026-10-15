package com.branchdc.dc.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "product")
public class Product {

    @Id
    private String sku;

    private String name;

    protected Product() {
        // for JPA
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }
}
