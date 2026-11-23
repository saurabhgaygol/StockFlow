package com.stockmanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Last TAG number used by a company in a year (same idea as RetailBillCounter).
 * The row is locked while a batch is saved, so two users can never get the same TAG number.
 */
@Entity
@Table(name = "tag_counter",
    uniqueConstraints = @UniqueConstraint(name = "uk_tag_counter", columnNames = {"company_name", "tag_year"}))
public class TagCounter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    @Column(name = "tag_year", nullable = false)
    private Integer tagYear;

    @Column(name = "last_no", nullable = false)
    private Long lastNo = 0L;

    public Long getId() { return id; }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    public Integer getTagYear() { return tagYear; }
    public void setTagYear(Integer tagYear) { this.tagYear = tagYear; }

    public Long getLastNo() { return lastNo; }
    public void setLastNo(Long lastNo) { this.lastNo = lastNo; }
}