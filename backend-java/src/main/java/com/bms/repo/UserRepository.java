package com.bms.repo;

import com.bms.domain.RoleName;
import com.bms.domain.User;
import com.bms.domain.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    @Query("select u from User u where lower(u.username) = lower(:username)")
    Optional<User> findByUsernameIgnoreCase(String username);

    @Query("select u.id from User u where lower(u.username) = lower(:username)")
    Optional<Long> findIdByUsername(String username);

    /** Row lock on the users row only (avoids Hibernate follow-on locking with eager associations). */
    @Query(value = "select * from users where id = :id for update", nativeQuery = true)
    Optional<User> lockById(Long id);

    @Query("select u.id from User u where lower(u.email) = lower(:email)")
    Optional<Long> findIdByEmail(String email);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email) and u.id <> :excludeId")
    boolean emailTakenByOther(String email, Long excludeId);

    long countByRole_Name(RoleName role);

    long countByRole_NameAndStatus(RoleName role, UserStatus status);
}
