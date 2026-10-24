#!/bin/sh
# Pull a session's logs from the phone into out/logs/<name>: the logcat capture
# (/data/local/tmp/oneos_logs), the switch/return logs, the diagnostics rounds
# and the guest's start-up log. Everything is packed and gzipped on the phone
# first (about 10x smaller), which matters over the mobile-data tunnel.
#
# Usage: scripts/pull-run-logs.sh <name>     (e.g. run30; ANDROID_SERIAL picks the phone,
#        ADB="adb -H 127.0.0.1 -P 5575" uses a PC's adb server forwarded over SSH,
#        REC=1 also pulls the screen recording)
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NAME="$1"
[ -n "$NAME" ] || { echo "usage: $0 <name>" >&2; exit 2; }
SERIAL="${ANDROID_SERIAL:-127.0.0.1:5565}"
OUT="$ROOT/out/logs/$NAME"
TMP=/data/local/tmp/oneos_pull
mkdir -p "$OUT"
# The stage (/data/local/tmp/oneos), or its path up to 0.3.6 while it is still there.
${ADB:-adb} -s "$SERIAL" shell "su -c '
S=/data/local/tmp/oneos
[ -d \$S ] || [ ! -d /data/local/tmp/lineage_stage ] || S=/data/local/tmp/lineage_stage
rm -rf $TMP $TMP.tgz && mkdir -p $TMP
cp -r /data/local/tmp/oneos_logs/* \$S/*.log \$S/diag_* $TMP/ 2>/dev/null
cp \$S/data/takeover.log $TMP/takeover_inner.log 2>/dev/null
cp \$S/data/call_audio.log $TMP/ 2>/dev/null
cp /data/local/tmp/ui_activator.log $TMP/ 2>/dev/null
cd $TMP && /data/adb/ksu/bin/busybox tar czf $TMP.tgz . && chmod 644 $TMP.tgz && rm -rf $TMP
ls -l $TMP.tgz'"
${ADB:-adb} -s "$SERIAL" pull "$TMP.tgz" "$OUT/logs.tgz"
(cd "$OUT" && tar xzf logs.tgz && rm logs.tgz && ls boot.log* | sort -r | xargs cat > all.log)
echo "$OUT: $(wc -l < "$OUT/all.log") logcat lines"
# REC=1 also pulls the session's screen recording (guest_start.sh writes
# data/oneos_rec_N.mp4 in the stage; root-only, so copied out first).
if [ "${REC:-0}" = 1 ]; then
    ${ADB:-adb} -s "$SERIAL" shell "su -c 'S=/data/local/tmp/oneos; [ -d \$S ] || [ ! -d /data/local/tmp/lineage_stage ] || S=/data/local/tmp/lineage_stage
rm -rf /data/local/tmp/oneos_rec_pull && mkdir /data/local/tmp/oneos_rec_pull &&
cp \$S/data/oneos_rec_*.mp4 /data/local/tmp/oneos_rec_pull/ && chmod 755 /data/local/tmp/oneos_rec_pull && chmod 644 /data/local/tmp/oneos_rec_pull/*'"
    mkdir -p "$OUT/rec"
    ${ADB:-adb} -s "$SERIAL" pull /data/local/tmp/oneos_rec_pull/. "$OUT/rec/"
    ${ADB:-adb} -s "$SERIAL" shell "su -c 'rm -rf /data/local/tmp/oneos_rec_pull'"
    ls -l "$OUT/rec"
fi
