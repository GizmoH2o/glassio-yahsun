#!/usr/bin/env bash
# tools/release.sh [--dry-run] : builds the release APK and publishes it on GitHub (GizmoH2o/glassio-yahsun) as
# v<versionName>, with that version's section of CHANGELOG.md as the release notes (the in-app update banner shows
# them). Stops when CHANGELOG.md has no section for the version, so every release says what changed.
#
# Before running: bump versionCode and versionName in app/build.gradle.kts, add "## <versionName> — <date>" at the top
# of CHANGELOG.md, commit and push. The APK is signed with Glassio Yahsun's release key (RELEASE_STORE_FILE and the rest
# in local.properties); the script checks the APK's certificate and stops when it's any other key. Keep the keystore
# and its password backed up: without them, the TVs that have Glassio Yahsun can't install another update over it.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=GizmoH2o/glassio-yahsun
# The release key's certificate (SHA-256), public: every published APK must carry it.
RELEASE_CERT=99a1d555c070a447a4e5d5e592425024e15523bee73f3da2705be0883ae94dda
DRY=0; [ "${1:-}" = "--dry-run" ] && DRY=1

VERSION=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts)
[ -n "$VERSION" ] || { echo "No versionName in app/build.gradle.kts" >&2; exit 1; }
TAG="v$VERSION"

NOTES=$(mktemp)
trap 'rm -f "$NOTES"' EXIT
# The lines after "## <version>" up to the next "## ".
awk -v v="$VERSION" '
  /^## / { if (on) exit; if ($2 == v) { on = 1; next } }
  on { print }
' CHANGELOG.md | sed -e '/./,$!d' > "$NOTES"
if ! grep -q '[^[:space:]]' "$NOTES"; then
  echo "CHANGELOG.md has no section for $VERSION: add \"## $VERSION — $(date +%F)\" with what changed." >&2
  exit 1
fi

if [ "$DRY" = 0 ]; then
  [ -z "$(git status --porcelain)" ] || { echo "Commit your changes first (git status isn't clean)." >&2; exit 1; }
  if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    echo "$TAG is already released: bump versionName first." >&2; exit 1
  fi
fi

./gradlew assembleRelease
# The signing key: Glassio Yahsun's release key, not the debug one (a build without local.properties' key gets that).
SDK=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties | sed -e 's#\\:#:#' -e 's#\\\\#/#g')}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
CERT=$(java -jar "$BT/lib/apksigner.jar" verify --print-certs app/build/outputs/apk/release/app-release.apk \
  | sed -n 's/^.*[Ss]igner.* certificate SHA-256 digest: //p' | sort -u)
if [ "$CERT" != "$RELEASE_CERT" ]; then
  echo "The APK isn't signed with Glassio Yahsun's release key (got ${CERT:-none}): check RELEASE_STORE_FILE in local.properties." >&2
  exit 1
fi
mkdir -p releases
APK="releases/GlassioYahsun-$VERSION.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"

echo "== $TAG notes =="; cat "$NOTES"; echo "== $APK ($(wc -c < "$APK") bytes) =="
[ "$DRY" = 1 ] && { echo "Dry run: nothing published."; exit 0; }

git tag -a "$TAG" -m "Glassio Yahsun $VERSION"
git push origin "$TAG"
gh release create "$TAG" "$APK" --repo "$REPO" --title "Glassio Yahsun $VERSION" --notes-file "$NOTES" --verify-tag
