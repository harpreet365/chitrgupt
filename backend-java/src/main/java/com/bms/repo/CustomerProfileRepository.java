package com.bms.repo;

import com.bms.domain.CustomerProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, Long> {

    Optional<CustomerProfile> findByUser_Id(Long userId);

    Optional<CustomerProfile> findByCustomerNumber(String customerNumber);

    @Query(value = "select nextval('customer_number_seq')", nativeQuery = true)
    long nextCustomerNumber();

    /** Search by name, username, email, customer number or one of the customer's account numbers. */
    @Query("""
            select c from CustomerProfile c join c.user u
            where (:q = ''
               or lower(u.fullName) like lower(concat('%', :q, '%'))
               or lower(u.username) like lower(concat('%', :q, '%'))
               or lower(u.email) like lower(concat('%', :q, '%'))
               or upper(c.customerNumber) = upper(:q)
               or exists (select a.id from BankAccount a where a.customer = c and a.accountNumber = :q))
            """)
    Page<CustomerProfile> search(String q, Pageable pageable);
}
