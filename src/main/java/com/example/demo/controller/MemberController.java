package com.example.demo.controller;

import com.example.demo.dto.MemberDTO.*;
import com.example.demo.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {
    private final MemberService memberService;

    @GetMapping("/me")
    public MemberResponse me(@AuthenticationPrincipal UserDetails userDetails) {
        // getUsername()은 표시 이름이 아니라 기존 principal에 저장된 로그인 userId다.
        return memberService.getCurrentMember(userDetails.getUsername());
    }

    @PatchMapping("/{memberId}/role")
    public MemberResponse updateRole(@AuthenticationPrincipal UserDetails userDetails,
                                     @PathVariable Long memberId,
                                     @Valid @RequestBody RoleUpdateRequest request) {
        return memberService.updateRole(userDetails.getUsername(), memberId, request.role());
    }
}
