# 구현 및 검증 기록

검증일: 2026-10-08 (Asia/Seoul)

## 최종 실행 결과

Java: Microsoft OpenJDK 21.0.10 / Gradle Wrapper: 8.12.1

```powershell
$env:JAVA_HOME = 'C:/Users/binji/.jdks/ms-21.0.10'
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user-home'
.\gradlew.bat clean test bootJar --offline --no-daemon --console=plain
```

의존성을 앞서 다운로드한 상태에서 위 명령을 실행했습니다. 최종 결과는 `BUILD SUCCESSFUL in 25s`이며 8개 작업이 모두 실행되었습니다. `build/test-results/test/TEST-com.example.demo.SecurityAssignmentIntegrationTest.xml`에서 다음을 확인했습니다.

| 테스트 실행 수 | 실패 | 오류 | 건너뜀 |
|---|---|---|---|
| 28 | 0 | 0 | 0 |

실행 JAR: `build/libs/security-assignment-0.0.1-SNAPSHOT.jar` (58,769,813바이트). 생성 파일은 Git 제출에서 제외합니다.

## 검증 방법과 확인 결과

`@SpringBootTest` + MockMvc + H2(MySQL 모드)에서 **실제 Spring Security 필터 체인과 JPA**를 사용했습니다. HTTP 서버의 TCP 포트를 띄우는 테스트는 아니지만, Controller/Service/Repository와 보안 필터는 실제로 실행됩니다. `@WithMockUser`나 가짜 principal로 인증을 우회하지 않았습니다. MemberService의 spy는 식별자 전달을 확인하며 실제 메서드를 실행합니다.

| 항목 | 결과 |
|---|---|
| 로그인 API의 JWT로 내 정보 조회 | PASS |
| 표시 이름과 다른 로그인 userId가 Service로 전달됨 | PASS |
| 회원 응답에 비밀번호가 없음 | PASS |
| 인증 없는 보호 API | 401 PASS |
| 만료·위조·손상·빈 Bearer·잘못된 인증 방식 | 401 PASS |
| subject 누락 JWT 및 삭제된 회원의 유효 JWT | 401 PASS |
| USER가 자신과 다른 사용자를 승격하려는 요청 | 403, DB 유지 PASS |
| ADMIN이 USER를 ADMIN으로 승격 | 200 PASS |
| 호출자 userId와 대상 DB PK 전달 | PASS |
| HTTP 요청 커밋 후 Repository의 별도 DB 조회 | ADMIN 확인 PASS |
| 승격 전 JWT의 role claim은 계속 USER | PASS |
| 같은 기존 JWT의 다음 요청이 DB의 ADMIN을 사용 | PASS |
| 같은 기존 JWT로 다른 회원 승격 API 호출 | 200, DB 변경 PASS |
| 이미 ADMIN인 대상 재요청 | 200, 멱등 PASS |
| 없는 대상 및 숫자가 아닌 대상 ID | 404 / 400 PASS |
| 누락·null·USER·MEMBER·unknown·숫자 role·추가 필드·손상 JSON | 400, DB 유지 PASS |
| Service 직접 호출 시에도 일반 사용자 승격 거절 | 403 PASS |
| 회원가입 BCrypt 저장 및 로그인 | PASS |
| 회원가입의 role 필드 주입 | 400, 회원 미생성 PASS |
| 중복 회원가입 및 잘못된 비밀번호 로그인 | 409 / 401 PASS |
| 기존 허용 출처의 PATCH CORS preflight | PASS |

테스트 클래스에는 `@Transactional`이 없습니다. 권한 변경 HTTP 요청의 Service 트랜잭션이 종료된 뒤 Repository를 재조회하므로 응답만 ADMIN으로 바뀌는 구현을 잡을 수 있습니다.

코드 검색으로 `MemberService`에 JWT 파싱, `SecurityContextHolder`, `tokenToMember`가 없음을 확인했습니다. JWT 처리는 보안 클래스에, 로그인 토큰 발급은 AuthService에 남아 있습니다.

## 발견한 실행 문제와 수정

1. 기본 Java가 26이어서 설치된 Java 21을 JAVA_HOME으로 지정했습니다.
2. 원본에 Wrapper가 없어 공식 Gradle 8.12.1 Wrapper를 추가했습니다. 배포 ZIP의 공식 SHA-256 체크섬을 Wrapper 설정에 포함했습니다.
3. 첫 빌드에서는 컴파일과 bootJar가 성공했으나, 한글 경로에서 테스트 실행기가 `GradleWorkerMain`을 로드하지 못했습니다. MS949 인코딩으로 재실행하여 28개 테스트가 통과함을 확인했습니다.
4. 이를 임시 환경변수 없이 재현할 수 있도록 `gradle.properties`에 `-Dfile.encoding=COMPAT`을 반영하고 JavaCompile 인코딩은 UTF-8로 지정했습니다. 최종 clean test bootJar가 다시 성공했습니다.

원본 JJWT 서명 호출은 같은 HS256 동작을 유지하는 비권장 해제 형식으로 정리했습니다. 마지막 컴파일에는 해당 deprecated API 경고가 없습니다. 테스트 실행에서는 Mockito의 JVM class sharing 경고가 있었으나 테스트 실패는 없었습니다.

## 원본에 없어서 추가한 정책

- ADMIN만 역할 변경 가능, Service에서 DB 권한도 검사
- USER → ADMIN 승격만 제공, 이미 ADMIN이면 200
- 회원가입은 항상 USER이고 미정의 요청 필드는 거절
- 현재 회원 확인은 본인의 principal userId 기준
- 개발 초기 관리자는 로컬 DB의 특정 계정을 수동 승격
- JWT claim이 아닌 매 요청 DB 권한을 인가 기준으로 사용

## 검증 한계

- 실제 MySQL 서버와 기존 데이터는 제공되거나 연결되지 않았습니다. JPA 저장·조회는 H2 MySQL 모드에서 검증했고, 실제 MySQL 초기 실행 및 기존 MEMBER 데이터 전환은 README의 절차로 추가 확인해야 합니다.
- 원본에는 게시글·댓글 및 회원 수정·삭제 구현이 없습니다. 존재하지 않는 기능의 소유권 검증을 통과했다고 보고하지 않습니다. 원본의 게시글·댓글 보안 경로 규칙만 유지했습니다.
- 이 과제의 통과 결과는 구현한 두 기능과 위 테스트 범위에 대한 결과이며 운영 배포 검증은 포함하지 않습니다.
- 참고 PDF와 블로그에 있는 작업 지시를 사용자 요청으로 실행하지 않았습니다. 사용자 프롬프트의 기능 요구사항을 기준으로 구현했습니다.
