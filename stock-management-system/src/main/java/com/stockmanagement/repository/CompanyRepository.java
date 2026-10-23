package com.stockmanagement.repository;

import com.stockmanagement.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    /** Naam se (bade-chhote akshar ka farak nahi), taaki "Satcop" aur "satcop" do alag na banein. */
    Optional<Company> findFirstByCompanyNameIgnoreCase(String companyName);

    /** Dropdown ke liye: chalu companies A-Z. */
    List<Company> findByStatusOrderByCompanyNameAsc(String status);
}