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
