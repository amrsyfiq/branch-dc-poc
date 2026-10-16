package com.branchdc.branch.store;

import com.branchdc.events.ProductPrice;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;

/** One product of this branch's copy. */
@Entity
@Table(name = "branch_product")
public class BranchProduct implements Persistable<String> {

    @Id
    private String sku;

    private String name;

    private BigDecimal price;

    private String currency;

    @Column(name = "synced_at")
    private Instant syncedAt;

    /** Rows are only ever inserted after a delete, so save() can skip the "does it exist?" select. */
    @Transient
    private boolean isNew = true;

    protected BranchProduct() {
        // for JPA
    }

    static BranchProduct from(ProductPrice p, Instant syncedAt) {
        BranchProduct bp = new BranchProduct();
        bp.sku = p.sku();
        bp.name = p.name();
        bp.price = p.price();
        bp.currency = p.currency();
        bp.syncedAt = syncedAt;
        return bp;
    }

    ProductPrice toDto() {
        return new ProductPrice(sku, name, price, currency);
    }

    @Override
    public String getId() {
        return sku;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public Instant getSyncedAt() {
        return syncedAt;
    }
}
