package com.example.demo.domain;

import com.example.demo.enums.RoleType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true, length = 80)
    private String userId;

    @Column(nullable = false, length = 128)
    private String password;

    @Column(nullable = false, length = 80)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_type", nullable = false, length = 20)
    private RoleType roleType = RoleType.USER;

    public Member(String userId, String password, String username) {
        this.userId = userId;
        this.password = password;
        this.username = username;
    }

    public void promoteToAdmin() {
        this.roleType = RoleType.ADMIN;
    }
}
