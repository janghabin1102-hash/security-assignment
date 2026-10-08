package com.example.demo.service;

import com.example.demo.domain.Member;
import com.example.demo.dto.MemberDTO.MemberResponse;
import com.example.demo.enums.RoleType;
import com.example.demo.exception.ApiException;
import com.example.demo.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {
    private final MemberRepository memberRepository;

    @Transactional(readOnly = true)
    public MemberResponse getCurrentMember(String userId) {
        return MemberResponse.from(findAuthenticatedMember(userId));
    }

    @Transactional
    public MemberResponse updateRole(String actorUserId, Long memberId, RoleType requestedRole) {
        Member actor = findAuthenticatedMember(actorUserId);
        // HTTP 인가 외에도 Service 진입점에서 DB의 관리자 권한을 검증한다.
        if (actor.getRoleType() != RoleType.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "관리자만 권한을 변경할 수 있습니다.");
        }
        if (requestedRole != RoleType.ADMIN) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADMIN으로의 승격만 지원합니다.");
        }
        Member target = memberRepository.findById(memberId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "대상 회원을 찾을 수 없습니다."));
        // 이미 ADMIN이어도 성공. 트랜잭션 커밋 시 JPA dirty checking으로 저장한다.
        target.promoteToAdmin();
        return MemberResponse.from(target);
    }

    private Member findAuthenticatedMember(String userId) {
        Member member = memberRepository.findByUserId(userId);
        if (member == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "인증된 회원이 존재하지 않습니다.");
        }
        return member;
    }
}
