# Fermata AirPlay receiver

The APK enables this feature when both Gradle properties `AIRPLAY_SOURCE`
and `AIRPLAY_OPENSSL` are present. The release source contains the pinned
UxPlay, libplist and OpenSSL source archives.

`AIRPLAY_SOURCE` is a directory containing `UxPlay` and `libplist` as
sibling directories. Apply `uxplay-pairing.patch` to the extracted
UxPlay directory first with `git apply --unidiff-zero`. The AirPlay CMake host smoke fixture and
Android target both use this patch.

`AIRPLAY_OPENSSL` has a directory per ABI with `include` and
`libcrypto.a`. For the ARM64 release, configure the unpacked
OpenSSL 3.4.4 source with the pinned NDK and API 28:

```sh
export ANDROID_NDK_ROOT=/absolute/path/to/ndk/29.0.14206865
export PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
./Configure android-arm64 -D__ANDROID_API__=28 no-shared no-tests no-apps no-docs -fPIC
make -j2 build_libs
```

Copy or link the resulting `include` directory and `libcrypto.a` into
`AIRPLAY_OPENSSL/arm64-v8a`. Pass both properties to the normal Auto
Release APK task.

The JVM smoke can use the host-installed OpenSSL development package.
Build the AirPlay CMake source with `-DFERMATA_AIRPLAY_SOURCE=/path/to/deps`
and no Android toolchain. Compile `verification/AirPlayReceiver.java`
with Java 17 or newer and run its main class with `-Djava.library.path`
pointing at the CMake build directory. It uses a loopback RTSP session to
test lifecycle, discovery records, an info payload, feedback, and denial
of a SETUP request without a verified pairing. It takes no iPhone capture
and makes no claims about Android NSD, MediaCodec, or head-unit rendering.
