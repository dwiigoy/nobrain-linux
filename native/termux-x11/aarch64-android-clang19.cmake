# Custom CMake toolchain to cross-build Xlorie (termux-x11/Lorie) for
# aarch64-android using Debian's native clang-19/lld-19 plus an NDK r27c
# unified sysroot (no full NDK toolchain available on this host).
#
# Layout:
#   $NOBRAIN_NDK_SYSROOT/sysroot/      - NDK r27c unified sysroot (bionic headers/libs)
#   $NOBRAIN_NDK_SYSROOT/bin/          - fake "standalone toolchain" bin dir so CMake's
#                                                 Android-Determine/Determine-Compiler-Standalone
#                                                 modules accept this as CMAKE_ANDROID_STANDALONE_TOOLCHAIN
#                                                 (bin/clang, bin/clang++ -> clang-19/clang++-19;
#                                                 bin/aarch64-linux-android-gcc -> fake -dumpversion)
#   $NOBRAIN_NDK_SYSROOT/clang-rt/lib/linux/libclang_rt.builtins-aarch64-android.a
#                                               - compiler-rt builtins (clang-19 ships none for Android)

set(CMAKE_SYSTEM_NAME Android)
set(CMAKE_SYSTEM_VERSION 26)
set(CMAKE_ANDROID_ARCH_ABI arm64-v8a)
if(NOT DEFINED ENV{NOBRAIN_NDK_SYSROOT})
    message(FATAL_ERROR "Set NOBRAIN_NDK_SYSROOT to the custom Android toolchain root")
endif()
set(NOBRAIN_NDK_SYSROOT "$ENV{NOBRAIN_NDK_SYSROOT}")
set(CMAKE_ANDROID_STANDALONE_TOOLCHAIN "${NOBRAIN_NDK_SYSROOT}")

# Pre-set compiler targets (with API level) before project() runs, so
# __android_compiler_clang() doesn't overwrite them with a target lacking
# the API-level suffix.
set(CMAKE_C_COMPILER_TARGET   aarch64-linux-android26)
set(CMAKE_CXX_COMPILER_TARGET aarch64-linux-android26)
set(CMAKE_ASM_COMPILER_TARGET aarch64-linux-android26)

# CMAKE_ASM_COMPILER is not auto-derived by the standalone-toolchain
# detection (only C/CXX are), so set it explicitly.
set(CMAKE_ASM_COMPILER /usr/bin/clang-19)

set(_lorie_rt_flags "-rtlib=compiler-rt -resource-dir=${NOBRAIN_NDK_SYSROOT}/clang-rt -DANDROID")

set(CMAKE_C_FLAGS_INIT   "${_lorie_rt_flags} -fuse-ld=lld-19")
set(CMAKE_CXX_FLAGS_INIT "${_lorie_rt_flags} -fuse-ld=lld-19 -stdlib=libc++")
set(CMAKE_ASM_FLAGS_INIT "${_lorie_rt_flags}")

set(CMAKE_EXE_LINKER_FLAGS_INIT    "${_lorie_rt_flags} -fuse-ld=lld-19")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "${_lorie_rt_flags} -fuse-ld=lld-19")
set(CMAKE_MODULE_LINKER_FLAGS_INIT "${_lorie_rt_flags} -fuse-ld=lld-19")
