#!/usr/bin/env bash
set -euo pipefail

# LifeLink's Robolectric tests target API 28 (Android 9 / P). Robolectric 4.14.1
# resolves this preinstrumented framework outside Gradle's normal dependency graph.
# Keep the coordinate and digest together so CI never silently accepts a different
# runtime JAR after a Maven mirror or artifact is changed.
readonly ARTIFACT_VERSION="9-robolectric-4913185-2-i7"
readonly ARTIFACT_NAME="android-all-instrumented-${ARTIFACT_VERSION}.jar"
readonly ARTIFACT_URL="https://repo.maven.apache.org/maven2/org/robolectric/android-all-instrumented/${ARTIFACT_VERSION}/${ARTIFACT_NAME}"
readonly ARTIFACT_SHA256="dbb1c88987b54cef3132695a1b4d2f4851ba06286a6fa7d15772f98fd1f0cdf7"
readonly OUTPUT_DIR="${ROBOLECTRIC_DEPENDENCY_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../LifeLinkAndroid" && pwd)/robolectric-jars/preinstrumented}"
readonly OUTPUT_FILE="${OUTPUT_DIR}/${ARTIFACT_NAME}"
readonly TEMP_FILE="${OUTPUT_FILE}.part"

mkdir -p "$OUTPUT_DIR"
trap 'rm -f "$TEMP_FILE"' EXIT

if [[ -f "$OUTPUT_FILE" ]]; then
    printf '%s  %s\n' "$ARTIFACT_SHA256" "$OUTPUT_FILE" | sha256sum --check --status && {
        echo "Robolectric runtime is already present and verified: $OUTPUT_FILE"
        exit 0
    }
    echo "Existing Robolectric runtime failed checksum verification; replacing it." >&2
    rm -f "$OUTPUT_FILE"
fi

echo "Downloading pinned Robolectric runtime: $ARTIFACT_NAME"
if ! curl --fail --location --silent --show-error --retry 5 --retry-delay 2 --retry-connrefused \
    --connect-timeout 15 --max-time 180 -o "$TEMP_FILE" "$ARTIFACT_URL"; then
    echo "ERROR: Could not download $ARTIFACT_URL" >&2
    echo "Diagnostics: $(curl --version | head -1)" >&2
    exit 1
fi

printf '%s  %s\n' "$ARTIFACT_SHA256" "$TEMP_FILE" | sha256sum --check
mv "$TEMP_FILE" "$OUTPUT_FILE"
echo "Verified Robolectric runtime: $OUTPUT_FILE"
