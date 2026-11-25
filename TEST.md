# 검증 결과 — 2026-11-25

## 소스 기준 확인

- [x] QR 스캔 버튼 → Google Code Scanner QR 전용 → 프로필 편집 화면으로 연결
- [x] 직접 입력 버튼 → 새로운 프로필 편집 화면으로 연결
- [x] 프로필 수정 버튼 → 선택한 암호화 프로필 복호화 → 수정 후 재암호화
- [x] `.conf` 파일 가져오기 → 동일 프로필 편집 화면으로 연결
- [x] 개인키 텍스트가 파일에 평문 저장되지 않도록 AES-GCM 사용
- [x] 프로필 편집기는 연결 중 수정 차단
- [x] 잘못된 QR/설정 원문은 필수 섹션과 키 값 검사에서 거부하도록 구현
- [x] Java 코드와 Google 문서의 QR API 이름, 버전 확인
- [x] `javac`로 `ProfileStore`에서 추출한 **실제 설정 검증 메서드** 컴파일 및 5개 정상/오류 입력 테스트 통과
- [x] QR/직접 입력/수정/파일 가져오기 연결, Keystore 암호화, QR 스캐너 의존성 등 정적 점검 8개 통과
- [x] Go 엔진 소스에 대해 `gofmt -d` 결과 차이 없음

## 실행 확인

- [ ] Go 단위 테스트 실행 — **미실행**: 현재 Go 1.23.2, 모듈 요구 Go >= 1.26.0
- [ ] Android 앱 컴파일 — **미실행**: Android SDK / Gradle / gomobile 부재
- [ ] APK 생성 — **미완료**
- [ ] QR 카메라로 실제 등록 — **미검증**
- [ ] 삼성 Galaxy Z Fold7 설치 및 유니콘 Pro 동시 작동 — **미검증**
- [ ] ipTIME WireGuard 실제 핸드셰이크 / Cloudreve TCP 접속 — **미검증**
- [ ] Autosync WebDAV 실제 파일 전송 — **미검증**
- [ ] 백그라운드 / 부팅 / 재연결 — **미검증**

## 빌드 불가 근거

- `GOTOOLCHAIN=local GOPROXY=off go test ./engine` → `go.mod requires go >= 1.26.0 (running go 1.23.2)`
- Android SDK/Gradle 실행 파일을 찾지 못함.
- dl.google.com 및 repo1.maven.org에 대한 네트워크 연결 실패.

위 이유로 이 ZIP을 **실행·빌드 검증을 마친 완성 APK**라고 주장하지 않습니다. GitHub Actions 빌드 후 출력 로그 확인이 필요합니다.

## Docker Compose 로컬 빌드 추가 (2026-11-25)

- [x] `Dockerfile`, `docker-compose.yml`, `.dockerignore` 추가
- [x] 컨테이너 내부 SDK/NDK/Gradle/Go/gomobile 설치 절차 구성
- [x] Go/Gradle 캐시 볼륨과 `dist/` APK 출력 설정
- [x] Bash 문법 및 Compose YAML 구조 정적 확인
- [ ] 실제 `docker compose run --rm --build apk` — 실행 환경에 Docker 실행 파일이 없어 미실행
- [ ] Android APK 컴파일·설치 — 미검증

SDK/Gradle 다운로드 및 Google Play Services, Android 기기 런타임 동작에는 인터넷 연결과 실제 빌드/기기 검증이 필요합니다.

## 추가 변경 및 검증 — TCP/HTTP→HTTPS, 새 규칙 빈칸, 순간 속도

### 구현 내용

- [x] 신규 규칙 추가 시 이름·로컬 포트·대상 IPv4·대상 포트를 빈칸으로 표시
- [x] 모드 기본값은 `TCP 그대로 전달`이며 기존 저장 규칙의 모드가 없으면 TCP로 처리
- [x] 앱 첫 실행의 예시 포워딩 규칙 자동 생성 제거 (기존 저장 규칙은 보존)
- [x] 선택 모드 `HTTP → HTTPS (NPM)` 추가: 내부 WireGuard TCP 경로로 NPM IP:443에 연결, TLS SNI·HTTP Host는 사용자 입력 도메인 사용
- [x] HTTPS 인증서의 서명·호스트명을 검증하며 TLS 검증 우회 금지
- [x] WebDAV 요청 본문 스트리밍, 인증 헤더, `MOVE`/`COPY` Destination 헤더, 리다이렉트 URL 처리
- [x] 업로드/다운로드 통계를 누적량 대신 최근 구간의 전송 속도(B/s, KB/s, MB/s)로 표시 (화면 재진입 시 새 샘플링 시작)
- [x] 이전 GitHub Actions 장애 대응: `go mod tidy`, `mkdir -p app/libs` 단계 포함

