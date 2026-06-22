package cloud.unum.usearch.demo;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import cloud.unum.usearch.Index;
import cloud.unum.usearch.android.USearchAndroid;

import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private Index index;
    private int currentDimensions = 64;
    private int currentCapacity = 1000;
    private String currentVectorType = "i8"; // "i8", "i4", "f16", "f32"

    private TextView consoleText;
    private ScrollView consoleScroll;
    private TextView tvDiagnosticInfo;
    private TextView tvQueryResults;
    private EditText etDimensions;
    private EditText etCapacity;
    private EditText etConnectivity;
    private EditText etLimit;
    private EditText etThreshold;
    
    private RadioGroup rgVectorType;
    private RadioGroup rgMetric;
    private RadioGroup rgSearchType;
    
    private Button btnBuildIndex;
    private Button btnSearch;
    private Button btnSaveIndex;
    private Button btnLoadIndex;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        consoleText = findViewById(R.id.console_text);
        consoleScroll = findViewById(R.id.console_scroll);
        tvDiagnosticInfo = findViewById(R.id.tv_diagnostic_info);
        tvQueryResults = findViewById(R.id.tv_query_results);
        
        etDimensions = findViewById(R.id.et_dimensions);
        etCapacity = findViewById(R.id.et_capacity);
        etConnectivity = findViewById(R.id.et_connectivity);
        etLimit = findViewById(R.id.et_limit);
        etThreshold = findViewById(R.id.et_threshold);
        
        rgVectorType = findViewById(R.id.rg_vector_type);
        rgMetric = findViewById(R.id.rg_metric);
        rgSearchType = findViewById(R.id.rg_search_type);
        
        btnBuildIndex = findViewById(R.id.btn_build_index);
        btnSearch = findViewById(R.id.btn_search);
        btnSaveIndex = findViewById(R.id.btn_save_index);
        btnLoadIndex = findViewById(R.id.btn_load_index);

        btnBuildIndex.setOnClickListener(v -> handleBuildIndex());
        btnSearch.setOnClickListener(v -> handleSearch());
        btnSaveIndex.setOnClickListener(v -> handleSaveIndex());
        btnLoadIndex.setOnClickListener(v -> handleLoadIndex());

        // Initialize USearch and display system info
        log("System: Starting application...");
        executor.execute(() -> {
            try {
                USearchAndroid.load();
                logThreadSafe("System: USearch Native library loaded successfully.");
                updateDiagnosticsThreadSafe();
            } catch (Throwable t) {
                logThreadSafe("ERROR: Failed to load USearch native library - " + t.getMessage());
            }
        });
    }

    private void updateDiagnosticsThreadSafe() {
        mainHandler.post(() -> {
            try {
                String version = USearchAndroid.version();
                String isaCompiled = TextUtils.join(", ", USearchAndroid.compiledHardwareAcceleration());
                boolean usesDynamic = Index.usesDynamicDispatch();
                String acceleration = (index != null) ? index.hardwareAcceleration() : "N/A";
                long memory = (index != null) ? index.memoryUsage() : 0;
                
                String info = String.format(Locale.US,
                    "USearch Version: %s\n" +
                    "Compiled ISA targets: %s\n" +
                    "Dynamic Dispatch: %b\n" +
                    "Device CPU ABI: %s\n" +
                    "Active Index ISA: %s\n" +
                    "Index Memory Usage: %s",
                    version, isaCompiled, usesDynamic, USearchAndroid.ABI, acceleration, formatBytes(memory));
                tvDiagnosticInfo.setText(info);
            } catch (Throwable t) {
                tvDiagnosticInfo.setText("Diagnostics error: " + t.getMessage());
            }
        });
    }

    private void handleBuildIndex() {
        btnBuildIndex.setEnabled(false);
        btnSearch.setEnabled(false);
        btnSaveIndex.setEnabled(false);
        btnLoadIndex.setEnabled(false);
        
        // Parse inputs
        String dimStr = etDimensions.getText().toString();
        String capStr = etCapacity.getText().toString();
        String connStr = etConnectivity.getText().toString();
        
        int dims = Integer.parseInt(dimStr);
        int cap = Integer.parseInt(capStr);
        int conn = Integer.parseInt(connStr);
        
        // Determine vector type and quantization
        int checkedTypeId = rgVectorType.getCheckedRadioButtonId();
        final String vecType;
        final String quantization;
        if (checkedTypeId == R.id.rb_int4) {
            vecType = "i4";
            quantization = Index.Quantization.INT8; // Quantized as i8
        } else if (checkedTypeId == R.id.rb_float16) {
            vecType = "f16";
            quantization = Index.Quantization.FLOAT16;
        } else if (checkedTypeId == R.id.rb_float32) {
            vecType = "f32";
            quantization = Index.Quantization.FLOAT32;
        } else {
            vecType = "i8";
            quantization = Index.Quantization.INT8;
        }

        // Determine Metric
        int checkedMetricId = rgMetric.getCheckedRadioButtonId();
        final String metric;
        if (checkedMetricId == R.id.rb_metric_l2) {
            metric = Index.Metric.EUCLIDEAN_SQUARED;
        } else if (checkedMetricId == R.id.rb_metric_ip) {
            metric = Index.Metric.INNER_PRODUCT;
        } else {
            metric = Index.Metric.COSINE;
        }

        log(String.format(Locale.US, "\n[Index Build]: Creating Index (Type: %s, Metric: %s, Dims: %d, Cap: %d, Connectivity: %d)...",
            vecType, metric, dims, cap, conn));

        executor.execute(() -> {
            try {
                if (index != null) {
                    index.close();
                    index = null;
                }

                long start = System.currentTimeMillis();
                index = USearchAndroid.newIndexConfig()
                        .metric(metric)
                        .quantization(quantization)
                        .dimensions(dims)
                        .capacity(cap)
                        .connectivity(conn)
                        .build();
                
                currentDimensions = dims;
                currentCapacity = cap;
                currentVectorType = vecType;

                logThreadSafe(String.format(Locale.US, "Index allocated in %d ms.", System.currentTimeMillis() - start));
                logThreadSafe(String.format(Locale.US, "Generating %d random vectors and adding to index...", cap));

                long addStart = System.currentTimeMillis();
                if (vecType.equals("i8")) {
                    for (int i = 0; i < cap; i++) {
                        byte[] vec = new byte[dims];
                        random.nextBytes(vec);
                        index.add(i, vec);
                    }
                } else if (vecType.equals("i4")) {
                    for (int i = 0; i < cap; i++) {
                        byte[] vec = new byte[dims];
                        for (int d = 0; d < dims; d++) {
                            // Signed 4-bit values: -8 to 7
                            vec[d] = (byte) (random.nextInt(16) - 8);
                        }
                        index.add(i, vec);
                    }
                } else {
                    // float16 or float32 - both accept float[] in Java layer
                    for (int i = 0; i < cap; i++) {
                        float[] vec = new float[dims];
                        for (int d = 0; d < dims; d++) {
                            vec[d] = random.nextFloat() * 2.0f - 1.0f; // range [-1.0, 1.0]
                        }
                        index.add(i, vec);
                    }
                }
                
                long duration = System.currentTimeMillis() - addStart;
                logThreadSafe(String.format(Locale.US, "SUCCESS: Generated and inserted %d vectors in %d ms.", cap, duration));
                logThreadSafe(String.format(Locale.US, "Current Index Size: %d, Memory: %s", index.size(), formatBytes(index.memoryUsage())));
                
                updateDiagnosticsThreadSafe();
            } catch (Throwable t) {
                logThreadSafe("ERROR building index: " + t.getClass().getSimpleName() + " - " + t.getMessage());
            } finally {
                mainHandler.post(() -> {
                    btnBuildIndex.setEnabled(true);
                    btnSearch.setEnabled(index != null);
                    btnSaveIndex.setEnabled(index != null);
                    btnLoadIndex.setEnabled(true);
                });
            }
        });
    }

    private void handleSearch() {
        if (index == null) {
            log("\nERROR: Index has not been created yet!");
            return;
        }

        btnSearch.setEnabled(false);
        
        // Parse constraints
        String limitStr = etLimit.getText().toString();
        String thresholdStr = etThreshold.getText().toString();
        
        final int limit = Integer.parseInt(limitStr);
        final float threshold = Float.parseFloat(thresholdStr);

        // Determine Search Type
        int checkedSearchId = rgSearchType.getCheckedRadioButtonId();
        final boolean exact = (checkedSearchId == R.id.rb_flat);
        
        final String searchAlgName = exact ? "Flat (Exact)" : "HNSW (Approx)";
        log(String.format(Locale.US, "\n[Search]: Querying using %s (Limit: %d, Threshold: %.4f)...",
            searchAlgName, limit, threshold));

        executor.execute(() -> {
            try {
                final long queryStart = System.nanoTime();
                final Index.SearchResult result;
                
                if (currentVectorType.equals("i8")) {
                    byte[] queryVec = new byte[currentDimensions];
                    random.nextBytes(queryVec);
                    result = index.search(queryVec, limit, threshold, exact);
                } else if (currentVectorType.equals("i4")) {
                    byte[] queryVec = new byte[currentDimensions];
                    for (int d = 0; d < currentDimensions; d++) {
                        queryVec[d] = (byte) (random.nextInt(16) - 8);
                    }
                    result = index.search(queryVec, limit, threshold, exact);
                } else {
                    // f16 or f32
                    float[] queryVec = new float[currentDimensions];
                    for (int d = 0; d < currentDimensions; d++) {
                        queryVec[d] = random.nextFloat() * 2.0f - 1.0f;
                    }
                    result = index.search(queryVec, limit, threshold, exact);
                }

                final long queryDurationNs = System.nanoTime() - queryStart;
                final double queryDurationMs = queryDurationNs / 1_000_000.0;
                final long indexMemoryBytes = index.memoryUsage();

                mainHandler.post(() -> {
                    if (result == null || result.keys == null || result.keys.length == 0) {
                        tvQueryResults.setText(String.format(Locale.US,
                            "Query timing: %.4f ms\n" +
                            "Index memory: %s\n" +
                            "Results: 0 matches found below threshold.",
                            queryDurationMs, formatBytes(indexMemoryBytes)));
                        log("Search results: 0 matches found.");
                    } else {
                        StringBuilder sb = new StringBuilder();
                        sb.append(String.format(Locale.US, "Query timing: %.4f ms\n", queryDurationMs));
                        sb.append(String.format(Locale.US, "Index memory: %s\n", formatBytes(indexMemoryBytes)));
                        sb.append(String.format(Locale.US, "Results (%d matches found):\n", result.keys.length));
                        
                        log(String.format(Locale.US, "Search completed in %.4f ms. Found %d matches.", queryDurationMs, result.keys.length));
                        
                        for (int i = 0; i < result.keys.length; i++) {
                            String item = String.format(Locale.US, "  #%d -> Key: %d, Distance: %.6f\n",
                                i + 1, result.keys[i], result.distances[i]);
                            sb.append(item);
                            log(item.trim());
                        }
                        tvQueryResults.setText(sb.toString());
                    }
                });

                updateDiagnosticsThreadSafe();
            } catch (Throwable t) {
                logThreadSafe("ERROR executing search: " + t.getClass().getSimpleName() + " - " + t.getMessage());
            } finally {
                mainHandler.post(() -> btnSearch.setEnabled(true));
            }
        });
    }

    private void handleSaveIndex() {
        if (index == null) {
            log("\nERROR: Index has not been created yet!");
            return;
        }

        btnSaveIndex.setEnabled(false);
        btnLoadIndex.setEnabled(false);
        btnBuildIndex.setEnabled(false);
        btnSearch.setEnabled(false);

        log("\n[Index Save]: Saving index to storage...");
        executor.execute(() -> {
            try {
                String path = getFilesDir().getAbsolutePath() + "/index.usearch";
                index.save(path);
                logThreadSafe("SUCCESS: Saved index to " + path);
            } catch (Throwable t) {
                logThreadSafe("ERROR saving index: " + t.getClass().getSimpleName() + " - " + t.getMessage());
            } finally {
                mainHandler.post(() -> {
                    btnSaveIndex.setEnabled(index != null);
                    btnLoadIndex.setEnabled(true);
                    btnBuildIndex.setEnabled(true);
                    btnSearch.setEnabled(index != null);
                });
            }
        });
    }

    private void handleLoadIndex() {
        btnSaveIndex.setEnabled(false);
        btnLoadIndex.setEnabled(false);
        btnBuildIndex.setEnabled(false);
        btnSearch.setEnabled(false);

        // Determine vector type
        int checkedTypeId = rgVectorType.getCheckedRadioButtonId();
        final String vecType;
        if (checkedTypeId == R.id.rb_int4) {
            vecType = "i4";
        } else if (checkedTypeId == R.id.rb_float16) {
            vecType = "f16";
        } else if (checkedTypeId == R.id.rb_float32) {
            vecType = "f32";
        } else {
            vecType = "i8";
        }

        log(String.format(Locale.US, "\n[Index Load]: Loading index from storage..."));
        executor.execute(() -> {
            try {
                if (index != null) {
                    index.close();
                    index = null;
                }

                String path = getFilesDir().getAbsolutePath() + "/index.usearch";
                java.io.File file = new java.io.File(path);
                if (!file.exists()) {
                    logThreadSafe("ERROR loading index: Save file not found at " + path);
                    return;
                }

                index = Index.loadFromPath(path);

                currentDimensions = (int) index.dimensions();
                currentCapacity = (int) index.capacity();
                
                // Set the current Vector Type based on index's scalar kind
                String scalarKind = index.getScalarKind();
                if ("i8".equals(scalarKind)) {
                    if (!"i4".equals(vecType)) {
                        currentVectorType = "i8";
                    } else {
                        currentVectorType = "i4";
                    }
                } else if ("f16".equals(scalarKind)) {
                    currentVectorType = "f16";
                } else if ("f32".equals(scalarKind)) {
                    currentVectorType = "f32";
                } else {
                    currentVectorType = scalarKind;
                }

                logThreadSafe("SUCCESS: Loaded index from " + path);
                logThreadSafe(String.format(Locale.US, "Loaded Index Size: %d, Memory: %s, Dimensions: %d, Scalar Kind: %s",
                    index.size(), formatBytes(index.memoryUsage()), index.dimensions(), index.getScalarKind()));

                updateDiagnosticsThreadSafe();
            } catch (Throwable t) {
                logThreadSafe("ERROR loading index: " + t.getClass().getSimpleName() + " - " + t.getMessage());
            } finally {
                mainHandler.post(() -> {
                    btnSaveIndex.setEnabled(index != null);
                    btnLoadIndex.setEnabled(true);
                    btnBuildIndex.setEnabled(true);
                    btnSearch.setEnabled(index != null);
                });
            }
        });
    }

    private void log(String message) {
        consoleText.append(message + "\n");
        consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void logThreadSafe(final String message) {
        mainHandler.post(() -> log(message));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.US, "%.2f %cB", bytes / Math.pow(1024, exp), pre);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (index != null) {
            try {
                index.close();
            } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }
}
