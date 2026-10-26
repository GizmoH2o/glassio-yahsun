#!/usr/bin/env bash
# tools/release.sh [--dry-run] : builds the release APK and publishes it on GitHub (GizmoH2o/glassio-yahsun) as
# v<versionName>, with that version's section of CHANGELOG.md as the release notes (the in-app update banner shows
# them). Stops when CHANGELOG.md has no section for the version, so every release says what changed.
#
# Before running: bump versionCode and versionName in app/build.gradle.kts, add "## <versionName> — <date>" at the top
# of CHANGELOG.md, commit and push. The APK is signed with this computer's debug key (~/.android/debug.keystore):
# keep that file, or the TVs that have Glassio Yahsun can't install the next update over it.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=GizmoH2o/glassio-yahsun
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
mkdir -p releases
APK="releases/GlassioYahsun-$VERSION.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"

echo "== $TAG notes =="; cat "$NOTES"; echo "== $APK ($(wc -c < "$APK") bytes) =="
[ "$DRY" = 1 ] && { echo "Dry run: nothing published."; exit 0; }

git tag -a "$TAG" -m "Glassio Yahsun $VERSION"
git push origin "$TAG"
gh release create "$TAG" "$APK" --repo "$REPO" --title "Glassio Yahsun $VERSION" --notes-file "$NOTES" --verify-tag
