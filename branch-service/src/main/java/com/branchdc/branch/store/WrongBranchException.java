package com.branchdc.branch.store;

public class WrongBranchException extends RuntimeException {

    public WrongBranchException(String thisBranch, String eventBranch) {
        super("This is branch " + thisBranch + "; refusing an event for branch " + eventBranch);
    }
}
