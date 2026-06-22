# USearch for Android

This wrapper packages the Java/JNI USearch binding as an Android library for
64-bit ARM devices (`arm64-v8a`). It builds `libusearch_jni.so` and exposes the
same `cloud.unum.usearch.Index` API used by the Java binding.

## Build the AAR

Prerequisites:

- JDK 17 or newer.
- Android SDK with API 37.
- Android NDK installed.
- Gradle 9.4.1 or newer for Android Gradle Plugin 9.2.1.
- Git submodules initialized with `git submodule update --init --recursive`.

From this directory:

```sh
gradle :usearch-android:assembleRelease
```

The generated AAR will be at:

```text
usearch-android/build/outputs/aar/usearch-android-release.aar
```

The module is restricted to the `arm64-v8a` ABI through `abiFilters`, so the AAR
contains:

```text
jni/arm64-v8a/libusearch_jni.so
```

## Use the AAR in an Android app

Copy the generated AAR into your app, for example:

```text
app/libs/usearch-android-release.aar
```

Add it to the app's Gradle build:

```groovy
repositories {
    google()
    mavenCentral()
    flatDir {
        dirs "libs"
    }
}

dependencies {
    implementation name: "usearch-android-release", ext: "aar"
}

android {
    defaultConfig {
        ndk {
            abiFilters "arm64-v8a"
        }
    }
}
```

Then use USearch from Java or Kotlin:

```java
import cloud.unum.usearch.Index;
import cloud.unum.usearch.android.USearchAndroid;

USearchAndroid.load();

try (Index index = USearchAndroid.newIndexConfig()
        .metric(Index.Metric.COSINE)
        .quantization(Index.Quantization.FLOAT32)
        .dimensions(3)
        .capacity(100)
        .build()) {

    index.add(42L, new float[]{0.1f, 0.2f, 0.3f});
    long[] keys = index.search(new float[]{0.1f, 0.2f, 0.3f}, 10);
}
```

## Manual `jniLibs` integration

If you do not want to consume the AAR, build the JNI library with the Android
NDK and copy it into your app:

```sh
cmake -B build_android_arm64 \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION/build/cmake/android.toolchain.cmake" \
  -DCMAKE_ANDROID_STL_TYPE=c++_static \
  -DANDROID_PLATFORM=23 \
  -DANDROID_ABI=arm64-v8a \
  -DUSEARCH_BUILD_JNI=ON \
  -DUSEARCH_BUILD_TEST_CPP=OFF \
  -DUSEARCH_BUILD_BENCH_CPP=OFF \
  -DUSEARCH_BUILD_LIB_C=OFF \
  -DUSEARCH_USE_NUMKONG=ON \
  -DNK_TARGET_NEON=ON \
  -DNK_TARGET_NEONBFDOT=ON \
  -DNK_TARGET_NEONHALF=ON \
  -DNK_TARGET_NEONSDOT=ON \
  -DNK_DYNAMIC_DISPATCH=ON

cmake --build build_android_arm64 --config Release
mkdir -p app/src/main/jniLibs/arm64-v8a
cp build_android_arm64/libusearch_jni.so app/src/main/jniLibs/arm64-v8a/
```

Include the Java binding sources or the USearch Java JAR in the app, then call
`USearchAndroid.load()` or let `Index` load `libusearch_jni.so` automatically.
