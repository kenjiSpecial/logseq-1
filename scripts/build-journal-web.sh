#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

# Hosted web artifact for journal.pa-to-po.dev.
# Keep this separate from Android/Capacitor's public/static output so mobile
# builds cannot replace the server-graph web bundle with a local-first bundle.
export LOGSEQ_JOURNAL_ANDROID=false
export LOGSEQ_JOURNAL_API_BASE_URL="${LOGSEQ_JOURNAL_API_BASE_URL:-https://journal.pa-to-po.dev}"

WEB_STATIC_DIR="${LOGSEQ_JOURNAL_WEB_STATIC_DIR:-static-web}"

echo "Building Journal hosted web bundle"
echo "  API base: $LOGSEQ_JOURNAL_API_BASE_URL"
echo "  Output:   $PWD/$WEB_STATIC_DIR"

npm run gulp:build

docker run --rm \
  -e HOME=/tmp \
  -e LOGSEQ_JOURNAL_ANDROID="$LOGSEQ_JOURNAL_ANDROID" \
  -e LOGSEQ_JOURNAL_API_BASE_URL="$LOGSEQ_JOURNAL_API_BASE_URL" \
  -v "$PWD:/work" \
  -w /work \
  clojure:temurin-21-tools-deps-bookworm \
  sh -lc 'apt-get update >/dev/null && apt-get install -y nodejs npm >/dev/null && git config --global --add safe.directory /work && clojure -M:cljs release app --config-merge "{:compiler-options {:output-feature-set :es6}}"'

rm -rf ./static/js/*.map
rm -rf "$WEB_STATIC_DIR"
mkdir -p "$WEB_STATIC_DIR"
cp -a static/. "$WEB_STATIC_DIR/"

docker run --rm -v "$PWD:/work" alpine:3.20 \
  sh -lc "chown -R 1000:1000 /work/static /work/$WEB_STATIC_DIR /work/.cpcache 2>/dev/null || true"

echo "Hosted web static bundle: $PWD/$WEB_STATIC_DIR"
