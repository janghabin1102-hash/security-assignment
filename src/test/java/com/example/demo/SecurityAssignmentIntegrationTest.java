package com.example.demo;

import com.example.demo.domain.Member;
import com.example.demo.enums.RoleType;
import com.example.demo.exception.ApiException;
import com.example.demo.repository.MemberRepository;
import com.example.demo.security.JwtUtility;
import com.example.demo.service.MemberService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityAssignmentIntegrationTest {
    private static final SecretKey TEST_KEY = Keys.secretKeyFor(SignatureAlgorithm.HS256);
    private static final String PASSWORD = "Test-pass-123!";

    @DynamicPropertySource
    static void configureJwt(DynamicPropertyRegistry registry) {
        registry.add("jwt.base64Secret", () -> Base64.getEncoder().encodeToString(TEST_KEY.getEncoded()));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MemberRepository repository;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtUtility jwtUtility;
    @MockitoSpyBean MemberService memberService;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void loginJwtResolvesPrincipalAndPassesLoginIdentifierToService() throws Exception {
        Member member = createMember("login-id", "Different Display Name", RoleType.USER);
        mvc.perform(get("/api/members/me").header("Authorization", bearer(login("login-id"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(member.getId()))
                .andExpect(jsonPath("$.userId").value("login-id"))
                .andExpect(jsonPath("$.username").value("Different Display Name"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist());
        verify(memberService).getCurrentMember("login-id");
    }

    @Test
    void protectedEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/members/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mvc.perform(patch("/api/members/1/role").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredJwtReturnsJson401() throws Exception {
        createMember("user", "User", RoleType.USER);
        String expired = Jwts.builder().setSubject("user")
                .setExpiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(TEST_KEY, SignatureAlgorithm.HS256).compact();
        mvc.perform(get("/api/members/me").header("Authorization", bearer(expired)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void forgedJwtIsRejected() throws Exception {
        createMember("user", "User", RoleType.USER);
        String forged = Jwts.builder().setSubject("user").claim("role", "ADMIN")
                .setExpiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.secretKeyFor(SignatureAlgorithm.HS256), SignatureAlgorithm.HS256).compact();
        mvc.perform(get("/api/members/me").header("Authorization", bearer(forged)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer broken", "Bearer ", "Basic wrong"})
    void malformedAuthenticationIsRejected(String header) throws Exception {
        mvc.perform(get("/api/members/me").header("Authorization", header))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenForDeletedMemberReturns401() throws Exception {
        Member member = createMember("deleted", "Deleted", RoleType.USER);
        String token = login("deleted");
        repository.deleteById(member.getId());
        mvc.perform(get("/api/members/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void jwtWithoutSubjectReturns401() throws Exception {
        String token = Jwts.builder().setExpiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(TEST_KEY, SignatureAlgorithm.HS256).compact();
        mvc.perform(get("/api/members/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userCannotPromoteSelfOrOthersAndDatabaseStaysUnchanged() throws Exception {
        Member actor = createMember("actor", "Actor", RoleType.USER);
        Member other = createMember("other", "Other", RoleType.USER);
        String token = login("actor");
        for (Long id : List.of(actor.getId(), other.getId())) {
            mvc.perform(roleRequest(id, token, "{\"role\":\"ADMIN\"}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("FORBIDDEN"));
            assertThat(repository.findById(id).orElseThrow().getRoleType()).isEqualTo(RoleType.USER);
        }
    }

    @Test
    void adminPromotionCommitsAndOldJwtUsesDatabaseRoleOnNextRequest() throws Exception {
        createMember("admin", "Admin", RoleType.ADMIN);
        Member target = createMember("target", "Target", RoleType.USER);
        Member another = createMember("another", "Another", RoleType.USER);
        String adminToken = login("admin");
        String oldUserToken = login("target");

        mvc.perform(roleRequest(target.getId(), adminToken, "{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberId").value(target.getId()))
                .andExpect(jsonPath("$.userId").value("target"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.password").doesNotExist());
        verify(memberService).updateRole("admin", target.getId(), RoleType.ADMIN);
        // 이 테스트 자체에는 @Transactional이 없다. HTTP 요청의 커밋 후 별도 조회한다.
        assertThat(repository.findById(target.getId()).orElseThrow().getRoleType()).isEqualTo(RoleType.ADMIN);
        assertThat(jwtUtility.getClaimsFromJwt(oldUserToken).get("role", String.class)).isEqualTo("USER");
        mvc.perform(get("/api/members/me").header("Authorization", bearer(oldUserToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
        mvc.perform(roleRequest(another.getId(), oldUserToken, "{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk());
        assertThat(repository.findById(another.getId()).orElseThrow().getRoleType()).isEqualTo(RoleType.ADMIN);
    }

    @Test
    void repeatedPromotionIsIdempotent() throws Exception {
        Member admin = createMember("admin", "Admin", RoleType.ADMIN);
        String token = login("admin");
        for (int i = 0; i < 2; i++) {
            mvc.perform(roleRequest(admin.getId(), token, "{\"role\":\"ADMIN\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
        }
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void missingTargetReturns404() throws Exception {
        createMember("admin", "Admin", RoleType.ADMIN);
        mvc.perform(roleRequest(Long.MAX_VALUE, login("admin"), "{\"role\":\"ADMIN\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"role\":null}", "{\"role\":\"USER\"}",
            "{\"role\":\"MEMBER\"}", "{\"role\":\"unknown\"}", "{\"role\":1}",
            "{\"role\":\"ADMIN\",\"extra\":true}", "broken"})
    void invalidRolesReturn400WithoutChangingDatabase(String body) throws Exception {
        createMember("admin", "Admin", RoleType.ADMIN);
        Member target = createMember("target", "Target", RoleType.USER);
        mvc.perform(roleRequest(target.getId(), login("admin"), body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        assertThat(repository.findById(target.getId()).orElseThrow().getRoleType()).isEqualTo(RoleType.USER);
    }

    @Test
    void invalidMemberIdReturns400() throws Exception {
        createMember("admin", "Admin", RoleType.ADMIN);
        mvc.perform(patch("/api/members/not-a-number/role").header("Authorization", bearer(login("admin")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void serviceAlsoRejectsNonAdminInvocation() {
        Member actor = createMember("actor", "Actor", RoleType.USER);
        assertThatThrownBy(() -> memberService.updateRole("actor", actor.getId(), RoleType.ADMIN))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(403));
        assertThat(repository.findById(actor.getId()).orElseThrow().getRoleType()).isEqualTo(RoleType.USER);
    }

    @Test
    void signupStoresBcryptAndLoginWorks() throws Exception {
        mvc.perform(post("/api/members").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                        Map.of("userId", "new-user", "username", "Display Name", "password", PASSWORD))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist());
        Member member = repository.findByUserId("new-user");
        assertThat(member.getPassword()).isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, member.getPassword())).isTrue();
        assertThat(login("new-user")).isNotBlank();
    }

    @Test
    void signupRejectsRoleInjection() throws Exception {
        mvc.perform(post("/api/members").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                        Map.of("userId", "injected", "username", "Injected", "password", PASSWORD, "role", "ADMIN"))))
                .andExpect(status().isBadRequest());
        assertThat(repository.findByUserId("injected")).isNull();
    }

    @Test
    void duplicateSignupReturns409() throws Exception {
        createMember("duplicate", "Duplicate", RoleType.USER);
        mvc.perform(post("/api/members").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                        Map.of("userId", "duplicate", "username", "Duplicate", "password", PASSWORD))))
                .andExpect(status().isConflict());
    }

    @Test
    void wrongLoginReturns401() throws Exception {
        createMember("user", "User", RoleType.USER);
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void corsPreflightPreservesAllowedOriginAndPatch() throws Exception {
        mvc.perform(options("/api/members/1/role").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    private Member createMember(String userId, String username, RoleType role) {
        Member member = new Member(userId, encoder.encode(PASSWORD), username);
        if (role == RoleType.ADMIN) member.promoteToAdmin();
        return repository.saveAndFlush(member);
    }

    private String login(String userId) throws Exception {
        String response = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("userId", userId, "password", PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).get("accessToken").asText();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder roleRequest(
            Long id, String token, String body) {
        return patch("/api/members/{memberId}/role", id).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String bearer(String token) { return "Bearer " + token; }
}
