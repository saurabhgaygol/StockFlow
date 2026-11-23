package com.stockmanagement.repository;

import com.stockmanagement.entity.TagPrintSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TagPrintSettingRepository extends JpaRepository<TagPrintSetting, Long> {

    Optional<TagPrintSetting> findByCompanyName(String companyName);
}