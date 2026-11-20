#!/usr/bin/env bash
# Zero-permissions gate.
#
# tengok requests no Android permissions. The single allowed exception is
# androidx core's self-protective receiver shim, pulled in by the manifest
# merger from androidx core's own library manifest:
#     <permission android:name="${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
#         android:protectionLevel="signature" />
#     <uses-permission android:name="...same..." />
# ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED) on API < 33 hands
# this permission to the platform as the broadcastPermission, so only apps
# signed with the SAME key can deliver broadcasts to those receivers. It
# grants no user-data access and is not a dangerous permission. Sources:
#   androidx/core core/src/main/AndroidManifest.xml (declaration, signature)
#   androidx/core ContextCompat.registerReceiver (API<33 permission hand-off)
#   F-Droid fdroidserver MR 1336 (permission deliberately not catalogued)
#
# Policy: fail on ANY other uses-permission or declared permission; the shim
# itself must be declared with protectionLevel exactly 0x2 (signature). Any
# change to the shim's level or name fails loudly so it is re-reviewed.
set -euo pipefail

AAPT="$1"; APK="$2"
SHIM="com.vanipy.tengok.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

dump="$("$AAPT" dump permissions "$APK")"
unexpected="$(printf '%s\n' "$dump" | grep 'uses-permission' | grep -vF "name='${SHIM}'" || true)"
if [ -n "$unexpected" ]; then
    echo "unexpected permission(s) requested:"
    printf '%s\n' "$unexpected"
    exit 1
fi

need_decl=0
if printf '%s\n' "$dump" | grep -qF "name='${SHIM}'"; then
    need_decl=1
fi

"$AAPT" dump xmltree "$APK" AndroidManifest.xml | awk \
    -v shim="$SHIM" -v need="$need_decl" '
    function flush(   ) {
        if (!inperm) return
        if (name == "") { inperm=0; return }
        if (name != shim) {
            print "unexpected <permission> declaration: " name > "/dev/stderr"
            bad=1
        } else if (prot != "0x2") {
            print "shim protectionLevel is " prot ", expected 0x2 (signature)" > "/dev/stderr"
            bad=1
        } else {
            found=1
        }
        inperm=0
    }
    /^ *E: permission \(/ { flush(); inperm=1; name=""; prot=""; next }
    /^ *E: /             { flush(); next }
    inperm && /A: android:name\(/ {
        if (match($0, /"[^"]+"/)) name = substr($0, RSTART+1, RLENGTH-2)
    }
    inperm && /A: android:protectionLevel\(/ {
        prot = ""; if (match($0, /\)0x[0-9a-f]+/)) prot = substr($0, RSTART+1, RLENGTH-1)
    }
    END {
        flush()
        if (!bad && need == 1 && !found) {
            print "shim uses-permission present but no signature-level <permission> declaration found" > "/dev/stderr"
            bad=1
        }
        exit bad
    }'

if [ "$need_decl" = 1 ]; then
    echo "permission gate: only the androidx receiver shim is requested; signature-level declaration verified"
else
    echo "permission gate: no permissions requested"
fi
