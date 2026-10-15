package com.branchdc.dc.catalog;

public class UnknownBranchException extends RuntimeException {

    public UnknownBranchException(String branchCode) {
        super("Unknown branch: " + branchCode);
    }
}
