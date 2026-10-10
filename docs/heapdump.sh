#!/usr/bin/env bash
# Take a Java heap dump of Boswell Phone (net.boswell.phone) with Perfetto's
# java_hprof data source, and pull it here. Works on the release build since
# the manifest's <profileable android:shell="true"/> (Android 10+).
#
#   docs/heapdump.sh                one dump, now
#   WATCH_S=900 docs/heapdump.sh    a dump every INTERVAL_S (default 60) seconds
#                                   for 15 minutes, to catch a spike as it happens
#
# Open the .pftrace at https://ui.perfetto.dev (the heap graph shows each
# class's retained size and the path that keeps it alive). Needs adb and one
# phone connected; the app must be running.
set -euo pipefail

PKG=net.boswell.phone
WATCH_S=${WATCH_S:-0}
INTERVAL_S=${INTERVAL_S:-60}
STAMP=$(date +%Y%m%d-%H%M%S)
REMOTE=/data/misc/perfetto-traces/boswell-heap-$STAMP.pftrace
LOCAL=${OUT:-boswell-heap-$STAMP.pftrace}

adb get-state >/dev/null
PID=$(adb shell pidof "$PKG" | tr -d '\r')
[ -n "$PID" ] || { echo "$PKG isn't running" >&2; exit 1; }
echo "dumping the Java heap of $PKG (pid $PID)"

if [ "$WATCH_S" -gt 0 ]; then
  DURATION_MS=$(( WATCH_S * 1000 ))
  CONTINUOUS="continuous_dump_config { dump_phase_ms: 0 dump_interval_ms: $(( INTERVAL_S * 1000 )) }"
else
  DURATION_MS=10000
  CONTINUOUS=""
fi

# The config goes in on stdin: a file pushed to /data/local/tmp can't be read by perfetto on newer Androids.
adb shell perfetto --txt -c - -o "$REMOTE" <<EOF
buffers { size_kb: 512000 fill_policy: DISCARD }
data_sources {
  config {
    name: "android.java_hprof"
    java_hprof_config {
      process_cmdline: "$PKG"
      dump_smaps: true
      $CONTINUOUS
    }
  }
}
duration_ms: $DURATION_MS
data_source_stop_timeout_ms: 120000
EOF

adb pull "$REMOTE" "$LOCAL"
adb shell rm -f "$REMOTE"
echo "saved $LOCAL ($(du -h "$LOCAL" | cut -f1)); open it at https://ui.perfetto.dev"
