# Spring Security 과제

Controller에서 Spring Security 인증 객체를 사용하고, 관리자만 사용자를 `USER`에서 `ADMIN`으로 승격할 수 있는 REST API입니다.

## 제공된 코드와 보완 범위

원본은 `build.gradle`과 보안 관련 Java 파일 4개뿐이었습니다. Controller, Member 엔티티, Repository, RoleType, 실행 설정과 테스트는 없었습니다. 회원가입·로그인·내 정보 조회를 추가하여 실제 API에서 인증과 권한 변경을 검증할 수 있도록 보완했습니다. 게시글·댓글 기능은 새로 만들지 않았습니다.

- 유지한 원본 경로: `security/`, `service/CustomUserDetailsService.java`
- 수정: `security/SecurityConfig.java`, `security/JwtAuthenticationFilter.java`, `security/JwtUtility.java`, `build.gradle`
- 추가: `src/main/java/com/example/demo/` 아래 Application, Member, RoleType, DTO, Controller, AuthService, MemberService, Repository, 예외 처리
- 추가: `src/main/resources/application.yml`, 통합 테스트, Gradle Wrapper, 제출 문서

원본 폴더를 이동하지 않기 위해 Gradle의 main 소스 경로에 `security`와 `service`를 포함했습니다. 새 코드는 표준 `src/main/java` 경로에 있습니다.

## 요구사항 구현 흐름

```text
Authorization: Bearer JWT
→ JwtAuthenticationFilter: 서명·만료 검증, subject(userId) 추출
→ CustomUserDetailsService: DB 회원과 현재 권한 조회
→ UserDetails를 principal로 갖는 Authentication을 SecurityContext에 저장
→ Controller: @AuthenticationPrincipal UserDetails
→ userDetails.getUsername()으로 String userId 추출
→ MemberService: userId로 업무 처리
→ MemberRepository → DB
```

`UserDetails.getUsername()`은 **로그인 식별자 `userId`**입니다. 화면에 표시하는 `Member.username`이나 숫자 PK `Member.id`와 다릅니다.

Controller 적용 예:

```java
@GetMapping("/me")
public MemberResponse me(@AuthenticationPrincipal UserDetails userDetails) {
    return memberService.getCurrentMember(userDetails.getUsername());
}
```

기존 필터는 이미 인증 객체를 만들고 있었지만 이를 사용하는 실제 Controller와 비즈니스 Service가 제공되지 않았습니다. 새 MemberService는 JWT나 SecurityContextHolder에 의존하지 않고 식별자만 받습니다. AuthService의 로그인 시 JWT **발급**과 CustomUserDetailsService의 principal 생성은 인증 계층의 기능으로 유지합니다.

권한 변경 흐름:

```text
PATCH /api/members/{memberId}/role
→ SecurityConfig: ADMIN만 허용
→ MemberController: principal에서 호출자 userId 추출
→ MemberService.updateRole(actorUserId, memberId, role)
→ DB에서 호출자 ADMIN 재확인, 대상 회원 조회
→ Member.promoteToAdmin()
→ @Transactional 커밋 시 JPA dirty checking으로 DB 저장
→ MemberResponse 반환
```

## API

| 메서드 | 경로 | 조건 | 성공 |
|---|---|---|---|
| POST | `/api/members` | 공개, 항상 USER 가입 | 201 |
| POST | `/api/login` | 공개, 올바른 로그인 정보 | 200 |
| GET | `/api/members/me` | 로그인, USER 또는 ADMIN | 200 |
| PATCH | `/api/members/{memberId}/role` | ADMIN | 200 |

회원가입 요청:

```json
{"userId":"student","username":"학생 이름","password":"사용자가 정한 비밀번호"}
```

로그인 요청:

```json
{"userId":"student","password":"가입할 때 사용한 비밀번호"}
```

