package com.branchdc.dc.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A branch the DC distributes data to. Rows come from the seed migration. */
@Entity
@Table(name = "branch")
public class Branch {

    @Id
    private String code;

    private String name;

    protected Branch() {
        // for JPA
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
