package com.example.demo.security;

import com.example.demo.dto.ApiError;
import com.example.demo.exception.HandleJwtException;
import com.example.demo.service.CustomUserDetailsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtUtility jwtUtility;
    private final CustomUserDetailsService customUserDetailsService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 인증 실패만 여기서 처리한다. Controller/Service 예외까지 JWT 오류로 덮지 않는다.
        try {
            String jwt = resolveJwt(request);
            if (jwt != null && jwtUtility.validateJwt(jwt)) {
                Authentication authentication = getAuthentication(jwt);
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            }
        } catch (HandleJwtException | JwtException | AuthenticationException | IllegalArgumentException e) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            objectMapper.writeValue(response.getWriter(),
                    new ApiError("UNAUTHORIZED", "유효한 인증 정보가 필요합니다."));
            return;
        }
        chain.doFilter(request, response);
    }

    private String resolveJwt(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring(7);
    }

    private Authentication getAuthentication(String jwt) {
        String userId = jwtUtility.getClaimsFromJwt(jwt).getSubject();
        if (userId == null || userId.isBlank()) {
            throw new HandleJwtException("JWT subject가 없습니다.");
        }
        // JWT role claim이 아니라 매 요청 DB 조회 결과가 인가 기준이다.
        UserDetails userDetails = customUserDetailsService.loadUserByUsername(userId);
        return new UsernamePasswordAuthenticationToken(userDetails, jwt, userDetails.getAuthorities());
    }
}
