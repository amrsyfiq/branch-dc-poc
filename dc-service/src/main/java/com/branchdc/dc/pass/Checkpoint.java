package com.branchdc.dc.pass;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An entry checkpoint: a "branch" the DC distributes passes to. Rows come from the seed migration. */
@Entity
@Table(name = "checkpoint")
public class Checkpoint {

    public enum Kind { AIRPORT, LAND, SEA }

    @Id
    private String code;

    private String name;

    @Enumerated(EnumType.STRING)
    private Kind kind;

    protected Checkpoint() {
        // for JPA
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Kind getKind() {
        return kind;
    }
}
