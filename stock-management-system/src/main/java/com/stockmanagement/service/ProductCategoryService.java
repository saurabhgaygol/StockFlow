package com.stockmanagement.service;


import com.stockmanagement.entity.ProductCategory;
import com.stockmanagement.entity.UserTable;
import com.stockmanagement.repository.ProductCategoryRepository;

import org.springframework.stereotype.Service;

import java.util.List;
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

    /**
     * List categories for the screen.
     * - Super Admin -> all companies' categories.
     * - Everyone else -> only their own company's categories.
     */
    public List<ProductCategory> getCategoriesForUser(CustomUserDetails userDetails) {
        Long userId = userDetails.getUserId();
        UserTable user = userDetails.getUser();

        if (userPermissionService.hasPermission(userId, "SUPER_ADMIN")) {
            return categoryRepository.findAll();
        }
        return categoryRepository.findByCompanyName(user.getCompanyName());
    }

    /** Active categories for a company — used to populate the Stock Inward dropdown. */
    public List<ProductCategory> getActiveCategoriesForCompany(String companyName) {
        return categoryRepository.findByCompanyNameAndStatus(companyName, "ACTIVE");
    }

    /** Result of an add attempt, so the UI can show the right message. */
    public record AddResult(ProductCategory category, boolean wasNew) {}

    /** Add a category, preventing duplicates (case-insensitive) within the same company. */
    public AddResult addCategory(ProductCategory incoming, String companyName, String createdBy) {
        Optional<ProductCategory> existing = categoryRepository
                .findByCategoryNameIgnoreCaseAndCompanyName(incoming.getCategoryName().trim(), companyName);

        if (existing.isPresent()) {
            return new AddResult(existing.get(), false);
        }

        incoming.setCategoryName(incoming.getCategoryName().trim());
        incoming.setCompanyName(companyName);
        incoming.setCreatedBy(createdBy);
        ProductCategory saved = categoryRepository.save(incoming);
        return new AddResult(saved, true);
    }

    public ProductCategory getCategoryById(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Category not found: " + id));
    }

    public void updateCategory(Long id, ProductCategory incoming) {
        ProductCategory category = getCategoryById(id);
        category.setCategoryName(incoming.getCategoryName().trim());
        category.setDescription(incoming.getDescription());
        categoryRepository.save(category);
    }

    /** Hard-delete the category. */
    public void deleteCategory(Long id) {
        categoryRepository.deleteById(id);
    }
}