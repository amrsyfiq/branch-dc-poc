package com.branchdc.dc.catalog;

import com.branchdc.events.ProductPrice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Reads the DC's data. Entities stay inside this class; callers get DTOs. */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final BranchRepository branches;
    private final BranchPriceRepository prices;

    public CatalogService(BranchRepository branches, BranchPriceRepository prices) {
        this.branches = branches;
        this.prices = prices;
    }

    /** Every branch with its products: what {@code GET /data} returns at the DC. */
    public List<BranchData> allData() {
        Map<String, List<ProductPrice>> byBranch = prices.findAllWithProduct().stream()
                .collect(Collectors.groupingBy(BranchPrice::getBranchCode,
                        Collectors.mapping(CatalogService::toDto, Collectors.toList())));
        return branches.findAllByOrderByCodeAsc().stream()
                .map(b -> new BranchData(b.getCode(), b.getName(), byBranch.getOrDefault(b.getCode(), List.of())))
                .toList();
    }

    /** One branch's products and prices: exactly what is sent to that branch. */
    public List<ProductPrice> sliceOf(String branchCode) {
        return prices.findSlice(branchCode).stream().map(CatalogService::toDto).toList();
    }

    /** @throws UnknownBranchException if the DC has no such branch */
    public Branch requireBranch(String branchCode) {
        return branches.findById(branchCode).orElseThrow(() -> new UnknownBranchException(branchCode));
    }

    private static ProductPrice toDto(BranchPrice bp) {
        return new ProductPrice(bp.getProduct().getSku(), bp.getProduct().getName(), bp.getPrice(), bp.getCurrency());
    }

    public record BranchData(String code, String name, List<ProductPrice> products) {
    }
}
