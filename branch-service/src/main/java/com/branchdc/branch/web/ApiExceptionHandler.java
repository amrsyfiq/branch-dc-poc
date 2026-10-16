package com.branchdc.branch.web;

import com.branchdc.branch.store.WrongBranchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errors as RFC 9457 problem details. The status tells the listener what to do: a 4xx means
 * "this event will never be accepted" (dead-letter it), a 5xx means "try again".
 * (Unreadable or incomplete JSON is already a 400 from Spring.)
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(WrongBranchException.class)
    ProblemDetail wrongBranch(WrongBranchException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }
}