로그인 응답은 `{"tokenType":"Bearer","accessToken":"<JWT>"}`이며, 이후 요청 헤더에 `Authorization: Bearer <JWT>`를 넣습니다. JWT 만료 시간은 원본의 1시간입니다.

권한 변경 요청:

```http
PATCH /api/members/2/role
Authorization: Bearer <관리자 JWT>
Content-Type: application/json

{"role":"ADMIN"}
```

성공 응답 예시(실제 PK와 회원 정보에 따라 달라짐):

```json
{"memberId":2,"userId":"student","username":"학생 이름","role":"ADMIN"}
```

`memberId`는 대상 회원의 **Long DB PK**이며 호출자의 `userId`와 구분합니다. 비밀번호와 해시는 반환하지 않습니다.

| 상황 | 상태 | error |
|---|---|---|
| 인증 없음·만료·변조 JWT·삭제된 회원의 JWT | 401 | UNAUTHORIZED |
| USER가 자신 또는 타인의 권한을 변경 | 403 | FORBIDDEN |
| ADMIN이 없는 대상 회원을 변경 | 404 | NOT_FOUND |
| role 누락·null·알 수 없는 값·USER 강등 요청·잘못된 ID 형식 | 400 | BAD_REQUEST |
| 이미 ADMIN인 대상의 ADMIN 변경 재요청 | 200 | 동일 상태 반환 |
| 중복 userId 회원가입 | 409 | CONFLICT |

오류는 `{"error":"FORBIDDEN","message":"접근 권한이 없습니다."}` 같은 JSON 형식입니다. 요청 DTO에 없는 필드도 400으로 거절하므로 회원가입 시 `role`을 주입할 수 없습니다. 비밀번호는 BCrypt로 저장하고 UTF-8 기준 72바이트를 초과하면 거절합니다.

## 권한 정책과 JWT 변경 후 동작

- 과제 문구에 맞춰 일반 권한을 `USER`, 관리자를 `ADMIN`으로 통일했습니다. DB에는 문자열 enum, Spring Security에는 `ROLE_USER` / `ROLE_ADMIN`으로 표현합니다.
- 과제에 호출자 정책이 없으므로 **ADMIN만 승격 가능**을 추가 정책으로 적용했습니다. 공개 관리자 생성 API는 없습니다.
- 승격만 지원하며 ADMIN → USER 강등은 400입니다. 이미 ADMIN인 경우 200으로 멱등 처리합니다.
- 필터는 매 요청 DB 회원을 조회하므로 **승격 전 발급한 유효 JWT도 다음 요청부터 ADMIN으로 인가**됩니다. JWT 내부 role claim은 발급 당시 USER로 남습니다. 인가의 기준은 DB의 현재 권한입니다.
- 원본 게시글·댓글의 URL 접근 정책은 MEMBER를 USER로 바꾸어 유지했습니다. 게시글 변경은 USER, 댓글 변경은 USER 또는 ADMIN입니다. ADMIN이 USER 권한을 자동 상속하는 정책은 없습니다. 현재 프로젝트에는 게시글·댓글 Controller가 없어 해당 기능의 실제 동작과 소유권 검증은 확인 대상이 아닙니다.
- 프런트엔드 CORS 허용 주소는 원본의 `http://localhost:3000`, `http://localhost:5173`입니다.
- 세션·HTTP Basic·폼 로그인은 사용하지 않으며 Authorization 헤더 기반 JWT를 사용합니다.

## 실행 환경

- Java 21, Gradle Wrapper 8.12.1
- Spring Boot 3.4.1, JJWT 0.11.5
- 애플리케이션 DB: MySQL. 테스트 DB: H2(MySQL 모드)
- security starter의 개별 버전 3.2.0을 제거하고 Boot 의존성 관리를 사용합니다.
- JWT secret과 DB 접속 정보는 환경변수로 받으며 저장소에 포함하지 않습니다.

PowerShell에서 Java 21 경로를 환경에 맞게 설정합니다. Java 26이 기본 설치되어 있다면 Java 21을 지정해야 합니다.

