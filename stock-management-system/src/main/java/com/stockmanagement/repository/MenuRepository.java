package com.stockmanagement.repository;



import com.stockmanagement.entity.Menu;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MenuRepository extends JpaRepository<Menu, Long> {
    List<Menu> findByStatusOrderByDisplayOrderAsc(String status);
}