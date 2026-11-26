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
- [ ] arm64 Android 기기 설치 및 기존 VPN과 동시 작동 — **미검증**
- [ ] WireGuard 서버 실제 핸드셰이크 / 목적지 TCP 접속 — **미검증**
- [ ] 클라이언트 앱 실제 파일 전송 — **미검증**
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
- [x] 선택 모드 `HTTP → HTTPS` 추가: 내부 WireGuard TCP 경로로 원격 서버 IP:443에 연결, TLS SNI·HTTP Host는 사용자 입력 도메인 사용
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
- [ ] Android 실기기에서 다른 VPN과 WGF 공존, 원격 HTTPS 접속, 대용량 파일 전송 미검증

**주의:** 독립 단위 테스트의 네트워크 연결에는 테스트용 로컬 TLS 서버가 사용됐습니다. 실제 WireGuard 터널과 원격 서버에 연결한 것은 아닙니다. 이 소스는 이전 대화에서 생성한 WGF 프로젝트에 대한 변경본이며, 별도로 Claude가 생성한 프로젝트를 받아 직접 수정한 것은 아닙니다.

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

## 2026-11-25 장시간 실행 및 범용 안내 개선

- [x] `LogStore`가 이벤트별 개별 파일을 만들지 않고 **2×32KiB** 로그만 유지하도록 변경, 지나치게 긴 오류 문구 제한
- [x] Foreground Service의 `stopWithTask=false` 명시: 앱 작업을 닫아도 연결 서비스 유지
- [x] 배터리 제한 설정으로 이동하는 버튼 및 운영 안내 추가 (OS 강제 종료 방지는 보장 불가)
- [x] 앱의 특정 VPN/동기화 도구/장비/서버 제품 이름과 개인 환경 예시 제거, 범용 작동 방식 안내로 교체
- [x] `LogStoreTest`: 6,000건 기록 후 로그 상한, 최신 기록, 장문 입력 테스트
- [ ] Android APK 컴파일 및 실기기 장시간 동작 시험은 현 환경에서 미실행

## 2026-11-25 변경: 로컬 HTTPS TCP 패스스루 포트 443

- [x] 기존 TCP 모드 변경 없이 `127.0.0.1:443` 규칙을 설정할 수 있도록 앱 입력·백업 복원·Go 엔진 검증을 함께 수정
- [x] 기존 포트 80 제한 및 0/65536 범위 검증 유지
- [x] TCP 수신이 TLS 데이터를 수정하지 않고 전달한다는 안내 추가; 도메인별 별도 규칙/도메인 분석 기능 불필요
- [ ] 갤럭시 기기에서 로컬 TCP/443 바인딩 가능 여부 미검증 (권한 거부 가능)
- [ ] GitHub Actions에서 Go 1.26 / Android APK 전체 빌드 및 설치 미검증


## 2026-11-25 변경: 규칙별 로컬 바인딩 IP

### 구현

- [x] 포워딩 규칙에 `localAddress` 필드 추가
- [x] 신규 규칙 기본값은 `127.0.0.1`
- [x] 기존 규칙 및 구버전 백업에 필드가 없으면 `127.0.0.1`로 처리
- [x] `127.0.0.0/8` IPv4 루프백 주소만 허용하고 LAN IP, `0.0.0.0`, IPv6는 거부
- [x] 중복 검증을 포트 단독에서 로컬 IP+포트 조합으로 변경
- [x] Go 엔진 리스너와 연결 테스트가 선택된 로컬 IP를 사용
- [x] HTTP→HTTPS 모드의 WebDAV Destination/Location 변환도 해당 로컬 IP 사용
- [x] 백업·복원에 로컬 IP 포함 및 구버전 데이터 호환
- [x] README 및 앱 작동 방식 안내 갱신

### 검증

- [x] Go 소스 `gofmt` 검사
- [x] 호스트 Linux에서 `127.0.0.1:<동일 포트>`와 `127.0.0.2:<동일 포트>` 동시 바인딩 테스트 통과
- [ ] 프로젝트 Go 엔진 전체 테스트 — 미실행: Go 1.26.0 다운로드가 네트워크 정책으로 차단됨 (환경 Go 1.23.2)
- [ ] Android APK 컴파일 — 미실행: Docker/Android SDK/Gradle 없음
- [ ] 갤럭시 폴드7에서 두 프로필 WGF의 동일 포트 동시 바인딩 — 미검증
- [ ] 프로필별 DNS가 각각 `127.0.0.1` 및 `127.0.0.2`를 반환하도록 Split DNS 설정 — 미구현/미검증

## 업데이트 서명(추가 검증 항목)
- 최신 소스에서 고정 서명키 GitHub Secrets 필요 여부: 정적 확인
- GitHub Actions 실행 번호 기반 versionCode: 정적 확인
- APK 실제 서명·재설치 없는 업데이트: **미검증** (동일 키로 2회 빌드 및 단말에서 adb install -r 확인 필요)

## 여러 프로필 동시 연결 · 프로필별 포트 포워딩 · `[Peer]` 오류 수정 (2026-11-26)

### 오류 원인 확인

- [x] `Invalid [Peer] settings, keys, or endpoint`는 `wireproxy.ParsePeers` 실패 시 표시. 이 함수에서 실행 중에 결과가 바뀔 수 있는 것은 `Endpoint` 도메인 DNS 조회(`net.ResolveIPAddr`)뿐이며, 키·AllowedIPs·Keepalive 검사는 같은 설정에 대해 항상 같은 결과
- [x] 이전 `ForwardService`는 네트워크 변경 2초 후 `Engine.stop()` → `Engine.start()`로 설정을 다시 파싱. DNS가 준비되지 않으면 위 메시지로 실패하고 `desiredRunning=false`로 연결을 영구 종료 → 수동 재연결 시에는 정상 (사용자 증상과 일치)

