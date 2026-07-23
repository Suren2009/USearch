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

A copy is also published to the repo `release/` folder for distribution:

```text
../release/usearch-android-release.aar
```

The module is restricted to the `arm64-v8a` ABI through `abiFilters`, so the AAR
contains:

```text
jni/arm64-v8a/libusearch_jni.so
jni/arm64-v8a/libnumkong.so
```

`libnumkong.so` is the NumKong vector/SIMD backend linked by the JNI library.

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

## Java Sample Code

### 1. Basic Vector Index Creation and Insertion

```java
import cloud.unum.usearch.Index;
import cloud.unum.usearch.android.USearchAndroid;

// Load the native JNI library
USearchAndroid.load();

// Create a Cosine index for 64-dimensional float vectors
try (Index index = USearchAndroid.newIndexConfig()
        .metric(Index.Metric.COSINE)
        .quantization(Index.Quantization.FLOAT32)
        .dimensions(64)
        .capacity(1000)
        .connectivity(16)
        .build()) {

    // Add vectors (accepts float[], double[], or byte[])
    float[] vector = new float[64];
    // Fill vector elements...
    index.add(42L, vector);
}
```

### 2. Search based on Distance Threshold and Limit

You can search for nearest neighbors with a threshold filter. This runs directly via C++ HNSW or Exact (flat) search, discarding elements with distance greater than the threshold:

```java
float[] queryVec = new float[64];
long limit = 5;
float threshold = 1.0f; // maximum distance
boolean exactSearch = false; // set to true for exact flat search, false for HNSW

Index.SearchResult result = index.search(queryVec, limit, threshold, exactSearch);

if (result != null && result.keys != null) {
    for (int i = 0; i < result.keys.length; i++) {
        long key = result.keys[i];
        float distance = result.distances[i];
        System.out.println("Match #" + (i+1) + " -> Key: " + key + ", Distance: " + distance);
    }
}
```

### 3. Persistent Save and Load

To avoid keeping the index only in volatile memory, serialize it to the Android app's local internal storage:

```java
// 1. Save the index to storage
String indexPath = context.getFilesDir().getAbsolutePath() + "/index.usearch";
index.save(indexPath);

// 2. Load the index from storage in subsequent app sessions
Index loadedIndex = Index.loadFromPath(indexPath);
System.out.println("Loaded index size: " + loadedIndex.size());
System.out.println("Index dimensions: " + loadedIndex.dimensions());
System.out.println("Index memory usage: " + loadedIndex.memoryUsage() + " bytes");
```

### 4. Memory-Mapped Views (Low-RAM Optimization)

To query a large index without loading it entirely into Android process memory (reclaiming pages dynamically from disk to avoid Out-Of-Memory crashes):

```java
String indexPath = context.getFilesDir().getAbsolutePath() + "/large_index.usearch";

// Creates an immutable, memory-mapped view of the index file
Index indexView = Index.viewFromPath(indexPath);

// Perform read-only search operations
Index.SearchResult result = indexView.search(queryVec, limit, threshold, false);
```

---

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
  -DUSEARCH_USE_NUMKONG=ON

cmake --build build_android_arm64 --config Release
mkdir -p app/src/main/jniLibs/arm64-v8a
cp build_android_arm64/libusearch_jni.so app/src/main/jniLibs/arm64-v8a/
cp build_android_arm64/libnumkong.so app/src/main/jniLibs/arm64-v8a/
```

NumKong enables ARM NEON (and related) kernels through compiler ISA probes when
cross-compiling with the NDK.

Include the Java binding sources or the USearch Java JAR in the app, then call
`USearchAndroid.load()` or let `Index` load `libusearch_jni.so` automatically.
