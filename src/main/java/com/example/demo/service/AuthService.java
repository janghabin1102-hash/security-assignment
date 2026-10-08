package com.example.demo.service;

import com.example.demo.domain.Member;
import com.example.demo.dto.MemberDTO.*;
import com.example.demo.exception.ApiException;
import com.example.demo.repository.MemberRepository;
import com.example.demo.security.JwtUtility;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtility jwtUtility;

    @Transactional
    public MemberResponse signup(SignupRequest request) {
        validatePasswordLength(request.password());
        if (memberRepository.existsByUserId(request.userId())) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 사용 중인 userId입니다.");
        }
        // 회원가입은 항상 USER. 요청으로 권한을 지정할 수 없다.
        Member member = new Member(request.userId(), passwordEncoder.encode(request.password()), request.username());
        return MemberResponse.from(memberRepository.saveAndFlush(member));
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        validatePasswordLength(request.password());
        Member member = memberRepository.findByUserId(request.userId());
        if (member == null || !passwordEncoder.matches(request.password(), member.getPassword())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인 정보가 올바르지 않습니다.");
        }
        // 로그인 시 토큰 발급은 인증 기능의 책임이다.
        return new LoginResponse("Bearer", jwtUtility.generateJwt(
                member.getUserId(), member.getUsername(), member.getRoleType()));
    }

    private void validatePasswordLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
    }
}
