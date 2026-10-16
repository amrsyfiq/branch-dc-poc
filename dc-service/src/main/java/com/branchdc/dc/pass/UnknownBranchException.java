package com.branchdc.dc.pass;

public class UnknownBranchException extends RuntimeException {

    public UnknownBranchException(String branchCode) {
        super("Unknown branch: " + branchCode);
    }
}