```powershell
$env:JAVA_HOME = 'C:\경로\jdk-21'
.\gradlew.bat clean test bootJar
```

처음 실행할 때 Gradle과 Maven Central 의존성을 다운로드하므로 네트워크가 필요합니다. 테스트는 DB 접속 정보 없이 실행 가능하며 JWT 테스트 키를 매번 임의 생성합니다.

`gradle.properties`의 `file.encoding=COMPAT`은 Windows 한글 경로에서 Gradle 테스트 실행기의 클래스 경로 인코딩을 OS와 맞춥니다. Java 소스 컴파일은 별도로 UTF-8을 지정합니다. Linux에서는 OS의 기본 인코딩을 따릅니다.

MySQL에 과제용 새 DB를 준비합니다(기존 운영 DB에 실행하지 마세요).

```sql
CREATE DATABASE security_assignment CHARACTER SET utf8mb4;
```

앱 실행 환경변수:

```powershell
$env:DB_URL = 'jdbc:mysql://localhost:3306/security_assignment'
$env:DB_USERNAME = '과제용_DB_사용자'
$env:DB_PASSWORD = '해당_사용자의_비밀번호'
# 개발용 새 DB의 테이블 생성에만 사용. 기본값은 validate입니다.
$env:DB_DDL_AUTO = 'update'
$taskKeyBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($taskKeyBytes)
$env:JWT_BASE64_SECRET = [Convert]::ToBase64String($taskKeyBytes)
.\gradlew.bat bootRun
```

JWT secret은 Base64로 인코딩된 32바이트 이상의 키입니다. 재시작 후 기존 토큰을 유지하려면 같은 키를 안전하게 다시 주입해야 합니다. 위 명령은 실행 때마다 새 키를 생성하므로 이전 키로 발급한 JWT는 무효가 됩니다. 테이블 준비 후에는 `DB_DDL_AUTO=validate`로 사용합니다. 앱은 기본 8080 포트를 사용합니다.

## 초기 관리자 준비와 수동 검증

관리자 승격 API를 처음 호출할 ADMIN이 필요합니다. 아래 SQL 준비는 로컬 개발 DB에서만 수동 실행합니다. 코드가 시작할 때 자동 승격하지 않습니다.

1. `/api/members`로 `admin-seed` 사용자를 일반 USER로 가입합니다.
2. 로컬 과제 DB에서 해당 계정만 승격합니다.

```sql
UPDATE member SET role_type = 'ADMIN'
WHERE user_id = 'admin-seed' AND role_type = 'USER';
SELECT id, user_id, username, role_type FROM member WHERE user_id = 'admin-seed';
```

3. admin-seed로 로그인합니다. 일반 사용자 student도 가입하고 **승격 전** 로그인하여 JWT를 보관합니다.
4. 관리자 JWT로 student의 DB PK에 권한 변경 API를 호출합니다.
5. DB와 student의 기존 JWT를 사용한 후속 요청으로 결과를 확인합니다.

PowerShell 예시(앱을 별도 터미널에서 실행한 상태):

```powershell
$base = 'http://localhost:8080'
$adminPassword = Read-Host 'admin-seed 가입 시 비밀번호'
$studentPassword = Read-Host 'student 가입 시 비밀번호'
$adminLogin = Invoke-RestMethod -Method Post -Uri "$base/api/login" -ContentType 'application/json' -Body (@{userId='admin-seed'; password=$adminPassword} | ConvertTo-Json)
$studentLogin = Invoke-RestMethod -Method Post -Uri "$base/api/login" -ContentType 'application/json' -Body (@{userId='student'; password=$studentPassword} | ConvertTo-Json)
$adminHeaders = @{Authorization="Bearer $($adminLogin.accessToken)"}
$studentHeaders = @{Authorization="Bearer $($studentLogin.accessToken)"}
$before = Invoke-RestMethod -Uri "$base/api/members/me" -Headers $studentHeaders
$changed = Invoke-RestMethod -Method Patch -Uri "$base/api/members/$($before.memberId)/role" -Headers $adminHeaders -ContentType 'application/json' -Body '{"role":"ADMIN"}'
$changed
# 승격 전에 받은 JWT로 DB의 ADMIN 역할 확인
Invoke-RestMethod -Uri "$base/api/members/me" -Headers $studentHeaders
# 같은 기존 JWT로 관리자 전용 API 접근 확인(이미 ADMIN → 멱등 200)
Invoke-RestMethod -Method Patch -Uri "$base/api/members/$($before.memberId)/role" -Headers $studentHeaders -ContentType 'application/json' -Body '{"role":"ADMIN"}'
```

