# One-shot Android APK build environment. No Android tooling is needed on the host.
FROM golang:1.26.0-bookworm

ARG GRADLE_VERSION=8.9
ARG CMDLINE_TOOLS_BUILD=15859902

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_NDK_HOME=/opt/android-sdk/ndk/27.2.12479018 \
    GRADLE_HOME=/opt/gradle/gradle-8.9 \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/gradle/gradle-8.9/bin:/go/bin:${PATH}

RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jdk-headless ca-certificates curl unzip git \
    && rm -rf /var/lib/apt/lists/*

# SHA-256 published on https://developer.android.com/studio#command-line-tools-only
RUN curl -fL --retry 3 \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip" \
      -o /tmp/android-commandline.zip \
    && echo "4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  /tmp/android-commandline.zip" | sha256sum -c - \
    && mkdir -p "${ANDROID_HOME}/cmdline-tools" /tmp/android-tools \
    && unzip -q /tmp/android-commandline.zip -d /tmp/android-tools \
    && mv /tmp/android-tools/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm -rf /tmp/android-commandline.zip /tmp/android-tools

# The SDK and NDK are installed inside the image, not on the user's computer.
RUN (yes || true) | sdkmanager --sdk_root="${ANDROID_HOME}" --licenses >/dev/null \
    && sdkmanager --sdk_root="${ANDROID_HOME}" \
       'platforms;android-35' 'build-tools;35.0.0' 'ndk;27.2.12479018'

RUN curl -fL --retry 3 \
      "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
      -o /tmp/gradle.zip \
    && mkdir -p /opt/gradle \
    && unzip -q /tmp/gradle.zip -d /opt/gradle \
    && rm /tmp/gradle.zip

RUN go install golang.org/x/mobile/cmd/gomobile@latest \
    && go install golang.org/x/mobile/cmd/gobind@latest

WORKDIR /workspace
CMD ["bash", "scripts/docker-build.sh"]
