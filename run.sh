#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

./gradlew :app:createDistributable --no-daemon
open app/build/compose/binaries/main/app/PixivShaft.app
