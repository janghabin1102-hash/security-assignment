package com.example.demo.dto;

import com.example.demo.domain.Member;
import com.example.demo.enums.RoleType;
import jakarta.validation.constraints.*;

public final class MemberDTO {
    private MemberDTO() {}

    public record SignupRequest(
            @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9_.-]+") String userId,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 80) String username) {}

    public record LoginRequest(
            @NotBlank @Size(max = 80) String userId,
            @NotBlank @Size(max = 72) String password) {}

    public record RoleUpdateRequest(@NotNull RoleType role) {}

    public record MemberResponse(Long memberId, String userId, String username, RoleType role) {
        public static MemberResponse from(Member member) {
            return new MemberResponse(member.getId(), member.getUserId(),
                    member.getUsername(), member.getRoleType());
        }
    }

    public record LoginResponse(String tokenType, String accessToken) {}
}
