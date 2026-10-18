FROM eclipse-temurin:21-jdk-noble

ARG TARGETARCH
ARG ANDROID_COMMAND_LINE_TOOLS=15859902
ARG ANDROID_COMMAND_LINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ARG ANDROID_API=36
ARG ANDROID_BUILD_TOOLS=35.0.0
ARG BUILDER_UID=1000
ARG BUILDER_GID=1000

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    HOME=/home/builder \
    GRADLE_USER_HOME=/home/builder/.gradle
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}:${PATH}"

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

RUN test "${TARGETARCH:-$(dpkg --print-architecture)}" = amd64 \
    && apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl git unzip \
    && rm -rf /var/lib/apt/lists/*

# Verify Google's archive before extracting or executing its contents.
RUN curl --fail --location --retry 3 \
        "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_COMMAND_LINE_TOOLS}_latest.zip" \
        --output /tmp/android-tools.zip \
    && echo "${ANDROID_COMMAND_LINE_TOOLS_SHA256}  /tmp/android-tools.zip" | sha256sum --check - \
    && mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && unzip -q /tmp/android-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
    && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm /tmp/android-tools.zip

# Ignore only yes's expected SIGPIPE; retain sdkmanager's exit status.
# Building this image accepts the Android SDK licenses.
RUN (yes || true) | sdkmanager --sdk_root="${ANDROID_HOME}" --licenses >/dev/null
RUN sdkmanager --sdk_root="${ANDROID_HOME}" --install \
        "platform-tools" "platforms;android-${ANDROID_API}" "build-tools;${ANDROID_BUILD_TOOLS}" \
    && rm -rf /root/.android /root/.cache

# Ubuntu's base image may already reserve UID/GID 1000 for its ubuntu user.
RUN if id ubuntu >/dev/null 2>&1; then userdel -r ubuntu; fi \
    && groupadd --gid "${BUILDER_GID}" builder \
    && useradd --uid "${BUILDER_UID}" --gid "${BUILDER_GID}" --create-home builder \
    && mkdir -p /workspace "${GRADLE_USER_HOME}" /home/builder/.android \
    && chown -R builder:builder /workspace /home/builder

USER builder
WORKDIR /workspace
# Verify platform-tools can run as the same user used for builds and USB work.
RUN adb version
CMD ["bash"]
