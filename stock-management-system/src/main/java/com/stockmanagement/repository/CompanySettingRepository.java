package com.stockmanagement.repository;

import com.stockmanagement.entity.CompanySetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanySettingRepository extends JpaRepository<CompanySetting, Long> {

    Optional<CompanySetting> findByCompanyNameAndSettingKey(String companyName, String settingKey);
}