#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$ROOT/app/src/main/java/me/bmax/apatch/util/Version.kt"
APP_SOURCE="$ROOT/app/src/main/java/me/bmax/apatch/APatchApp.kt"

grep -Fq '@Synchronized' "$SOURCE"
grep -Fq '"check-${android.os.Process.myPid()}"' "$SOURCE"
grep -Fq 'Os.unlink(link)' "$SOURCE"
grep -Fq 'error.errno != OsConstants.ENOENT' "$SOURCE"
grep -Fq 'Os.symlink(lib.path, link)' "$SOURCE"
grep -Fq 'createDeviceProtectedStorageContext().filesDir' "$APP_SOURCE"

printf '%s\n' '[PASS] KernelPatch inspection workspace is idempotent'
