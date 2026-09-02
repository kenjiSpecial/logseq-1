#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

export LOGSEQ_JOURNAL_ANDROID=1
export LOGSEQ_JOURNAL_API_BASE_URL="${LOGSEQ_JOURNAL_API_BASE_URL:-http://100.74.89.18:3060}"
export LOGSEQ_JOURNAL_LOCAL_GRAPH_DIR="${LOGSEQ_JOURNAL_LOCAL_GRAPH_DIR:-journal/graph}"
export LOGSEQ_JOURNAL_LOCAL_GRAPH_NAME="${LOGSEQ_JOURNAL_LOCAL_GRAPH_NAME:-Journal}"
export JAVA_HOME="${LOGSEQ_JOURNAL_JAVA_HOME:-/home/kenji/.jdks/jdk-17.0.11+9}"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.java.home=$JAVA_HOME"
maven_repo="${LOGSEQ_JOURNAL_MAVEN_REPO:-/tmp/logseq-journal-m2}"

upstream_head="$(git rev-parse HEAD)"
upstream_origin="$(git rev-parse origin/main 2>/dev/null || true)"
upstream_version="$(node -p "require('./package.json').version")"

test "$upstream_version" = "1.0.0"
command -v yarn >/dev/null
command -v clojure >/dev/null
test -x "$JAVA_HOME/bin/java"

echo "Journal Android debug build"
echo "  upstream HEAD: $upstream_head"
echo "  origin/main:   ${upstream_origin:-unavailable}"
echo "  OG version:    $upstream_version"
echo "  API base:      $LOGSEQ_JOURNAL_API_BASE_URL"
echo "  graph dir:     $LOGSEQ_JOURNAL_LOCAL_GRAPH_DIR"
echo "  graph name:    $LOGSEQ_JOURNAL_LOCAL_GRAPH_NAME"
echo "  Java:          $JAVA_HOME"
echo "  Maven cache:   $maven_repo"

yarn install --frozen-lockfile --ignore-scripts
yarn --cwd tldraw install --frozen-lockfile
# Root install intentionally skips lifecycle scripts; install the package with
# its lockfile and invoke its pinned build script before gulp consumes dist/.
yarn --cwd packages/amplify install --frozen-lockfile --ignore-scripts
yarn --cwd packages/amplify run build:amplify
yarn gulp:build
mkdir -p "$maven_repo"
clojure -Sdeps "{:mvn/local-repo \"$maven_repo\"}" -M:cljs release app --config-merge '{:compiler-options {:output-feature-set :es6}}'

mkdir -p public/static
find public/static -mindepth 1 -maxdepth 1 ! -name yarn.lock -exec rm -rf {} +
cp -a static/. public/static/

npx cap sync android
(
  cd android
  ./gradlew --stop >/dev/null 2>&1 || true
  ./gradlew --no-daemon :app:assembleDebug
)

apk_path="$PWD/android/app/build/outputs/apk/debug/app-debug.apk"
test -s "$apk_path"
apk_sha256="$(sha256sum "$apk_path" | awk '{print $1}')"

echo "APK: $apk_path"
echo "APK SHA256: $apk_sha256"
echo "APK package: dev.patopo.journal"
echo "APK versionCode: 100"
echo "APK versionName: 1.0.0"
