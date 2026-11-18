package com.stockmanagement.entity;

import jakarta.persistence.*;

/** Last invoice number used by a company in a year. The row is locked while a new number is taken. */
@Entity
@Table(name = "retail_bill_counter",
    uniqueConstraints = @UniqueConstraint(name = "uk_retail_bill_counter", columnNames = {"company_name", "bill_year"}))
public class RetailBillCounter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    @Column(name = "bill_year", nullable = false)
    private Integer billYear;

    @Column(name = "last_no", nullable = false)
    private Long lastNo = 0L;

    public Long getId() { return id; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public Integer getBillYear() { return billYear; }
    public void setBillYear(Integer billYear) { this.billYear = billYear; }
    public Long getLastNo() { return lastNo; }
    public void setLastNo(Long lastNo) { this.lastNo = lastNo; }
}