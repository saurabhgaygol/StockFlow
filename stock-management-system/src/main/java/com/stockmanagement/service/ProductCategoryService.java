package com.stockmanagement.service;

import com.stockmanagement.entity.ProductCategory;
import com.stockmanagement.entity.UserTable;
import com.stockmanagement.repository.ProductCategoryRepository;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ProductCategoryService {

    private final ProductCategoryRepository categoryRepository;
    private final UserPermissionService userPermissionService;

    public ProductCategoryService(
            ProductCategoryRepository categoryRepository,
            UserPermissionService userPermissionService) {
        this.categoryRepository = categoryRepository;
        this.userPermissionService = userPermissionService;
    }

    /* ============================================================
       LIST — screen ke liye
       ============================================================ */
    public List<ProductCategory> getCategoriesForUser(CustomUserDetails userDetails) {
        Long userId = userDetails.getUserId();
        UserTable user = userDetails.getUser();

        if (userPermissionService.hasPermission(userId, "SUPER_ADMIN")) {
            return categoryRepository.findAll();
        }
        return categoryRepository.findByCompanyName(user.getCompanyName());
    }

    /* ============================================================
       DROPDOWN — Stock Inward ke liye
       ============================================================ */

    /**
     * Active categories for a company — Category dropdown ke liye.
     * Sirf unique category names.
     */
    public List<ProductCategory> getActiveCategoriesForCompany(String companyName) {
        List<ProductCategory> categories =
                categoryRepository.findByCompanyNameAndStatus(companyName, "ACTIVE");

        Map<String, ProductCategory> unique = new LinkedHashMap<>();
        for (ProductCategory c : categories) {
            if (c.getCategoryName() != null && !c.getCategoryName().trim().isEmpty()) {
                unique.putIfAbsent(c.getCategoryName().trim().toLowerCase(), c);
            }
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * Saare active products laao us company ke liye (unique).
     */
    public List<ProductCategory> getActiveProductForCompany(String companyName) {
        List<ProductCategory> products =
                categoryRepository.findByCompanyNameAndStatus(companyName, "ACTIVE");

        Map<String, ProductCategory> unique = new LinkedHashMap<>();
        for (ProductCategory p : products) {
            if (p.getProductName() != null && !p.getProductName().trim().isEmpty()) {
                unique.putIfAbsent(p.getProductName().trim().toLowerCase(), p);
            }
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * Ek specific category ke products laao (dependent dropdown ke liye).
     * ✅ FIXED VERSION — Debug logs ke saath
     */
    public List<ProductCategory> getProductsByCategory(Long categoryId, String companyName) {

        // Step 1: Category dhundo
        ProductCategory category = categoryRepository.findById(categoryId)
                .filter(c -> companyName != null && companyName.equals(c.getCompanyName()))
                .orElseThrow(() -> new IllegalArgumentException("Category not found."));


        // Step 2: Us category ke saare products laao
        List<ProductCategory> products = categoryRepository
                .findByCategoryNameAndCompanyNameAndStatus(
                        category.getCategoryName(), companyName, "ACTIVE");

        // Step 3: Duplicate product name hatao (case-insensitive)
        Map<String, ProductCategory> unique = new LinkedHashMap<>();
        for (ProductCategory p : products) {
            if (p.getProductName() != null && !p.getProductName().trim().isEmpty()) {
                unique.putIfAbsent(p.getProductName().trim().toLowerCase(), p);
            }
        }


        return new ArrayList<>(unique.values());
    }

    /* ============================================================
       CRUD — Add / Update / Delete
       ============================================================ */

    public record AddResult(ProductCategory category, boolean wasNew) {}

    public AddResult addCategory(ProductCategory incoming, String companyName, String createdBy) {
        Optional<ProductCategory> existing = categoryRepository
                .findByCategoryNameIgnoreCaseAndCompanyName(
                        incoming.getCategoryName().trim(), companyName);

        if (existing.isPresent()) {
            return new AddResult(existing.get(), false);
        }

        incoming.setCategoryName(incoming.getCategoryName().trim());
        incoming.setCompanyName(companyName);
        incoming.setCreatedBy(createdBy);
        ProductCategory saved = categoryRepository.save(incoming);
        return new AddResult(saved, true);
    }

    /** Category sirf apni company ki mil sakti hai (dusri company ka id = "not found"). */
    public ProductCategory getCategoryById(Long id, String companyName) {
        return categoryRepository.findById(id)
                .filter(c -> companyName != null && companyName.equals(c.getCompanyName()))
                .orElseThrow(() -> new IllegalArgumentException("Category not found."));
    }

    public void updateCategory(Long id, ProductCategory incoming, String companyName) {
        ProductCategory category = getCategoryById(id, companyName);
        category.setCategoryName(incoming.getCategoryName().trim());
        category.setDescription(incoming.getDescription());
        categoryRepository.save(category);
    }

    public void deleteCategory(Long id, String companyName) {
        ProductCategory category = getCategoryById(id, companyName);
        categoryRepository.delete(category);
    }
}