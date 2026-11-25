# WireGuard Forwarder — Android

집의 ipTIME WireGuard 서버로 접속하는 사용자 공간(user-space) WireGuard TCP 포트 포워더입니다. 일반 Android VPN(VpnService)을 등록하지 않으므로 유니콘 Pro 같은 기존 Android VPN과 *VPN 슬롯 충돌 없이 공존하도록 설계*했습니다. 실제 VPN 동시 작동 여부와 백그라운드 안정성은 기기에서 테스트해야 합니다.

> **개발 상태 (2026-11-25):** Docker Compose 로컬 빌드 구성을 추가했습니다. 이 실행 환경에는 Docker/Android SDK/Gradle 및 필요한 패키지 다운로드 접근이 없어 **실제 APK 빌드·설치·실기기 테스트는 미완료**입니다. 빌드 성공이나 앱 동작이 보장된 결과물은 아닙니다.

## 기능

- `.conf` 파일 가져오기
- **WireGuard QR 코드 카메라 스캔으로 프로필 등록** (전체 `.conf` 내용이 들어있는 표준 QR)
- **WireGuard 설정 내용 직접 입력 및 기존 프로필 수정** (이름 + `.conf` 원문 편집)
- 여러 WireGuard 프로필 저장 및 선택
- Android Keystore AES-256-GCM으로 개인키가 포함된 설정 파일 암호화 저장 (백업 제외)
- `127.0.0.1:<포트>` → `<집 PC IPv4>:<포트>` TCP 포워딩 여러 개
- TCP 목적지 연결 테스트, 송수신 통계, 실행 로그
- Foreground Service, 부팅 시 연결 시도, 기본 네트워크 변경 시 재연결 시도
- 루팅과 `VpnService` 권한 불필요

## 프로필 등록 방법

1. 앱 설치 후 **QR 스캔**, **.conf 파일**, **직접 입력** 중 하나 선택.
2. QR 스캔 시 ipTIME에 표시되는 WireGuard 클라이언트 QR을 카메라로 스캔. QR에는 전체 `[Interface]` / `[Peer]` 설정과 개인키가 포함되어 있어야 합니다.
3. 설정 편집기에 프로필 이름과 WireGuard 원문이 표시됩니다. 값 확인 후 **저장**.
4. 이미 등록한 프로필은 목록에서 선택 → **프로필 수정**. 연결 중에는 먼저 연결 종료.
5. QR 스캐너는 **Google Play 서비스**의 Code Scanner API를 사용합니다. CAMERA 권한을 요구하지 않으나 첫 사용 시 스캐너 구성 요소를 다운로드할 수 있어 인터넷 연결이 필요할 수 있습니다. Google Play 서비스가 없는 기기에서는 `.conf` 파일 또는 직접 입력을 사용하세요.

**보안:** QR에 포함된 `PrivateKey`와 `PresharedKey`를 다른 사람에게 공유하거나 공개 저장소에 올리지 마세요. 앱은 설정의 내용을 실행 로그에 기록하지 않으며, 설정 편집 화면은 스크린샷/화면 녹화를 차단하도록 설계했습니다. 직접 입력한 텍스트는 편집 중 메모리에 존재합니다.

## 설정 예시 (키는 반드시 본인 값으로 교체)

```ini
[Interface]
PrivateKey = <내 WireGuard 클라이언트 개인키>
Address = 10.107.221.5/24
DNS = 168.126.63.1

[Peer]
PublicKey = <ipTIME WireGuard 서버 공개키>
PresharedKey = <미사용이면 행 제거>
Endpoint = <공유기 DDNS 주소>:<WireGuard UDP 포트>
AllowedIPs = 192.168.0.230/32
PersistentKeepalive = 25
```

- `AllowedIPs`는 목적지 PC의 IP 대역을 포함해야 합니다. `192.168.0.230/32`는 **해당 PC 한 대만** 의미합니다.
- WireGuard UDP 포트와 Cloudreve의 TCP 5212 포트는 **서로 다른 역할**입니다.
- ipTIME 공유기에서 WireGuard를 서비스한다면 Cloudreve 5212를 인터넷으로 포트 포워딩할 필요는 없습니다. 집 PC의 Docker Compose에 `5212:5212`를 게시하고, 로컬 방화벽에서 필요한 접근만 허용하세요.

## TCP 포워딩 사용

기본 예시 규칙:

| 이름 | Android 로컬 주소 | 집 PC 대상 |
|---|---|---|
| Cloudreve | `127.0.0.1:5212` | `192.168.0.230:5212` |
| Immich | `127.0.0.1:2283` | `192.168.0.230:2283` |

**연결** → 원하는 규칙의 **연결 테스트**가 성공하면 Chrome에서 `http://127.0.0.1:5212/`로 접속을 시험하세요.

Autosync WebDAV 서버 주소는 `http://127.0.0.1:5212/<Cloudreve가 지정한 실제 WebDAV 경로>`로 입력하되, **Cloudreve에서 WebDAV를 사용하도록 설정**해야 합니다. `/dav` 경로는 보편적인 기본값이라고 보장할 수 없으므로 추측해서 입력하지 마세요. HTTPS-only 구성은 인증서 호스트명 검증 등의 추가 고려사항이 있습니다. 또한 Autosync가 localhost URL 및 평문 HTTP를 허용하는지 기기에서 확인해야 합니다.

