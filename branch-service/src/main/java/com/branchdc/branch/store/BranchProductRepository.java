package com.branchdc.branch.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface BranchProductRepository extends JpaRepository<BranchProduct, String> {

    List<BranchProduct> findAllByOrderBySkuAsc();
}
