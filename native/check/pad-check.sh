#!/opt/homebrew/bin/bash
# 平板上验证 libunireader_embed.so（`../../../docs/SEMANTIC-SYNC-PLAN.md §8`）：
# 经 app_process 调 .so 算几条查询的向量，与同一台平板上 /data/local/tmp/embed/llama-embedding 的结果比余弦（要 > 0.9999）。
# 前提：平板经 USB 连着、/data/local/tmp/embed/ 下已有 llama-embedding、模型与 single/q*.txt（SEARCH-PLAN.md §5.5 那次留下的）。
# 用法（Mac 上）：android/native/check/pad-check.sh [q00 q13 …]
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
android_dir="$(cd "$here/../.." && pwd)"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
adb="$sdk/platform-tools/adb"
bt=("$sdk"/build-tools/*)
d8="${bt[${#bt[@]}-1]}/d8"
java_home="${JAVA_HOME:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

"$java_home/bin/javac" --release 8 -d "$work/classes" "$here"/com/xvan/unireader/local/search/*.java 2>/dev/null \
  || "$java_home/bin/javac" --release 11 -d "$work/classes" "$here"/com/xvan/unireader/local/search/*.java
JAVA_HOME="$java_home" "$d8" --min-api 26 --output "$work" "$work"/classes/com/xvan/unireader/local/search/*.class
(cd "$work" && zip -q check.jar classes.dex)

dev=/data/local/tmp/embed
"$adb" push -q "$work/check.jar" "$dev/check.jar" >/dev/null
"$adb" push -q "$android_dir/app/src/main/jniLibs/arm64-v8a/libunireader_embed.so" "$dev/libunireader_embed.so" >/dev/null

qs=("$@")
[[ ${#qs[@]} -gt 0 ]] || qs=(q00 q13 q37)
for q in "${qs[@]}"; do
  f="single/$q.txt"
  "$adb" shell "cd $dev && CLASSPATH=$dev/check.jar app_process /system/bin com.xvan.unireader.local.search.EmbedCheck $dev/libunireader_embed.so $dev/Qwen3-Embedding-0.6B-Q8_0.gguf $f" \
    > "$work/so.txt" 2> "$work/so.err"
  "$adb" shell "cd $dev && ./llama-embedding -m Qwen3-Embedding-0.6B-Q8_0.gguf -f $f --embd-separator '<#sep#>' --pooling last --embd-normalize 2 -t 4 -c 512 -b 512 -ub 512 --embd-output-format json 2>/dev/null" \
    > "$work/ref.json"
  python3 - "$q" "$work/so.txt" "$work/ref.json" "$work/so.err" <<'PY'
import json, math, sys
q, so, ref, err = sys.argv[1:]
a = json.loads(open(so).read().strip().splitlines()[-1])
b = json.load(open(ref))["data"][0]["embedding"]
dot = sum(x * y for x, y in zip(a, b))
cos = dot / math.sqrt(sum(x * x for x in a) * sum(y * y for y in b))
info = " | ".join(l.strip() for l in open(err) if l.startswith(("load", "self")))
print(f"{q}: dim {len(a)}/{len(b)} 余弦 {cos:.6f} {'OK' if cos > 0.9999 else '不够'} | {info}")
PY
done
