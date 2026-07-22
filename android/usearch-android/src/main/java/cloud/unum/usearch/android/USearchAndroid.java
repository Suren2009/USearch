package cloud.unum.usearch.android;

import cloud.unum.usearch.Index;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android entry point for the USearch JNI binding.
 *
 * <p>The Android AAR packages {@code libusearch_jni.so} and NumKong's
 * {@code libnumkong.so} under an ABI-specific {@code jni/} directory. Calling
 * {@link #load()} explicitly makes failures easier to diagnose before constructing
 * an {@link Index}.
 */
public final class USearchAndroid {

    /**
     * ABI built by this Android wrapper module.
     */
    public static final String ABI = "arm64-v8a";

    /**
     * Native library name without Android's {@code lib} prefix or {@code .so} suffix.
     */
    public static final String LIBRARY_NAME = "usearch_jni";

    /**
     * NumKong vector/SIMD backend packaged alongside the JNI library.
     */
    public static final String VECTOR_LIBRARY_NAME = "numkong";

    private static final AtomicBoolean loaded = new AtomicBoolean(false);

    private USearchAndroid() {
    }

    /**
     * Loads NumKong then the packaged JNI library from the Android app or AAR.
     */
    public static void load() {
        if (!loaded.compareAndSet(false, true)) {
            return;
        }

        try {
            // Load the vector backend first so libusearch_jni.so can resolve it.
            System.loadLibrary(VECTOR_LIBRARY_NAME);
            System.loadLibrary(LIBRARY_NAME);
        } catch (RuntimeException | Error e) {
            loaded.set(false);
            throw e;
        }
    }

    /**
     * Creates a USearch index config after loading the Android JNI library.
     *
     * @return configurable USearch index builder
     */
    public static Index.Config newIndexConfig() {
        load();
        return new Index.Config();
    }

    /**
     * Creates a float32 USearch index with the default metric and provided dimensions.
     *
     * @param dimensions vector dimensions
     * @return configured USearch index
     */
    public static Index newIndex(long dimensions) {
        return newIndexConfig().dimensions(dimensions).build();
    }

    /**
     * Returns the bundled USearch native library version.
     *
     * @return version string
     */
    public static String version() {
        load();
        return Index.version();
    }

    /**
     * Returns SIMD targets compiled into the bundled Android library.
     *
     * @return compiled hardware acceleration names
     */
    public static String[] compiledHardwareAcceleration() {
        load();
        return Index.hardwareAccelerationCompiled();
    }
}
