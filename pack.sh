#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# UniReader Android 打包：release（默认）或 debug 构建；adb 恰好有一台设备时顺带安装。
#
# 用法：
#   ./pack.sh                # release 打包；有且仅有一台 adb 设备时安装
#   ./pack.sh --debug        # debug 打包（同样尝试安装）
#   ./pack.sh --no-install   # 只打包不安装
#   ./pack.sh --no-bump      # release 但不自动升版本号
#
# 版本号：release 打包前自动改 app/build.gradle.kts（versionCode+1、versionName patch+1），
# debug 不动。想升 minor/major 就手动改 versionName，脚本只加最后一位。
#
# 签名（debug/release 同一证书，见 app/build.gradle.kts）：
#   keystore = ~/.keystores/xVanTuring.jks (alias key0)
#   密码从 local.properties 读：releaseStorePassword / releaseKeyPassword（本机文件，不入 git）

VARIANT="release"
DO_INSTALL=1
DO_BUMP=1
for arg in "$@"; do
    case "$arg" in
        --debug) VARIANT="debug" ;;
        --release) VARIANT="release" ;;
        --no-install) DO_INSTALL=0 ;;
        --no-bump) DO_BUMP=0 ;;
        -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
        *) echo "未知参数：$arg（-h 看用法）" >&2; exit 2 ;;
    esac
done

# --- 签名密码前置检查（缺了会给能看懂的提示，而不是 AGP 的天书） ---
need_pw() { ! grep -q "^$1=" local.properties; }
if need_pw releaseStorePassword || need_pw releaseKeyPassword; then
    echo "缺少签名密码：请在 android/local.properties 里补两行（本机文件，不入 git）：" >&2
    echo "  releaseStorePassword=..." >&2
    echo "  releaseKeyPassword=..." >&2
    exit 1
fi

# --- SDK / adb 定位（sdk.dir 或 ANDROID_HOME） ---
SDK_DIR="${ANDROID_HOME:-}"
if [ -z "$SDK_DIR" ] && [ -f local.properties ]; then
    SDK_DIR=$(sed -n 's/^sdk\.dir=//p' local.properties | tail -1)
fi
ADB="$SDK_DIR/platform-tools/adb"
APKSIGNER=$(ls -d "$SDK_DIR"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)

# --- 自动升版本：release 打包前改 app/build.gradle.kts（code+1 / patch+1） ---
if [ "$VARIANT" = "release" ] && [ "$DO_BUMP" -eq 1 ]; then
    GRADLE_FILE="app/build.gradle.kts"
    CODE=$(sed -n 's/^ *versionCode = \([0-9][0-9]*\).*/\1/p' "$GRADLE_FILE" | head -1)
    NAME=$(sed -n 's/^ *versionName = "\([^"]*\)".*/\1/p' "$GRADLE_FILE" | head -1)
    if [ -z "$CODE" ] || ! grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' <<< "$NAME"; then
        echo "解析版本号失败（$GRADLE_FILE 里应有 versionCode = N / versionName = \"x.y.z\"），" >&2
        echo "请手动修正或用 --no-bump 跳过。" >&2
        exit 1
    fi
    NEW_CODE=$((CODE + 1))
    IFS='.' read -r VMAJ VMIN VPAT <<< "$NAME"
    NEW_NAME="$VMAJ.$VMIN.$((VPAT + 1))"
    sed -i '' -e "s/versionCode = $CODE/versionCode = $NEW_CODE/" \
              -e "s/versionName = \"$NAME\"/versionName = \"$NEW_NAME\"/" "$GRADLE_FILE"
    echo "==> 版本：$NAME($CODE) → $NEW_NAME($NEW_CODE)"
fi

# --- 构建 ---
TASK="assemble$(tr '[:lower:]' '[:upper:]' <<< "${VARIANT:0:1}")${VARIANT:1}"
echo "==> ./gradlew $TASK"
./gradlew "$TASK"

APK="app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
[ -f "$APK" ] || { echo "没找到产物：$APK" >&2; exit 1; }
echo "==> 产物：$APK ($(du -h "$APK" | cut -f1))"

# --- 签名核验（打印证书摘要，方便跟别处的包对签名） ---
if [ -n "$APKSIGNER" ]; then
    "$APKSIGNER" verify --print-certs "$APK" | sed 's/^/    /'
fi

# --- 安装：恰好一台在线设备才动手，多台/零台都只给提示 ---
if [ "$DO_INSTALL" -eq 1 ]; then
    if [ ! -x "$ADB" ]; then
        echo "==> 找不到 adb（$ADB），跳过安装"
    else
        DEVS=()
        # 按制表符切：无线调试的设备名里带空格（如 `adb-xxx (2)._adb-tls-connect._tcp`），按空格切会把它当成离线
        while IFS= read -r line; do DEVS+=("$line"); done < <("$ADB" devices | awk -F'\t' 'NR>1 && $2=="device" {print $1}')
        if [ "${#DEVS[@]}" -eq 1 ]; then
            echo "==> 安装到 ${DEVS[0]}"
            "$ADB" install -r "$APK"
        elif [ "${#DEVS[@]}" -eq 0 ]; then
            echo "==> 没有在线的 adb 设备，跳过安装"
        else
            echo "==> 检测到 ${#DEVS[@]} 台设备，跳过自动安装。手动装："
            for d in "${DEVS[@]}"; do echo "    $ADB -s $d install -r $APK"; done
        fi
    fi
fi
