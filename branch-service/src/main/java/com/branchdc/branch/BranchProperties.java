package com.branchdc.branch;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Who this branch is. The only difference between the branch A and branch B deployments
 * (besides the database they point at).
 *
 * @param id the branch id, e.g. {@code A}; events for any other branch are rejected
 */
@Validated
@ConfigurationProperties("branch")
public record BranchProperties(@NotBlank String id) {
}
