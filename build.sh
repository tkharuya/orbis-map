#!/usr/bin/env bash
set -euo pipefail

project_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$project_directory"
source_directory=app/src/main
version_code=$(awk '$1 == "versionCode" { print $2; exit }' app/build.gradle)
version_name=$(awk '$1 == "versionName" { print $2; exit }' app/build.gradle | tr -d "'\"")
sdk_directory=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}
android_jar="$sdk_directory/platforms/android-35/android.jar"
build_tools="$sdk_directory/build-tools/${ANDROID_BUILD_TOOLS_VERSION:-35.0.0}"
compiler_platform="$android_jar:$build_tools/core-lambda-stubs.jar"
apk_path=${1:-$project_directory/build/orbis-map.apk}

if [[ ! -f "$android_jar" || ! -x "$build_tools/aapt2" ]]; then
    echo 'Install Android SDK Platform 35 and Build Tools 35.0.0, then set ANDROID_SDK_ROOT.' >&2
    exit 1
fi

signing_directory=${ORBIS_SIGNING_DIRECTORY:-$project_directory/build/signing}
mkdir -p -- "$signing_directory"
key_path="$signing_directory/orbis.p12"
password_path="$signing_directory/password"
if [[ ! -e "$key_path" ]]; then
    (
        umask 077
        if [[ ! -s "$password_path" ]]; then
            openssl rand -hex 24 > "$password_path"
        fi
        keytool -genkeypair -keystore "$key_path" -storetype PKCS12 \
            -storepass:file "$password_path" -alias orbis -keyalg RSA -keysize 3072 \
            -validity 10000 -dname 'CN=Orbis Map'
    )
fi

work_directory=$(mktemp -d)
trap 'rm -rf -- "$work_directory"' EXIT
mkdir -p -- "$work_directory/generated" "$work_directory/classes" "$work_directory/dex"

"$build_tools/aapt2" compile --dir "$source_directory/res" -o "$work_directory/resources.zip"
assets=()
if [[ -d "$source_directory/assets" ]]; then
    assets=(-A "$source_directory/assets")
fi
"$build_tools/aapt2" link -I "$android_jar" --manifest "$source_directory/AndroidManifest.xml" \
    --java "$work_directory/generated" --min-sdk-version 26 --target-sdk-version 35 \
    --version-code "$version_code" --version-name "$version_name" \
    "${assets[@]}" -o "$work_directory/unsigned.apk" "$work_directory/resources.zip"

mapfile -d '' -t sources < <(find "$source_directory/java" "$work_directory/generated" -name '*.java' -print0)
compiler=(javac)
if ! command -v javac >/dev/null 2>&1; then
    compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${compiler[@]}" -source 8 -target 8 -encoding UTF-8 -bootclasspath "$compiler_platform" \
    -d "$work_directory/classes" "${sources[@]}"

(
    cd "$work_directory/classes"
    zip -qr "$work_directory/classes.jar" .
)
"$build_tools/d8" --release --min-api 26 --lib "$android_jar" \
    --output "$work_directory/dex" "$work_directory/classes.jar"
zip -qj "$work_directory/unsigned.apk" "$work_directory"/dex/classes*.dex
"$build_tools/zipalign" -f 4 "$work_directory/unsigned.apk" "$work_directory/aligned.apk"

mkdir -p -- "$(dirname -- "$apk_path")"
"$build_tools/apksigner" sign --ks "$key_path" --ks-key-alias orbis \
    --ks-pass "file:$password_path" --v4-signing-enabled false \
    --out "$apk_path" "$work_directory/aligned.apk"
"$build_tools/apksigner" verify --verbose "$apk_path"
printf 'APK saved to %s\n' "$apk_path"
