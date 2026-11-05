package com.stockmanagement.service;

import com.stockmanagement.entity.CompanyFeature;
import com.stockmanagement.repository.CompanyFeatureRepository;
import com.stockmanagement.repository.CompanyRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which optional features are ON for which company. No row = OFF, so existing companies see no change.
 * Only Super Admin switches features (ShopFeaturesController).
 */
@Service
public class CompanyFeatureService {

    public static final String RETAIL_VARIANT_STOCK = "RETAIL_VARIANT_STOCK";
    public static final String RETAIL_OFFERS = "RETAIL_OFFERS";

    /** code -> display info. available=false are shown as "Coming soon" and cannot be switched yet. */
    public record FeatureInfo(String code, String name, String description, boolean available) {}

    private static final Map<String, FeatureInfo> CATALOG = new LinkedHashMap<>();
    static {
        add(new FeatureInfo(RETAIL_VARIANT_STOCK, "Products & Variants",
                "Barcode + quantity stock with size / colour variants (clothing, grocery, kirana, bar).", true));
        add(new FeatureInfo("RETAIL_BILLING", "Billing / POS",
                "Counter billing with barcode scan.", false));
        add(new FeatureInfo(RETAIL_OFFERS, "Offers & Discounts",
                "Festival and item-wise offers.", true));
        add(new FeatureInfo("RETAIL_RETURNS", "Sales Returns",
                "Customer returns and exchanges.", false));
        add(new FeatureInfo("RETAIL_REPORTS", "Retail Reports",
                "Sales, stock and profit reports.", false));
    }

    private static void add(FeatureInfo f) { CATALOG.put(f.code(), f); }

    private final CompanyFeatureRepository repo;
    private final CompanyRepository companyRepo;

    public CompanyFeatureService(CompanyFeatureRepository repo, CompanyRepository companyRepo) {
        this.repo = repo;
        this.companyRepo = companyRepo;
    }

    public List<FeatureInfo> catalog() {
        return List.copyOf(CATALOG.values());
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(String companyName, String featureCode) {
        if (companyName == null || companyName.isBlank()) return false;
        return repo.findByCompanyNameAndFeatureCode(companyName.trim(), featureCode)
                .map(f -> Boolean.TRUE.equals(f.getEnabled()))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public Set<String> enabledCodes(String companyName) {
        Set<String> out = new HashSet<>();
        if (companyName == null || companyName.isBlank()) return out;
        for (CompanyFeature f : repo.findByCompanyName(companyName.trim())) {
            if (Boolean.TRUE.equals(f.getEnabled())) out.add(f.getFeatureCode());
        }
        return out;
    }

    /** Switch one feature for one company. Throws IllegalArgumentException with a user-friendly message. */
    @Transactional
    public void setFeature(String companyName, String featureCode, boolean enabled, Long userId, String userName) {
        FeatureInfo info = CATALOG.get(featureCode);
        if (info == null || !info.available()) {
            throw new IllegalArgumentException("This feature cannot be switched yet.");
        }
        String canonical = companyRepo.findFirstByCompanyNameIgnoreCase(companyName == null ? "" : companyName.trim())
                .map(c -> c.getCompanyName())
                .orElseThrow(() -> new IllegalArgumentException("Company not found."));

        CompanyFeature row = repo.findByCompanyNameAndFeatureCode(canonical, featureCode).orElseGet(() -> {
            CompanyFeature n = new CompanyFeature();
            n.setCompanyName(canonical);
            n.setFeatureCode(featureCode);
            return n;
        });
        row.setEnabled(enabled);
        row.setUpdatedByUserId(userId);
        row.setUpdatedByName(userName);
        repo.save(row);
    }
}