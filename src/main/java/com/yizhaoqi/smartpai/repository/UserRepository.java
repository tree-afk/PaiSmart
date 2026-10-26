package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.KnowledgeRole;
import com.yizhaoqi.smartpai.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    Page<User> findByUsernameContainingIgnoreCase(String keyword, Pageable pageable);

    /**
     * 角色变更前锁定全部角色管理者；所有角色变更都先走这把锁，
     * 并发降级会串行化，避免两次统计都看到“还有两名管理者”。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.knowledgeRole = :role order by u.id asc")
    List<User> findByKnowledgeRoleForUpdate(@Param("role") KnowledgeRole role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findForUpdate(@Param("id") Long id);
}
