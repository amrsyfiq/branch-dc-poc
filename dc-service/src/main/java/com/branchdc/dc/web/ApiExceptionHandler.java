package com.branchdc.dc.web;

import com.branchdc.dc.catalog.UnknownBranchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Errors as RFC 9457 problem details ({"status":404,"detail":"..."}). */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(UnknownBranchException.class)
    ProblemDetail unknownBranch(UnknownBranchException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
