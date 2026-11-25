# WireGuard Forwarder — Android

원격 WireGuard 서버에 접속하는 사용자 공간(user-space) WireGuard TCP 포트 포워더입니다. 일반 Android VPN(VpnService)을 등록하지 않으므로 다른 Android VPN과 *VPN 슬롯 충돌 없이 공존하도록 설계*했습니다. 실제 VPN 동시 작동 여부와 백그라운드 안정성은 기기에서 테스트해야 합니다.

> **개발 상태 (2026-11-25):** Docker Compose 로컬 빌드 구성을 추가했습니다. 이 실행 환경에는 Docker/Android SDK/Gradle 및 필요한 패키지 다운로드 접근이 없어 **실제 APK 빌드·설치·실기기 테스트는 미완료**입니다. 빌드 성공이나 앱 동작이 보장된 결과물은 아닙니다.

## 앱 아이콘

짙은 적색(#88171A)과 코랄 레드 계열을 조합한 독립적인 WGF 보안 터널 아이콘을 포함했습니다. (외부 서비스의 공식 로고는 사용하지 않습니다.) 일반 및 원형 런처 아이콘과 Android 8.0 이상 Adaptive Icon을 지원합니다. `app/src/main/AndroidManifest.xml`의 `android:icon` 및 `android:roundIcon`에 연결되어 있으며, `app/src/main/res/mipmap-*`의 PNG와 `mipmap-anydpi-v26`의 XML에서 관리합니다. 애플리케이션 네트워크 기능은 변경하지 않았습니다.

## 기능

- `.conf` 파일 가져오기
- **WireGuard QR 코드 카메라 스캔으로 프로필 등록** (전체 `.conf` 내용이 들어있는 표준 QR)
- **WireGuard 설정 내용 직접 입력 및 기존 프로필 수정** (이름 + `.conf` 원문 편집)
- 여러 WireGuard 프로필 저장 및 선택
- Android Keystore AES-256-GCM으로 개인키가 포함된 설정 파일 암호화 저장 (백업 제외)
- `127.x.x.x:<포트>` → `<원격 서버 IPv4>:<포트>` TCP 포워딩 여러 개 (기본 로컬 IP `127.0.0.1`)
- TCP 목적지 연결 테스트, **업로드/다운로드 현재 속도(B/s·KB/s·MB/s)**, 실행 로그
- **규칙별 두 가지 모드:** 기본 TCP 포워딩 또는 `HTTP → HTTPS` 전환
- **암호화 설정 백업 및 복원:** WireGuard 프로필/개인키, 포워딩 규칙, 부팅 시 자동 실행 및 재연결 설정 포함
- Foreground Service, 부팅 시 연결 시도, 기본 네트워크 변경 시 재연결 시도
- 루팅과 `VpnService` 권한 불필요

## 프로필 등록 방법

1. 앱 설치 후 **QR 스캔**, **.conf 파일**, **직접 입력** 중 하나 선택.
2. QR 스캔 시 WireGuard 서버에서 발급한 클라이언트 설정 QR을 카메라로 스캔. QR에는 전체 `[Interface]` / `[Peer]` 설정과 개인키가 포함되어 있어야 합니다.
3. 설정 편집기에 프로필 이름과 WireGuard 원문이 표시됩니다. 값 확인 후 **저장**.
4. 이미 등록한 프로필은 목록에서 선택 → **프로필 수정**. 연결 중에는 먼저 연결 종료.
5. QR 스캐너는 **Google Play 서비스**의 Code Scanner API를 사용합니다. CAMERA 권한을 요구하지 않으나 첫 사용 시 스캐너 구성 요소를 다운로드할 수 있어 인터넷 연결이 필요할 수 있습니다. Google Play 서비스가 없는 기기에서는 `.conf` 파일 또는 직접 입력을 사용하세요.

**보안:** QR에 포함된 `PrivateKey`와 `PresharedKey`를 다른 사람에게 공유하거나 공개 저장소에 올리지 마세요. 앱은 설정의 내용을 실행 로그에 기록하지 않으며, 설정 편집 화면은 스크린샷/화면 녹화를 차단하도록 설계했습니다. 직접 입력한 텍스트는 편집 중 메모리에 존재합니다.

## 설정 백업 및 복원

1. 앱 하단 **설정 백업 및 복원 → 백업 내보내기**를 선택합니다.
2. 백업 암호(8자 이상)를 두 번 입력하고 `.wgfb` 파일 저장 위치를 선택합니다.
3. 복원 시 **백업 복원**에서 파일을 선택하고 암호를 입력한 뒤 **교체 복원**을 누릅니다.
4. 복원 완료 후 연결을 수동으로 재시작합니다.

- **백업 내용:** 모든 WireGuard 프로필의 설정 원문(개인키 포함), 선택된 프로필, TCP/HTTPS 규칙, 자동 실행/재연결 옵션.
- **파일 보호:** AES-256-GCM, PBKDF2-HMAC-SHA256(25만 회), 매번 새로운 salt와 nonce 사용. Android Keystore가 다른 새 기기에서도 암호만 알면 복원할 수 있습니다.
- **주의:** 파일에는 암호화된 개인키가 포함되어 있으므로 암호를 잊으면 복원할 수 없습니다. 강력한 암호를 사용하고 파일을 안전하게 보관하세요.
- **복원 동작:** 연결을 끊은 뒤 사용하며, 복원 시 기존 프로필과 규칙을 **교체**합니다. 암호 불일치, 파일 훼손, 규칙 오류는 저장 전 검증해 기존 설정을 유지합니다.
- 프로필 파일·앱 내부 저장소는 기존처럼 Android Keystore로 암호화합니다. `.wgfb`에는 장치별 Keystore 키를 직접 담지 않습니다.

## 설정 예시 (키는 반드시 본인 값으로 교체)

```ini
[Interface]
PrivateKey = <내 WireGuard 클라이언트 개인키>
Address = 10.10.0.2/24
DNS = 1.1.1.1

[Peer]
PublicKey = <WireGuard 서버 공개키>
PresharedKey = <미사용이면 행 제거>
Endpoint = <WireGuard 서버 주소>:<WireGuard UDP 포트>
AllowedIPs = 192.168.10.10/32
PersistentKeepalive = 25
```

- `AllowedIPs`는 원격 목적지의 IP 대역을 포함해야 합니다. `192.168.10.10/32`는 **해당 서버 IP 하나만** 의미합니다.
- WireGuard 서버의 UDP 포트와 목적지 서비스의 TCP 포트는 **서로 다른 역할**입니다.
- 목적지 서비스는 WireGuard 서버가 라우팅할 수 있는 내부 주소에서 수신 중이어야 합니다. 외부 인터넷에 서비스 포트를 개방할 필요는 없으며, 내부 방화벽의 접근 허용 여부를 확인하세요.

## 포워딩 규칙 설정

신규 설치 시 **포워딩 규칙은 비어 있습니다**. `+ 추가`를 누르면 규칙 이름·로컬 포트·대상 IPv4·대상 포트는 모두 **빈칸**이며, 로컬 IP는 `127.0.0.1`, 모드는 **TCP 그대로 전달**로 선택되어 있습니다. 기존에 저장한 규칙은 로컬 IP가 없으면 `127.0.0.1`로 처리됩니다.

### 모드 1 — TCP 그대로 전달 (기본값)

| 설정 | 값 (예시) |
|---|---|
| 규칙 이름 | 내부 웹 서비스 |
| 로컬 IP | `127.0.0.1` |
| 로컬 포트 | `5212` |
| 대상 IPv4 | `192.168.10.10` |
| 대상 포트 | `5212` |
| 모드 | TCP 그대로 전달 |

접속 예시: `http://127.0.0.1:5212/<서비스 경로>`

이 모드는 HTTP·HTTPS 등 **TCP 데이터를 변경하지 않고** WireGuard로 전달합니다. HTTPS 목적지에 HTTP 요청을 보내면 HTTPS로 자동 변환되지 않습니다.

### 여러 HTTPS 도메인 → 하나의 원격 HTTPS 서버 (TCP 패스스루)

TCP 모드에서 **로컬 IP `127.0.0.1`, 로컬 `443` → 원격 서버 `443`** 규칙 하나만 등록하면, 여러 HTTPS 도메인의 원본 TLS 트래픽을 변경 없이 전송할 수 있습니다. 서로 다른 루프백 IP(예: `127.0.0.1`과 `127.0.0.2`)에는 동일한 포트를 각각 바인딩할 수 있도록 지원합니다. 요청의 SNI는 원격 HTTPS 서버 또는 역방향 프록시가 확인하므로 앱에서 와일드카드 도메인을 별도로 입력할 필요가 없습니다.

| 설정 | 값 (예시) |
|---|---|
| 모드 | `TCP 그대로 전달` |
| 로컬 IP | `127.0.0.1` |
| 로컬 포트 | `443` |
| 대상 IPv4 | `192.168.10.10` |
| 대상 포트 | `443` |

- 외부 DNS에서 `example.com`과 `*.example.com`을 해당 프로필의 로컬 바인딩 IP(예: 홈 `127.0.0.1`, 별도 프로필 `127.0.0.2`)로 해석하도록 **별도 구성**해야 포트 없는 주소가 로컬 WGF로 도착합니다. 공개 DNS는 프로필별로 다른 응답을 줄 수 없으므로, 서로 다른 로컬 IP를 사용하려면 프로필별 Split DNS가 필요합니다. 공개 DNS 변경은 다른 이용자에게도 영향을 줍니다.
- TLS 인증서 및 도메인별 서비스 분기는 원격 HTTPS 서버가 처리합니다. `*.example.com` 인증서가 `example.com` 자체까지 자동 포함하지는 않습니다.
- **중요:** 앱에서는 로컬 443 포트를 입력할 수 있게 했지만 Android 커널의 `ip_unprivileged_port_start` 설정에 따라 실제 바인딩이 `permission denied`로 실패할 수 있습니다. 루팅이나 VPN 권한 없이 우회할 수 있다고 가정하지 마세요. 실패하면 로컬 `8443` 같은 포트를 사용하고 URL에 `:8443`을 붙이는 방식으로만 사용할 수 있습니다.
- 이 규칙은 TCP의 HTTPS를 통과시킵니다. HTTP/3(QUIC, UDP/443)는 해당하지 않습니다. 브라우저가 다른 DNS/IPv6 경로를 선택하는지 확인하세요.

### 모드 2 — HTTP → HTTPS

**연결 경로:** `클라이언트 앱 (HTTP localhost) → WGF (TLS 연결 생성) → WireGuard → 원격 네트워크 → HTTPS 서버/역방향 프록시 (SNI·Host로 호스트 구분) → 대상 서비스`.

| 설정 | 값 (예시) |
|---|---|
| 규칙 이름 | 내부 HTTPS 서비스 |
| 로컬 IP | `127.0.0.1` |
| 로컬 포트 | `5213` |
| 대상 IPv4 | `192.168.10.10` |
| 대상 포트 | `443` |
| 모드 | HTTP → HTTPS |
| HTTPS 인증서 도메인 | `service.example.com` |

접속 예시: `http://127.0.0.1:5213/<서비스 경로>` (로컬 IP를 변경한 경우 해당 IP 사용)

이 모드는 **외부 DNS를 이용하지 않고** 내부 IP `192.168.10.10:443`으로 WireGuard TCP 접속합니다. TLS SNI 및 HTTP Host는 `service.example.com`으로 설정하며, **Android가 신뢰하는 인증기관과 도메인 일치를 검증**합니다. 인증서를 신뢰하지 못하거나 이름이 맞지 않으면 연결을 거부합니다. 원격 HTTPS 서버는 `service.example.com`과 일치하는 유효한 인증서가 있어야 합니다. 다른 도메인을 이용하려면 별도의 로컬 포트에 HTTPS 규칙을 추가하세요.

**안전성 구분:** 클라이언트 앱에서 WGF까지의 로컬 HTTP 구간은 평문이며, WGF부터 원격 HTTPS 서버까지는 TLS + WireGuard입니다. 이후 역방향 프록시가 있다면 내부 서비스와의 암호화 여부는 해당 서버 설정에 따릅니다. 인터넷에 내부 서비스 포트를 직접 공개하지 않아도 되지만 WireGuard 서버 자체는 접근 가능해야 합니다.

**로컬 바인딩 IP:** 규칙마다 `127.0.0.0/8` 범위의 IPv4 루프백 주소를 지정할 수 있습니다. 홈 프로필은 `127.0.0.1`, 별도 프로필은 `127.0.0.2`처럼 서로 다른 주소를 사용하면 동일한 포트를 각각 열 수 있습니다. 외부 LAN IP나 `0.0.0.0` 바인딩은 허용하지 않습니다. 구버전 규칙과 백업에 로컬 IP가 없으면 `127.0.0.1`을 기본값으로 사용합니다.

**실제 운영 주의:** HTTP와 HTTPS 모드는 같은 로컬 IP·포트를 공유할 수 없으므로 엔드포인트가 달라야 합니다. HTTP/WebDAV MOVE/COPY의 `Destination` 헤더 및 HTTPS 응답의 리디렉션 주소는 로컬 주소로 변환합니다. 일부 서버의 쿠키, 절대 URL, 로그인 리디렉션 또는 대용량 전송 호환성은 실기기에서 검증해야 합니다. TLS 인증서 검증을 비활성화하지 않습니다.

### 속도 표시

연결 화면에는 누적 전송량 대신 **현재 업로드/다운로드 속도(B/s, KB/s, MB/s)** 를 표시합니다. 화면이 열려 있는 동안 약 1.5초마다 최근 바이트 증가량을 측정합니다. 화면을 다시 열면 이전 화면 중단 시간의 전송량을 섞지 않고 새 측정 구간을 시작합니다. 연결이 끊기면 수치는 초기화됩니다.

## 장시간 백그라운드 실행과 로그

- 연결을 시작하면 Android `connectedDevice` Foreground Service가 실행되고 상태 알림이 표시됩니다. **화면을 닫거나 다른 앱을 사용해도 서비스는 계속 실행**되도록 구성했습니다. 알림의 **연결 종료** 또는 앱의 연결 종료 버튼으로 중지합니다.
- **실행 설정 → 앱 정보 열기 (배터리 설정)**에서 앱의 배터리 사용을 **제한 없음**으로 조정하면 제조사 절전 정책으로 인한 중단 가능성을 줄일 수 있습니다. Android가 강제로 서비스를 종료하는 상황까지 보장하지는 않습니다.
- **부팅 후 자동 연결** 및 **네트워크 변경 시 재연결**은 각각 켜고 끌 수 있습니다. OS 제한으로 부팅 시 자동 실행이 실패할 수 있습니다.
- 이벤트 및 오류 로그는 `forwarder.log` / `forwarder.old.log` 2개만 사용하고, 각 파일을 최대 **32KiB**로 유지합니다. 총 저장량은 약 **64KiB** 이하여서 장시간 실행해도 계속 증가하지 않습니다. 전송 패킷은 로그로 기록하지 않습니다.

## 연결 테스트

**연결** → 원하는 규칙의 **연결 테스트**로 목적지에 실제 TCP 연결을 시도합니다. HTTPS 모드에서는 인증서/호스트 검증을 포함한 TLS 핸드셰이크까지 확인합니다. 테스트가 성공하면 브라우저에서 `http://127.0.0.1:<규칙 로컬 포트>/`로 접속하세요.

접속 경로는 대상 서비스 설정에서 확인하세요. 연결할 앱이 HTTP localhost 접속을 허용하는지 별도 확인해야 합니다. **로컬 포트 포워딩은 TCP만 해당하며**, 일반 앱의 전체 트래픽을 WireGuard로 강제 전송하지 않습니다.

## 로컬 Docker Compose 빌드 (추천)

**호스트 PC에 설치할 것은 Docker Desktop(Windows) 또는 Docker Engine + Compose Plugin(Linux)뿐입니다.** Java, Go, Android SDK/NDK, Gradle, gomobile은 모두 Docker 이미지 안에서 설치합니다. 처음 실행할 때 공식 저장소에서 개발 도구와 의존성을 다운로드하므로 인터넷 연결과 수 GB 이상의 저장 공간이 필요합니다.

프로젝트 ZIP을 해제한 폴더에서 다음 한 줄을 실행하세요.

```bash
docker compose run --rm --build apk
```

정상 빌드 후 생성 파일:

```text
dist/WireGuard Forwarder.apk
```

기존 `dist/WireGuard Forwarder.apk`가 있으면 정상 빌드가 완료된 뒤 같은 이름으로 덮어씁니다. GitHub Actions도 동일한 파일명으로 내보냅니다. Windows PowerShell에서도 같은 명령어를 실행할 수 있습니다. Android 기기로 APK를 옮겨 직접 설치하면 됩니다. `arm64-v8a` 전용 debug APK이므로 arm64 Android 기기를 대상으로 하며 다른 CPU 구조를 가진 기기는 지원하지 않습니다. 정식 스토어 배포용 서명은 포함하지 않았습니다.

- `Dockerfile`: Go 1.26, Java 17, Gradle 8.9, Android SDK 35, NDK 27.2, gomobile 설치
- `docker-compose.yml`: 소스 폴더를 마운트하고 Go/Gradle 빌드 캐시 보관
- `scripts/build-android.sh`: Go 엔진 테스트 → Android AAR → debug APK → `dist/WireGuard Forwarder.apk` 내보내기
- `scripts/export-apk.sh`: 빌드 성공 후 `dist/WireGuard Forwarder.apk`로 복사·덮어쓰기
- `scripts/docker-build.sh`: 빌드 후 APK SHA-256 출력

처음 빌드는 다운로드 때문에 오래 걸릴 수 있습니다. 이후에는 이미지와 다운로드 캐시를 재사용합니다. Go 의존성 설치 시 `go.mod` 및 `go.sum`이 갱신될 수 있습니다. 빌드 실패 시 아래 로그를 그대로 확인하세요.

```bash
docker compose run --rm --build apk 2>&1 | tee build.log
```

> **주의:** Docker Compose는 **개발 PC에서 Android APK를 빌드하는 도구**이며, 실행 중인 서비스 컨테이너가 아닙니다. 앱 자체는 휴대폰에 설치해 실행합니다. `docker compose`를 실행한다고 휴대폰의 WireGuard 연결이 설정되지는 않습니다.

## GitHub Actions 빌드 (대안)

1. 이 ZIP의 프로젝트 **내용 전체**를 GitHub 저장소의 루트에 업로드하세요. (공개 저장소에 개인키나 실제 `.conf`를 올리지 마세요.)
2. 저장소 → **Actions** → **Build Android APK** → **Run workflow**. 기본 브랜치가 `main`/`master`이면 push 시에도 빌드됩니다.
3. 성공하면 해당 워크플로 실행 화면의 **Artifacts**에서 `WireGuardForwarder-APK`를 내려받습니다. 그 안의 `WireGuard Forwarder.apk`가 설치용 파일입니다.
4. arm64 Android 기기에 APK를 설치합니다. 개발용 debug 서명이므로 공식 스토어 배포용이 아닙니다.

워크플로는 Go 1.26, Android SDK 35, NDK 27.2, Gradle 8.9, gomobile을 설치하고 엔진을 `arm64` AAR로 만든 뒤 Android APK를 빌드하도록 설정되어 있습니다. **실제 CI 실행 성공 여부는 검증하지 못했습니다.** Go 엔진 테스트 후 암호화 백업 포맷의 Java 회귀 테스트도 실행합니다.

로컬 빌드 시 위 도구를 설치하고 `ANDROID_HOME`, `ANDROID_NDK_HOME`을 지정한 후 `bash scripts/build-android.sh`를 실행하세요.

## 알려진 제약 및 미검증 사항

- APK가 이 환경에서 빌드되지 않아 Android 컴파일 및 설치 결과가 검증되지 않았습니다.
- 실제 WireGuard 서버 연결, 다른 Android VPN과의 공존, 화면 OFF 상태의 데이터 전송, 네트워크 자동 복구 및 클라이언트 앱 호환성은 실기기 검증이 필요합니다.
- QR 스캔은 카메라 방식이며 **갤러리에 저장된 QR 이미지 선택**은 구현하지 않았습니다.
- QR 스캔은 Google Play 서비스가 필요한 방법입니다. 오프라인 환경에서는 `.conf` 또는 직접 입력을 사용하세요.
- TCP 프록시는 앱 내부의 로컬 포트에서 수신한 TCP만 터널로 전달합니다. UDP 포워딩·TUN 전송은 제공하지 않습니다.
- 포트 매핑 규칙은 초기값으로 추가하지 않았습니다. 실제 목적지 IP와 포트를 확인한 뒤 규칙을 추가하세요.

## 의존성

- [Wireproxy](https://github.com/windtf/wireproxy) — WireGuard 사용자 공간 엔진
- [gomobile](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) — Go → Android AAR
- [Google Code Scanner](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner) — QR 카메라 스캐너 (`play-services-code-scanner:16.1.0`)

프로젝트의 Gradle / Go 모듈 의존성은 빌드 시 다운로드됩니다. 비밀키가 포함된 QR 이미지는 빌드 입력이나 ZIP에 포함하지 않았습니다.