### 구현 내용

- [x] Go 엔진: 프로필 ID별 터널 맵 (`StartTunnel` / `StopTunnel` / `StopAll` / `IsRunning` / `Reconnect` / `ProbeTunnel` / `Stats` / `Retryable`). 프로필마다 별도 WireGuard 장치·UDP 소켓·netstack
- [x] `Endpoint` DNS 조회를 `ParsePeers` 전에 분리, 실패 시 재시도 가능한 별도 오류(`endpoint DNS lookup failed for <host>`)
- [x] 실행 중 프로필 재시작 시 새 설정의 DNS 조회가 끝난 뒤에만 기존 터널 교체 (DNS 실패 시 기존 터널 유지)
- [x] 네트워크 변경 시 터널 재생성 대신 `Reconnect`: 도메인 재조회 → 주소 변경 시 UAPI `update_only` 갱신 → `BindUpdate` → keepalive. 기존 TCP 연결 유지
- [x] 데이터 전송 중 핸드셰이크 135초 이상 실패 시 해당 피어의 Endpoint 도메인 자동 재조회 (유휴 시 DNS 조회 없음)
- [x] 다른 실행 중 프로필과 로컬 IP:포트 또는 `ListenPort`가 겹치면 그 프로필 이름을 포함한 오류
- [x] `Stats`에 프로필별 마지막 핸드셰이크 시각·서버 주소 추가 (UAPI 덤프의 개인키는 파싱에서 제외)
- [x] Android: 규칙에 `profileId`, 연결 유지 대상 `activeProfiles`, 프로필별 오류. 기존 데이터 자동 이전 (schema 2)
- [x] `ForwardService`: 프로필별 순차 작업 큐(프로필끼리는 병렬), DNS 실패 시 5초~5분 백오프 재시도, 네트워크 연결 시 즉시 재시도, 알림에 연결된 프로필 목록
- [x] UI: 프로필 카드(연결/종료, 상태·핸드셰이크·속도, 수정/삭제, 포트 추가, 프로필별 규칙과 연결 테스트), 모두 연결 / 모두 연결 종료
- [x] 백업 버전 2(규칙별 소속 프로필), 버전 1 백업 복원 호환

### 수행한 테스트

테스트 코드는 저장소 정책(f9b2091에서 테스트 코드 제거)에 따라 커밋하지 않고 임시 작업 폴더에서만 실행했습니다.

- [x] **Go 엔진 테스트 7개 통과 (`go test -race`, Go 1.26.0, 실제 wireproxy/wireguard-go 의존성)** — 테스트 안에 사용자 공간 WireGuard 서버를 띄워 실제 핸드셰이크와 TCP 전달 확인:
  1. 프로필 2개 동시 연결, 두 프로필의 `Address`가 같아도(10.9.0.2) 각각 자기 서버로 전달, 한 프로필 종료가 다른 프로필에 영향 없음
  2. DNS 실패 → 재시도 가능 오류(호스트명 포함, `invalid [Peer]` 아님), DNS 실패 중 재시작·`Reconnect` 시도에도 기존 터널과 진행 중 TCP 연결 유지
  3. 키 형식 오류·Endpoint 포트 누락·섹션 누락은 재시도 불가 오류, 오류 문자열에 개인키 미포함
  4. 다른 프로필과 로컬 포트 충돌 시 상대 프로필 이름 포함 오류, 다른 루프백 주소(127.0.0.2)의 같은 포트는 허용
  5. `Reconnect` 후 진행 중이던 TCP 연결 유지, 바뀐 서버 주소(DNS 응답 변경)로 Endpoint 갱신
  6. Endpoint 선택(IPv4 우선, 기존 주소 유지, IPv4-mapped 해제), 잘못된 Endpoint 형식 거부
  7. (타이머 단축 사본) 유휴 터널은 DNS 조회 없음, 서버가 사라진 뒤 전송 시도 시 Endpoint 재조회
- [x] `go vet`, `gofmt` 통과. CI와 같은 `go get -tool gobind` + `go mod tidy` 이후 의존성 버전에서도 통과
- [x] `gobind -lang=java`로 실제 `engine.Engine` Java 바인딩 생성 성공 (gomobile 바인딩 가능한 API 확인)
- [x] **Java 전체 소스 컴파일 성공** (`javac --release 17 -Xlint:all`, Android 15 프레임워크 jar(Robolectric android-all) + 생성된 Engine 바인딩, 앱 코드 경고 0개). ML Kit QR 스캐너 클래스는 Google Maven 접근 불가로 컴파일용 스텁 사용
- [x] **`ProfileStore` JVM 검증 22개 통과** (메모리 SharedPreferences, AndroidKeyStore 대체 프로바이더): 기존 데이터 이전(규칙 → 선택 프로필, 연결 상태 유지, 1회만 실행), 소속 없는 규칙의 새 프로필 편입, 프로필별 암호화 설정, 여러 프로필 동시 활성, 백업 v2 왕복, v1 백업 복원, 프로필 간 같은 포트 허용/한 프로필 내 중복 거부, 프로필 삭제 시 규칙·상태 정리
- [ ] `gomobile bind` AAR / APK 빌드 — 미실행 (Android SDK/NDK 다운로드 호스트 접근 불가)
- [ ] 실기기: 여러 프로필 동시 연결, 와이파이 ↔ LTE 전환 시 연결 유지, 장시간 실행 후 오류 재발 여부 — **미검증**
