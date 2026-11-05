package com.stockmanagement.repository;

import com.stockmanagement.entity.RetailStockOut;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RetailStockOutRepository extends JpaRepository<RetailStockOut, Long> {

    List<RetailStockOut> findByCompanyNameOrderByIdDesc(String companyName, Pageable pageable);

    List<RetailStockOut> findByCompanyNameAndCreatedAtBetweenOrderByIdDesc(String companyName,
                                                                           LocalDateTime from, LocalDateTime to,
                                                                           Pageable pageable);

    Optional<RetailStockOut> findByIdAndCompanyName(Long id, String companyName);

    /** all items of one outward (oldest first); older single-item rows without a group count as their own group */
    @Query("select o from RetailStockOut o where o.companyName = :company "
         + "and (o.outGroupId = :group or (o.outGroupId is null and o.id = :group)) order by o.id asc")
    List<RetailStockOut> findGroup(@Param("company") String company, @Param("group") Long group);
}