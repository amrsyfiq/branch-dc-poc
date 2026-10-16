package com.branchdc.dc.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface BranchPriceRepository extends JpaRepository<BranchPrice, Long> {

    /** One branch's slice, with the product loaded in the same query (no N+1 selects). */
    @Query("select bp from BranchPrice bp join fetch bp.product p where bp.branchCode = :branchCode order by p.sku")
    List<BranchPrice> findSlice(String branchCode);

    @Query("select bp from BranchPrice bp join fetch bp.product p order by bp.branchCode, p.sku")
    List<BranchPrice> findAllWithProduct();
}
