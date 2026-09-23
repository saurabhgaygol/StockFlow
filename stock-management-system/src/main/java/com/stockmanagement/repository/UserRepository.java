package com.stockmanagement.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.stockmanagement.entity.UserTable;

import java.util.Optional;
public interface UserRepository extends JpaRepository<UserTable, Long>{
	
	  Optional<UserTable> findByUsername(String username);

	    boolean existsByUsername(String username);

	    boolean existsByEmployeeId(String employeeId);

	    boolean existsBySystemGeneratedId(String systemGeneratedId);

}