DB에서 별도로 확인합니다.

```sql
SELECT id, user_id, username, role_type FROM member WHERE user_id = 'student';
```

## 기존 MEMBER 데이터 전환

기존 DB는 제공되거나 연결되지 않았으므로 자동 변경하지 않았습니다. 기존 테이블 구조와 백업을 먼저 확인하고 로컬/승인된 DB에서 아래 절차를 적용합니다.

VARCHAR 컬럼이면:

```sql
UPDATE member SET role_type = 'USER' WHERE role_type = 'MEMBER';
```

MySQL ENUM 컬럼이면 기존 enum 목록이 정확히 ADMIN/MEMBER인지 확인한 뒤 USER를 먼저 허용합니다.

```sql
ALTER TABLE member MODIFY role_type ENUM('ADMIN','MEMBER','USER') NOT NULL DEFAULT 'USER';
UPDATE member SET role_type = 'USER' WHERE role_type = 'MEMBER';
SELECT DISTINCT role_type FROM member;
ALTER TABLE member MODIFY role_type ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER';
```

다른 enum 값·스키마·테이블명이 있으면 해당 DB에 맞게 조정해야 합니다. 변경 전 `SHOW CREATE TABLE member`로 타입과 제약을 확인합니다.

## 검증과 제출

실제 실행 명령·결과·검증 범위는 [검증 기록](docs/verification.md)에 정리합니다. 통합 테스트는 mock 로그인 사용자가 아니라 `/api/login`으로 받은 JWT를 실제 필터에 전달합니다. 테스트에는 관리자 승격, 커밋 후 DB 재조회, 기존 JWT의 권한 반영, 실패 응답, BCrypt, 회원가입 권한 주입 거절, CORS가 포함됩니다.

GitHub에는 소스·테스트·Wrapper·문서만 제출하고 `build/`, `.gradle-user-home/`, 환경변수 값은 제외합니다. 저장소 연결 후 일반적인 업로드 명령은 다음과 같습니다.

```powershell
git init -b main
git add .gitignore build.gradle settings.gradle gradle.properties gradlew gradlew.bat gradle security service src README.md docs
git update-index --chmod=+x gradlew
git commit -m "Implement principal-based member APIs and admin role update"
git remote add origin https://github.com/janghabin1102-hash/security-assignment.git
git push -u origin main
```

이미 Git 초기화·커밋·remote 설정이 완료되었다면 해당 단계를 반복하지 않습니다. 실제 제출 링크는 업로드가 완료된 저장소 URL입니다.

제출 저장소: https://github.com/janghabin1102-hash/security-assignment

## 참고

- 제공된 `Security (1).pdf`: 15~18쪽 권한 enum·ADMIN 승격 과제, 36~51쪽 UserDetails 및 인증 객체, 52~59쪽 인가·CORS
- [과제 참고 글](https://growdev.tistory.com/121)
- [Spring Security @AuthenticationPrincipal 공식 문서](https://docs.spring.io/spring-security/reference/servlet/integrations/mvc.html)
- [Spring Security 요청 인가 공식 문서](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)
- [Spring Boot 3.4 Gradle 지원](https://docs.spring.io/spring-boot/3.4/gradle-plugin/index.html)
