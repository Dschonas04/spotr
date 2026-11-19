#!/usr/bin/env bash
# Baut das APK im Android-Container auf dem Docker-Host und holt es nach ausgabe/.
#   ./bauen.sh [debug|release]
set -euo pipefail
VARIANTE="${1:-debug}"
BAUHOST="${BAUHOST:-debian@10.0.2.7}"
FERN="/tmp/spotr"
HIER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ssh "$BAUHOST" "rm -rf $FERN/app/build/outputs"
rsync -az --delete --exclude '.git' --exclude 'build' --exclude '.gradle' --exclude '.kotlin' --exclude 'ausgabe' "$HIER/" "$BAUHOST:$FERN/"
ssh "$BAUHOST" "cd $FERN && docker run --rm \
    -v $FERN:/build -v hecht-gradle-cache:/build/.gradle -w /build \
    nexora-android:1 bash -c 'export ANDROID_USER_HOME=/build/.gradle/android-spotr; ./gradlew --no-daemon -q testDebugUnitTest assemble${VARIANTE^}; r=\$?; chown -R \$(stat -c %u:%g /build/bauen.sh) /build/app /build/.kotlin 2>/dev/null; exit \$r'"
mkdir -p "$HIER/ausgabe"; rm -f "$HIER/ausgabe/spotr-$VARIANTE.apk"
scp -q "$BAUHOST:$FERN/app/build/outputs/apk/$VARIANTE/app-$VARIANTE*.apk" "$HIER/ausgabe/spotr-$VARIANTE.apk"
ls -lh "$HIER/ausgabe/spotr-$VARIANTE.apk"
