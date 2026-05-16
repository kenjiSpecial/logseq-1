#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

export LOGSEQ_JOURNAL_ANDROID=1
export LOGSEQ_JOURNAL_API_BASE_URL="${LOGSEQ_JOURNAL_API_BASE_URL:-${JOURNAL_API_BASE_URL:-http://100.74.89.18:3060}}"
export JAVA_HOME="${LOGSEQ_JOURNAL_JAVA_HOME:-/home/kenji/.jdks/jdk-17.0.11+9}"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_OPTS="-Dorg.gradle.java.home=$JAVA_HOME"

echo "Building Journal Android APK"
echo "  API base: $LOGSEQ_JOURNAL_API_BASE_URL"

npm run gulp:build

docker run --rm \
  -e HOME=/tmp \
  -e LOGSEQ_JOURNAL_ANDROID="$LOGSEQ_JOURNAL_ANDROID" \
  -e LOGSEQ_JOURNAL_API_BASE_URL="$LOGSEQ_JOURNAL_API_BASE_URL" \
  -v "$PWD:/work" \
  -w /work \
  clojure:temurin-21-tools-deps-bookworm \
  sh -lc 'apt-get update >/dev/null && apt-get install -y nodejs npm >/dev/null && git config --global --add safe.directory /work && clojure -M:cljs release app --config-merge "{:compiler-options {:output-feature-set :es6}}"'

rm -rf ./public/static
rm -rf ./static/js/*.map
mv static ./public
rm -rf static
cp -a public/static static

docker run --rm -v "$PWD:/work" alpine:3.20 \
  sh -lc 'chown -R 1000:1000 /work/public/static /work/static /work/.cpcache 2>/dev/null || true'

npx cap sync android
(
  cd android
  ./gradlew --stop >/dev/null 2>&1 || true
  ./gradlew --no-daemon :app:assembleDebug
)

echo "APK: $PWD/android/app/build/outputs/apk/debug/app-debug.apk"
