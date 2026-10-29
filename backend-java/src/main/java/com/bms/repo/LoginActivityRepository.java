package com.bms.repo;

import com.bms.domain.LoginActivity;
import com.bms.domain.LoginOutcome;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.OffsetDateTime;
import java.util.Collection;

public interface LoginActivityRepository extends JpaRepository<LoginActivity, Long>,
        JpaSpecificationExecutor<LoginActivity> {

    Page<LoginActivity> findByUserId(Long userId, Pageable pageable);

    long countByOutcomeInAndCreatedAtGreaterThanEqual(Collection<LoginOutcome> outcomes, OffsetDateTime from);
}
