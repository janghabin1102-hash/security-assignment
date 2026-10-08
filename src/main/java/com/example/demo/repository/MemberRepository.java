package com.example.demo.repository;

import com.example.demo.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {
    // 기존 CustomUserDetailsService의 null 검사 계약을 유지한다.
    Member findByUserId(String userId);
    boolean existsByUserId(String userId);
}
