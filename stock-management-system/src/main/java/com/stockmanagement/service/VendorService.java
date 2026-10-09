package com.stockmanagement.service;

import com.stockmanagement.entity.CompanyVendor;
import com.stockmanagement.entity.UserTable;
import com.stockmanagement.entity.Vendor;
import com.stockmanagement.repository.CompanyVendorRepository;
import com.stockmanagement.repository.VendorRepository;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class VendorService {

    private final VendorRepository vendorRepository;
    private final CompanyVendorRepository companyVendorRepository;
    private final UserPermissionService userPermissionService;

    public VendorService(
            VendorRepository vendorRepository,
            CompanyVendorRepository companyVendorRepository,
            UserPermissionService userPermissionService) {
        this.vendorRepository = vendorRepository;
        this.companyVendorRepository = companyVendorRepository;
        this.userPermissionService = userPermissionService;
    }

    public List<Vendor> getVendorsForUser(CustomUserDetails userDetails) {
        Long userId = userDetails.getUserId();
        UserTable user = userDetails.getUser();

        if (userPermissionService.hasPermission(userId, "SUPER_ADMIN")) {
            return vendorRepository.findAll();
        }

        return getVendorsForCompany(user.getCompanyName());
    }

    public record AddVendorResult(Vendor vendor, boolean vendorWasNew, boolean alreadyInYourList) {}

    public AddVendorResult addVendorForCompany(Vendor incoming, String companyName, String createdBy) {

        Optional<Vendor> existing = vendorRepository
                .findByVendorNameIgnoreCase(incoming.getVendorName().trim());

        boolean vendorWasNew = existing.isEmpty();

        Vendor vendor = existing.orElseGet(() -> {
            incoming.setVendorName(incoming.getVendorName().trim());
            incoming.setAddedByCompany(companyName);
            incoming.setCreatedBy(createdBy);
            return vendorRepository.save(incoming);
        });

        boolean alreadyLinked = companyVendorRepository
                .existsByCompanyNameAndVendorId(companyName, vendor.getId());

        if (!alreadyLinked) {
            CompanyVendor link = new CompanyVendor();
            link.setCompanyName(companyName);
            link.setVendorId(vendor.getId());
            link.setCreatedBy(createdBy);
            companyVendorRepository.save(link);
        }

        return new AddVendorResult(vendor, vendorWasNew, alreadyLinked);
    }

    public List<Vendor> getVendorsForCompany(String companyName) {
        List<Long> vendorIds = companyVendorRepository
                .findByCompanyNameAndStatus(companyName, "ACTIVE")
                .stream()
                .map(CompanyVendor::getVendorId)
                .collect(Collectors.toList());

        return vendorRepository.findAllById(vendorIds);
    }

    public List<Vendor> getVendorsAddedByCompany(String addedByCompany) {
        return vendorRepository.findByAddedByCompany(addedByCompany);
    }

    public Vendor getVendorById(Long id) {
        return vendorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Vendor not found: " + id));
    }

    /**
     * Vendor edit: sirf wahi company edit kar sakti hai jisne vendor add kiya hai
     * (aur jiski list me wo linked hai). SUPER_ADMIN sab edit kar sakta hai.
     */
    public void updateVendor(Long id, Vendor incoming, String companyName, boolean superAdmin) {
        Vendor vendor = getVendorById(id);

        if (!superAdmin) {
            boolean linked = companyVendorRepository
                    .existsByCompanyNameAndVendorId(companyName, id);
            boolean owner = companyName != null
                    && companyName.equals(vendor.getAddedByCompany());

            if (!linked || !owner) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "You can edit only vendors added by your company.");
            }
        }

        vendor.setVendorName(incoming.getVendorName().trim());
        vendor.setContactPerson(incoming.getContactPerson());
        vendor.setPhone(incoming.getPhone());
        vendor.setEmail(incoming.getEmail());
        vendor.setAddress(incoming.getAddress());
        vendor.setGstNumber(incoming.getGstNumber());
        vendorRepository.save(vendor);
    }

    /**
     * Hard-delete: removes the company-vendor link, and if no other company
     * is using that vendor, deletes the master vendor row too.
     * Agar vendor is company ki list me hai hi nahi to "Vendor not found".
     */
    public void removeVendorForCompany(Long vendorId, String companyName) {
        // 1. Link hona zaroori hai, warna dusri company ka vendor delete ho sakta tha
        CompanyVendor link = companyVendorRepository
                .findByCompanyNameAndVendorId(companyName, vendorId)
                .orElseThrow(() -> new IllegalArgumentException("Vendor not found"));

        companyVendorRepository.delete(link);

        // 2. If no other company is linked to this vendor, delete the master vendor too
        List<CompanyVendor> remainingLinks = companyVendorRepository.findByVendorId(vendorId);
        if (remainingLinks.isEmpty()) {
            vendorRepository.deleteById(vendorId);
        }
    }
}