#!/usr/bin/env bash
set -euo pipefail

# Robolectric 4.14.1 initializes its default API 35 SDK before applying the
# tests' explicit API 28 configuration. Keep both framework JARs pinned here.
readonly OUTPUT_DIR="${ROBOLECTRIC_DEPENDENCY_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../LifeLinkAndroid" && pwd)/robolectric-jars/preinstrumented}"
readonly MAVEN_BASE="https://repo.maven.apache.org/maven2/org/robolectric/android-all-instrumented"

readonly SDK28_RUNTIME_VERSION="9-robolectric-4913185-2-i7"
readonly SDK28_RUNTIME_SHA256="dbb1c88987b54cef3132695a1b4d2f4851ba06286a6fa7d15772f98fd1f0cdf7"
readonly SDK35_RUNTIME_VERSION="15-robolectric-12650502-i7"
readonly SDK35_RUNTIME_SHA256="33c2d7cc4186d4605269d5d411fa1818972c5b30a9f70bfbbd3d430b076af52f"
readonly SDK28_ARTIFACT_NAME="android-all-instrumented-${SDK28_RUNTIME_VERSION}.jar"
readonly SDK35_ARTIFACT_NAME="android-all-instrumented-${SDK35_RUNTIME_VERSION}.jar"

# Clean partial downloads on any exit. A RETURN trap inside prefetch() is
# inherited by later calls and makes cleanup depend on function-local scope.
declare -a TEMP_FILES=()
trap 'rm -f "${TEMP_FILES[@]}"' EXIT

mkdir -p "$OUTPUT_DIR"

prefetch() {
    local version="$1"
    local expected_sha256="$2"
    local artifact_name="android-all-instrumented-${version}.jar"
    local output_file="${OUTPUT_DIR}/${artifact_name}"
    local temp_file="${output_file}.part"
    local artifact_url="${MAVEN_BASE}/${version}/${artifact_name}"

    if [[ -f "$output_file" ]] && printf '%s  %s\n' "$expected_sha256" "$output_file" | sha256sum --check --status; then
        echo "Robolectric runtime is already present and verified: $output_file"
        return 0
    fi

    if [[ -f "$output_file" ]]; then
        echo "Existing Robolectric runtime failed checksum verification; replacing it: $output_file" >&2
        rm -f "$output_file"
    fi

    TEMP_FILES+=("$temp_file")
    echo "Downloading pinned Robolectric runtime: $artifact_name"
    if ! curl --fail --location --silent --show-error --retry 5 --retry-delay 2 --retry-connrefused \
        --connect-timeout 15 --max-time 300 -o "$temp_file" "$artifact_url"; then
        echo "ERROR: Could not download $artifact_url" >&2
        echo "Diagnostics: $(curl --version | head -1)" >&2
        return 1
    fi

    printf '%s  %s\n' "$expected_sha256" "$temp_file" | sha256sum --check
    mv "$temp_file" "$output_file"
    echo "Verified Robolectric runtime: $output_file"
}

prefetch "$SDK28_RUNTIME_VERSION" "$SDK28_RUNTIME_SHA256"
prefetch "$SDK35_RUNTIME_VERSION" "$SDK35_RUNTIME_SHA256"

# Robolectric 4.14.1 resolves its default API 35 framework before tests apply
# their explicit API 28 configuration. Fail before Gradle if either pinned
# runtime is missing or has changed; do not silently fall back to network fetch.
verify_runtime() {
    local artifact_name="$1"
    local expected_sha256="$2"
    local artifact_path="\${OUTPUT_DIR}/\${artifact_name}"

    if [[ ! -s "$artifact_path" ]]; then
        echo "ERROR: Required Robolectric runtime is missing or empty: $artifact_path" >&2
        exit 1
    fi
    printf '%s  %s\n' "$expected_sha256" "$artifact_path" | sha256sum --check
}

verify_runtime "$SDK28_ARTIFACT_NAME" "$SDK28_RUNTIME_SHA256"
verify_runtime "$SDK35_ARTIFACT_NAME" "$SDK35_RUNTIME_SHA256"