### 수행한 테스트

- [x] **HTTP→HTTPS Go 코어 독립 단위 테스트 3개 통과** (Go 1.23.2 / 외부 의존성 없는 임시 테스트 모듈):
  1. WebDAV MOVE, Authorization, 본문 스트리밍, Destination/Location 변환, 전송량 측정
  2. HTTPS 인증서 이름이 잘못되면 502 반환 및 연결 거부
  3. 내부 IP로 Dial하면서도 TLS SNI 및 Host를 `example.com`으로 전송
- [x] `gofmt` 적용
- [x] Compose YAML과 GitHub Actions YAML 구문 분석 통과
- [x] 빌드 Bash 스크립트 `bash -n` 통과
- [x] 신규 규칙 빈칸, 기본 TCP 모드, 초기 예제 규칙 삭제, 워크플로 수정 정적 검사 통과
- [ ] **전체 Go 엔진 `go test ./engine`** 미검증: 현재 환경 Go 1.23.2 (프로젝트 Go 1.26.0 요구), 외부 모듈 다운로드 불가
- [ ] **Android APK 빌드 / 설치** 미검증: Android SDK, Gradle, Docker 부재
- [ ] Android 실기기에서 WGF+유니콘 Pro 공존, HTTPS NPM 접속, Autosync WebDAV 대용량 전송 미검증

**주의:** 독립 단위 테스트의 네트워크 연결에는 테스트용 로컬 TLS 서버가 사용됐습니다. 실제 WireGuard 터널, NPM, Cloudreve에 연결한 것은 아닙니다. 이 소스는 이전 대화에서 생성한 WGF 프로젝트에 대한 변경본이며, 별도로 Claude가 생성한 프로젝트를 받아 직접 수정한 것은 아닙니다.

## 앱 아이콘 추가 점검

- [x] `AndroidManifest.xml`의 런처 아이콘 리소스 참조 확인
- [x] mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi PNG의 크기, RGBA 포맷 및 파일 유효성 확인
- [x] Android 8.0 이상 Adaptive Icon XML 및 배경색 참조 검사
- [x] QR/포워딩/Go 엔진 코드 변경 없음
- [ ] 실제 기기 런처에서 표시 상태 확인 (Android SDK 및 연결된 기기 없음)
- [ ] APK 빌드 성공 확인 (Android SDK/Gradle 미설치)

## APK 파일명 및 붉은색 런처 아이콘 변경 (2026-11-25)

- [x] Docker/직접 빌드 및 GitHub Actions 모두 `dist/WireGuard Forwarder.apk` 사용하도록 연결
- [x] `install` 명령을 사용해 기존 파일이 있으면 새 빌드 성공 후 덮어쓰도록 구성
- [x] 런처 일반/원형/Adaptive 아이콘을 붉은색 계열로 교체하고 PNG 파일 검사
- [x] 관련 스크립트 Bash 문법 및 ZIP 구성 검사
- [ ] 실제 Gradle APK 컴파일 및 설치 미검증 (현 환경에 Android SDK/Gradle 없음)


## 2026-11-25 수정: HTTPS 호스트 검증 및 설정 백업·복원

- GitHub Actions 보고 오류: `TestHTTPSRuleRequiresDomain`에서 `127.0.0.1` 허용 → Go/Java 모두 숫자 IP 형태의 TLS 도메인을 거부하도록 수정.
- GitHub Actions 보고 오류: `TestHTTPBridgeTLSVerification` 502 예상/200 반환 → HTTPS 업스트림 TLS의 인증서 호스트명 검증을 `VerifyConnection`에서 명시하고 미일치 인증서/신뢰되지 않는 인증서 거부 테스트를 강화.
- **검증:** Go 1.23 오프라인 독립 테스트(원본 검증 함수 및 HTTP 브리지 코드 추출) 9개 통과. Android/Go 1.26 통합 테스트는 이 환경에서 재실행 불가.
- **백업:** AES-256-GCM + PBKDF2-HMAC-SHA256(25만 회) 암호화; 비밀번호 필수; QR로 등록한 프로필·암호화된 원본 설정을 복원 가능한 휴대용 파일로 내보냄. 포워딩 규칙 및 옵션 포함.
- **백업 포맷 테스트:** Java 17에서 정상 복호화, 다른 암호 거부, 변조 감지, 반복 암호화 시 서로 다른 결과 확인 통과 (`bash scripts/test-backup-codec.sh`).
- **미검증:** Android SDK/Go 1.26과 앱 전체 컴파일, Galaxy Fold7 실기기에서 백업 UI 및 복원 경로, 실제 TLS 인증서 실서버 연결.