**중요:** 여기서 포워딩은 TCP뿐입니다. 일반 앱의 전체 트래픽을 WireGuard로 강제 전송하는 VPN이 아니며, Android VPN 권한을 사용하지 않습니다.

## 로컬 Docker Compose 빌드 (추천)

**호스트 PC에 설치할 것은 Docker Desktop(Windows) 또는 Docker Engine + Compose Plugin(Linux)뿐입니다.** Java, Go, Android SDK/NDK, Gradle, gomobile은 모두 Docker 이미지 안에서 설치합니다. 처음 실행할 때 공식 저장소에서 개발 도구와 의존성을 다운로드하므로 인터넷 연결과 수 GB 이상의 저장 공간이 필요합니다.

프로젝트 ZIP을 해제한 폴더에서 다음 한 줄을 실행하세요.

```bash
docker compose run --rm --build apk
```

정상 빌드 후 생성 파일:

```text
dist/WireGuardForwarder-debug.apk
```

Windows PowerShell에서도 같은 명령어를 실행할 수 있습니다. Android 기기로 APK를 옮겨 직접 설치하면 됩니다. `arm64-v8a` 전용 debug APK이므로 갤럭시 Z Fold7에 맞지만 다른 CPU 구조를 가진 기기는 지원하지 않습니다. 정식 스토어 배포용 서명은 포함하지 않았습니다.

- `Dockerfile`: Go 1.26, Java 17, Gradle 8.9, Android SDK 35, NDK 27.2, gomobile 설치
- `docker-compose.yml`: 소스 폴더를 마운트하고 Go/Gradle 빌드 캐시 보관
- `scripts/build-android.sh`: Go 엔진 테스트 → Android AAR → debug APK
- `scripts/docker-build.sh`: 생성된 APK를 `dist/`에 복사하고 SHA-256 출력

처음 빌드는 다운로드 때문에 오래 걸릴 수 있습니다. 이후에는 이미지와 다운로드 캐시를 재사용합니다. Go 의존성 설치 시 `go.mod` 및 `go.sum`이 갱신될 수 있습니다. 빌드 실패 시 아래 로그를 그대로 확인하세요.

```bash
docker compose run --rm --build apk 2>&1 | tee build.log
```

> **주의:** Docker Compose는 **집 PC에서 Android APK를 빌드하는 도구**이며, 실행 중인 서비스 컨테이너가 아닙니다. 앱 자체는 휴대폰에 설치해 실행합니다. `docker compose`를 실행한다고 휴대폰의 WireGuard 연결이 설정되지는 않습니다.

## GitHub Actions 빌드 (대안)

1. 이 ZIP의 프로젝트 **내용 전체**를 GitHub 저장소의 루트에 업로드하세요. (공개 저장소에 개인키나 실제 `.conf`를 올리지 마세요.)
2. 저장소 → **Actions** → **Build Android APK** → **Run workflow**. 기본 브랜치가 `main`/`master`이면 push 시에도 빌드됩니다.
3. 성공하면 해당 워크플로 실행 화면의 **Artifacts**에서 `WireGuardForwarder-debug-APK`를 내려받습니다. 그 안의 `app-debug.apk`가 설치용 파일입니다.
4. Galaxy Z Fold7에서 APK를 설치합니다. 개발용 debug 서명이므로 공식 스토어 배포용이 아닙니다.

워크플로는 Go 1.26, Android SDK 35, NDK 27.2, Gradle 8.9, gomobile을 설치하고 엔진을 `arm64` AAR로 만든 뒤 Android APK를 빌드하도록 설정되어 있습니다. **실제 CI 실행 성공 여부는 검증하지 못했습니다.**

로컬 빌드 시 위 도구를 설치하고 `ANDROID_HOME`, `ANDROID_NDK_HOME`을 지정한 후 `bash scripts/build-android.sh`를 실행하세요.

## 알려진 제약 및 미검증 사항

- APK가 이 환경에서 빌드되지 않아 Android 컴파일 및 설치 결과가 검증되지 않았습니다.
- WireGuard ipTIME 연결, 유니콘 Pro와의 공존, 화면 OFF 상태 동기화, 네트워크 자동 복구, 실제 AutoSync 연동은 실기기 검증이 필요합니다.
- QR 스캔은 카메라 방식이며 **갤러리에 저장된 QR 이미지 선택**은 구현하지 않았습니다.
- QR 스캔은 Google Play 서비스가 필요한 방법입니다. 오프라인 환경에서는 `.conf` 또는 직접 입력을 사용하세요.
- TCP 프록시는 앱 내부의 로컬 포트에서 수신한 TCP만 터널로 전달합니다. UDP 포워딩·TUN 전송은 제공하지 않습니다.
- GitLab·OriginVault 포트 매핑은 초기값으로 추가하지 않았습니다. 실제 Docker 호스트 포트를 확인한 뒤 규칙을 추가하세요.

## 의존성

- [Wireproxy](https://github.com/windtf/wireproxy) — WireGuard 사용자 공간 엔진
- [gomobile](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) — Go → Android AAR
- [Google Code Scanner](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner) — QR 카메라 스캐너 (`play-services-code-scanner:16.1.0`)

프로젝트의 Gradle / Go 모듈 의존성은 빌드 시 다운로드됩니다. 비밀키가 포함된 QR 이미지는 빌드 입력이나 ZIP에 포함하지 않았습니다.
