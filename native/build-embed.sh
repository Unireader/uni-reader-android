#!/opt/homebrew/bin/bash
# 重编 libunireader_embed.so（安卓语义搜索算查询向量用，`../../docs/SEMANTIC-SYNC-PLAN.md §4.3`）。
#
# 做的事：把 llama.cpp 源码拉到主仓库 build/llama-embed-src（钉提交），用 NDK + Homebrew 的 cmake / ninja 编
# llama / ggml 静态库，与 embed_jni.cpp 链成一个 .so（libc++ 静态、只导出 JNI 符号），strip 后放进
# app/src/main/jniLibs/arm64-v8a/。产物提交进安卓仓库，Gradle 构建不碰 native。
#
# 用法（在 Mac 上）：android/native/build-embed.sh
# 环境：NDK 30.0.16248370（~/Library/Android/sdk/ndk/）、/opt/homebrew/bin/cmake、/opt/homebrew/bin/ninja、git。
set -euo pipefail

LLAMA_COMMIT=b1b770d90d94cad9e9ea5b993bdbc383a522fb28   # build 12359，与 Mac / 平板实测同一提交（SEARCH-PLAN.md §5.5）
LLAMA_REPO=https://github.com/ggml-org/llama.cpp.git
NDK_VERSION=30.0.16248370
# minSdk = 26：按最低版本编，API 26 的设备也能加载（.so 用到的 libc 符号都在 26 里有）
ANDROID_API=26

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
android_dir="$(cd "$here/.." && pwd)"
repo_root="$(cd "$android_dir/.." && pwd)"
src="$repo_root/build/llama-embed-src"
out="$repo_root/build/llama-embed-out"
ndk="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/$NDK_VERSION}"
cmake_bin=/opt/homebrew/bin/cmake
ninja_bin=/opt/homebrew/bin/ninja
dest="$android_dir/app/src/main/jniLibs/arm64-v8a"

for f in "$ndk/build/cmake/android.toolchain.cmake" "$cmake_bin" "$ninja_bin"; do
  [[ -e "$f" ]] || { echo "缺少 $f" >&2; exit 1; }
done

# 1. 源码：已有就只 checkout，没有就浅拉那一个提交
if [[ ! -d "$src/.git" ]]; then
  mkdir -p "$src"
  git -C "$src" init -q
  git -C "$src" remote add origin "$LLAMA_REPO"
fi
if ! git -C "$src" cat-file -e "$LLAMA_COMMIT^{commit}" 2>/dev/null; then
  echo "拉取 llama.cpp $LLAMA_COMMIT …"
  git -C "$src" fetch -q --depth 1 origin "$LLAMA_COMMIT"
fi
git -C "$src" -c advice.detachedHead=false checkout -q --force "$LLAMA_COMMIT"

# 2. 配置 + 编译
arch_flags="-march=armv8.2-a+dotprod+fp16"
"$cmake_bin" -S "$here" -B "$out" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$ninja_bin" \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM="android-$ANDROID_API" \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_FLAGS="$arch_flags" -DCMAKE_CXX_FLAGS="$arch_flags" \
  -DLLAMA_SRC="$src" \
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DBUILD_SHARED_LIBS=OFF \
  -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_SERVER=OFF \
  -DLLAMA_BUILD_COMMON=OFF -DLLAMA_BUILD_APP=OFF
"$ninja_bin" -C "$out" unireader_embed

# 3. strip + 放进 jniLibs
strip_bin="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"
nm_bin="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-nm"
mkdir -p "$dest"
"$strip_bin" --strip-unneeded -o "$dest/libunireader_embed.so" "$out/libunireader_embed.so"

echo "导出符号："
"$nm_bin" -D --defined-only "$dest/libunireader_embed.so" | grep ' T ' || true
echo "依赖："
"$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf" -d "$dest/libunireader_embed.so" | grep NEEDED || true
ls -l "$dest/libunireader_embed.so"
