package com.example.demo.security;



import com.example.demo.enums.RoleType;
import com.example.demo.exception.HandleJwtException;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * JWT (Json Web Token) 생성 및 검증 유틸리티 클래스
 *
 * ── 📚 초보자를 위한 JWT 개념 설명 ──
 *
 * JWT는 "인증 정보를 담은 토큰"입니다.
 * 예를 들어, 호텔에서 체크인하면 "방 열쇠(토큰)"를 받는 것과 같습니다.
 *
 * 1. 로그인 성공 → 서버가 JWT 토큰 발급 (방 열쇠 받기)
 * 2. 이후 요청 시 → 헤더에 토큰을 넣어서 보냄 (방 열쇠 보여주기)
 * 3. 서버가 토큰 검증 → 유효하면 요청 처리 (열쇠 확인 후 입장 허용)
 *
 * JWT 구조: Header.Payload.Signature (점(.)으로 구분된 3부분)
 * - Header: 알고리즘 정보
 * - Payload: 회원 ID, 이름 등 실제 데이터
 * - Signature: 위변조 방지를 위한 서명
 */
@Service
public class JwtUtility {
    private final SecretKey secretKey; // JWT 서명에 사용되는 비밀 키 // 생성한 비밀 키의 타입이 SecretKey타입

    private static final long expirationTime = 1000 * 60 * 60; // 토큰 만료 시간: 1시간

    public JwtUtility(@Value("${jwt.base64Secret}") String base64Secret){ //@Value을 통해 application.yml에서 값 주입
        byte[] decodedkey = Base64.getDecoder().decode(base64Secret);//Base64로 인코딩된 문자열을 디코딩하여 바이트 배열로 반환
        this.secretKey=Keys.hmacShaKeyFor(decodedkey); //
    }

    // JWT 생성
    public String generateJwt(String userId, String username, RoleType roleType) {

        // ✅ 1) 현재 시각을 UTC 기준으로 얻기
        // Instant는 타임존(지역 시간) 개념이 없는 "순수한 UTC 시각"이라 만료 시간 계산에 안전합니다.
        Instant now = Instant.now();

        // ✅ 2) JWT 빌더로 토큰 생성 시작
        return Jwts.builder()

                // ✅ 3) 토큰의 주체(Subject)
                // "이 토큰은 누구의 것인가?"를 나타내는 대표 식별자입니다. (보통 userId 또는 DB PK)
                .setSubject(userId)

                // ✅ 4) Payload(Claims)에 추가 정보 저장
                // ※ JWT Payload는 암호화가 아니라 Base64 인코딩이라 누구나 읽을 수 있습니다.
                //    따라서 비밀번호/주민번호 같은 민감 정보는 절대 넣으면 안 됩니다.
                .claim("name", username)              // 사용자 이름(표시/편의 목적)
                .claim("role", roleType.name())       // 발급 시점 정보. 실제 인가는 매 요청 DB 권한을 사용.

                // ✅ 5) 발급 시간(iat) / 만료 시간(exp) 설정
                // JJWT 0.11.5는 Date 기반 API이므로 Instant를 Date로 변환해서 넣어야 합니다.
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusMillis(expirationTime)))

                // ✅ 6) 서명(Signature)
                // SecretKey로 서명하여 토큰 위변조를 방지합니다.
                // (payload를 바꿔치기 하면 서명이 깨져서 검증 단계에서 거절됩니다.)
                .signWith(secretKey, SignatureAlgorithm.HS256)

                // ✅ 7) 최종 JWT 문자열 생성: header.payload.signature 형태
                .compact();
    }

    public boolean validateJwt(String jwt){
        try{
            // Authorization 헤더에 "Bearer "가 포함되어 있으면 제거

            // JWT 파서 생성 → 서명 키 설정 → 토큰 파싱(검증)
            // 이 과정에서 서명, 만료시간, 구조 등이 자동으로 검증됨
            Jwts.parserBuilder()
                    .setSigningKey(secretKey) // JWT 서명 검증에 사용할 SecretKey
                    .build()
                    .parseClaimsJws(jwt);     // 토큰 파싱 및 유효성 검증
            // 예외가 발생하지 않으면 유효한 JWT
            return true;

        } catch (ExpiredJwtException e){
            // 토큰의 만료 시간이 지난 경우
            throw new HandleJwtException("만료된 JWT");
        } catch (UnsupportedJwtException e){
            // 지원되지 않는 JWT 형식인 경우
            throw new HandleJwtException("지원되지 않는 JWT 형식");
        } catch (MalformedJwtException e){
            // JWT 구조(Header.Payload.Signature)가 깨진 경우
            throw new HandleJwtException("손상된 JWT");
        } catch (SecurityException e){
            // 서명 검증 실패 (SecretKey 불일치 등)
            throw new HandleJwtException("서명이 올바르지 않은 JWT");
        } catch (IllegalArgumentException e){
            // JWT 문자열이 null이거나 비어있는 경우
            throw new HandleJwtException("JWT가 NULL이거나 빈 문자열임");
        } catch (JwtException e){
            // 그 외 JWT 처리 중 발생한 예외
            throw new HandleJwtException("기타 JWT관련 예외");
        }
    }

    public Claims getClaimsFromJwt(String jwt){
        // Authorization 헤더에서 받은 JWT 문자열을 그대로 저장
        // (Bearer 토큰일 수도 있고, 순수 JWT일 수도 있음)$
        String noneBearerJwt = jwt;

        // JWT가 "Bearer "로 시작하는 경우
        // → 실제 토큰 값만 사용하기 위해 "Bearer " 부분 제거
        if(jwt.startsWith("Bearer ")){
            noneBearerJwt = jwt.substring(7);
        }

        // JWT 파서 생성 및 검증 시작
        return Jwts.parserBuilder()
                .setSigningKey(secretKey)      // JWT 서명 검증에 사용할 SecretKey 설정
                .build()                        // 파서 빌드
                .parseClaimsJws(noneBearerJwt)  // 서명 + 만료시간 + 위변조 여부 검증
                .getBody();                     // 검증 완료 후 Payload(Claims) 반환
    }


}
