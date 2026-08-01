package cloud.unum.usearch.demo;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
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
import java.io.File;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import io.jhdf.HdfFile;
import io.jhdf.api.Dataset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private static final String INDEX_FILE_NAME = "index.usearch";
    private static final String KPI_LOG_TAG = "USearchKPI";
    private static final int KPI_DIMENSIONS = 768;
    private static final int KPI_QUERY_COUNT = 1_000;
    private static final int KPI_RESULT_LIMIT = 10;
    private static final long KPI_MEMORY_CAP_BYTES = 30L * 1024L * 1024L;
    private static final String KPI_BASE_FILE = "cohere-768-base.fbin";
    private static final String KPI_QUERY_FILE = "cohere-768-query.fbin";
    private static final String COHERE_ARCHIVE_URL = "https://dbyiw3u3rf9yr.cloudfront.net/corpora/vectorsearch/cohere-wikipedia-22-12-en-embeddings/documents-1m.hdf5.bz2";
    private static final String EXTRA_DOWNLOAD_KPI_DATA = "download_kpi_data";
    private static final String EXTRA_BASE_URL = "base_url";
    private static final String EXTRA_QUERY_URL = "query_url";

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
    private TextView tvKpiStatus;
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
    private Button btnClearData;
    private Button btnRunKpi;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        consoleText = findViewById(R.id.console_text);
        consoleScroll = findViewById(R.id.console_scroll);
        tvDiagnosticInfo = findViewById(R.id.tv_diagnostic_info);
        tvQueryResults = findViewById(R.id.tv_query_results);
        tvKpiStatus = findViewById(R.id.tv_kpi_status);
        
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
        btnClearData = findViewById(R.id.btn_clear_data);
        btnRunKpi = findViewById(R.id.btn_run_kpi);

        tvKpiStatus.setText("Ready. Run the KPI downloads the real Cohere 768D corpus on this device when needed.");

        btnBuildIndex.setOnClickListener(v -> handleBuildIndex());
        btnSearch.setOnClickListener(v -> handleSearch());
        btnSaveIndex.setOnClickListener(v -> handleSaveIndex());
        btnLoadIndex.setOnClickListener(v -> handleLoadIndex());
        btnClearData.setOnClickListener(v -> handleClearData());
        btnRunKpi.setOnClickListener(v -> handleRunKpi());
        setBusy(false);

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

        // This is intentionally launched only through ADB. It keeps the normal demo
        // UI untouched while ensuring every timed operation runs inside this process.
        if (getIntent().getBooleanExtra(EXTRA_DOWNLOAD_KPI_DATA, false)) {
            executor.execute(this::downloadKpiDataAndRun);
        } else if (getIntent().getBooleanExtra("run_kpi", false)) {
            executor.execute(this::runDeviceKpiBenchmark);
        }
    }

    /**
     * Downloads pre-converted little-endian fbin matrices into this app's external
     * files directory. URLs are Intent extras so a deployment can use its approved
     * object store and does not rely on a transient public dataset mirror.
     */
    private void downloadKpiDataAndRun() {
        String baseUrl = getIntent().getStringExtra(EXTRA_BASE_URL);
        String queryUrl = getIntent().getStringExtra(EXTRA_QUERY_URL);
        downloadKpiDataAndRun(baseUrl, queryUrl, getIntent().getBooleanExtra("run_kpi", true));
    }

    private void handleRunKpi() {
        setBusy(true);
        executor.execute(() -> {
            try {
                ensureKpiDataset();
                runDeviceKpiBenchmark();
            } finally {
                mainHandler.post(() -> setBusy(false));
            }
        });
    }

    private void ensureKpiDataset() {
        File datasetsDir = new File(getExternalFilesDir(null), "datasets");
        File baseFile = new File(datasetsDir, KPI_BASE_FILE);
        File queryFile = new File(datasetsDir, KPI_QUERY_FILE);
        try {
            validateFbin(baseFile, 100_000);
            validateFbin(queryFile, KPI_QUERY_COUNT);
            setKpiStatusThreadSafe("Using cached Cohere 768D FBin files.");
            return;
        } catch (IOException ignored) { }
        try {
            if (!datasetsDir.exists() && !datasetsDir.mkdirs()) throw new IOException("Cannot create " + datasetsDir);
            File archive = new File(datasetsDir, "cohere-768-source.hdf5.bz2");
            File hdf5 = new File(datasetsDir, "cohere-768-source.hdf5");
            downloadArchive(COHERE_ARCHIVE_URL, archive);
            setKpiStatusThreadSafe("Extracting the Cohere source archive…");
            decompressBzip2(archive, hdf5);
            setKpiStatusThreadSafe("Converting base and query vectors to local FBin…");
            convertHdf5ToFbin(hdf5, baseFile, queryFile);
            if (!archive.delete()) Log.w(KPI_LOG_TAG, "Retained source archive: " + archive);
            if (!hdf5.delete()) Log.w(KPI_LOG_TAG, "Retained extracted HDF5: " + hdf5);
            validateFbin(baseFile, 100_000);
            validateFbin(queryFile, KPI_QUERY_COUNT);
            setKpiStatusThreadSafe("Local FBin files ready.");
        } catch (Throwable t) {
            Log.e(KPI_LOG_TAG, "Dataset preparation failed", t);
            setKpiStatusThreadSafe("Dataset preparation failed: " + t.getMessage());
            throw new RuntimeException(t);
        }
    }

    private void downloadArchive(String sourceUrl, File destination) throws IOException {
        if (destination.isFile() && destination.length() > 0) {
            setKpiStatusThreadSafe("Using already downloaded Cohere source archive.");
            return;
        }
        File partial = new File(destination.getParentFile(), destination.getName() + ".part");
        HttpURLConnection connection = (HttpURLConnection) new URL(sourceUrl).openConnection();
        connection.setConnectTimeout(30_000); connection.setReadTimeout(30_000);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + connection.getResponseCode());
            setKpiStatusThreadSafe("Downloading the real Cohere 768D corpus on this device…");
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream()); FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[1024 * 1024]; int read; long copied = 0;
                while ((read = input.read(buffer)) != -1) { output.write(buffer, 0, read); copied += read; if (copied % (128L * 1024 * 1024) < read) setKpiStatusThreadSafe("Downloaded " + formatBytes(copied) + "…"); }
            }
            if (!partial.renameTo(destination)) throw new IOException("Could not finalize source archive");
        } finally { connection.disconnect(); }
    }

    private static void decompressBzip2(File source, File destination) throws IOException {
        try (BZip2CompressorInputStream input = new BZip2CompressorInputStream(new BufferedInputStream(new FileInputStream(source)));
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[1024 * 1024]; int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
    }

    private void convertHdf5ToFbin(File hdf5, File base, File query) throws IOException {
        try (HdfFile file = new HdfFile(hdf5)) {
            writeFbin(file.getDatasetByPath("train"), 100_000, base);
            writeFbin(file.getDatasetByPath("test"), KPI_QUERY_COUNT, query);
        }
    }

    private static void writeFbin(Dataset dataset, int rows, File destination) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(destination)) {
            ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(rows).putInt(KPI_DIMENSIONS);
            stream.write(header.array());
            ByteBuffer row = ByteBuffer.allocate(KPI_DIMENSIONS * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            int rowsPerBatch = rowsPerMemoryCap();
            for (int offset = 0; offset < rows; offset += rowsPerBatch) {
                int rowsThisBatch = Math.min(rowsPerBatch, rows - offset);
                float[][] vectors = (float[][]) dataset.getData(
                        new long[] {offset, 0}, new int[] {rowsThisBatch, KPI_DIMENSIONS});
                for (float[] vector : vectors) {
                    row.clear();
                    row.asFloatBuffer().put(vector, 0, KPI_DIMENSIONS);
                    stream.write(row.array());
                }
            }
        }
    }

    private void downloadKpiDataAndRun(String baseUrl, String queryUrl, boolean runKpi) {
        if (TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(queryUrl)) {
            logThreadSafe("KPI data download needs direct base and query .fbin URLs.");
            setKpiStatusThreadSafe("Cannot start: direct .fbin URLs are required.");
            return;
        }

        File datasetsDir = new File(getExternalFilesDir(null), "datasets");
        if (!datasetsDir.exists() && !datasetsDir.mkdirs()) {
            logThreadSafe("Cannot create dataset directory: " + datasetsDir);
            setKpiStatusThreadSafe("Cannot create the app dataset folder.");
            return;
        }
        try {
            File baseFile = new File(datasetsDir, KPI_BASE_FILE);
            File queryFile = new File(datasetsDir, KPI_QUERY_FILE);
            downloadFbin(baseUrl, baseFile, 100_000);
            downloadFbin(queryUrl, queryFile, KPI_QUERY_COUNT);
            logThreadSafe("KPI data is ready in " + datasetsDir);
            setKpiStatusThreadSafe("Dataset ready. Running the device benchmark…");
            if (runKpi) runDeviceKpiBenchmark();
        } catch (Throwable t) {
            Log.e(KPI_LOG_TAG, "KPI dataset download failed", t);
            logThreadSafe("KPI dataset download failed: " + t.getMessage());
            setKpiStatusThreadSafe("Download failed: " + t.getMessage());
        }
    }

    private void downloadFbin(String sourceUrl, File destination, int minimumRows) throws IOException {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".part");
        if (temporary.exists() && !temporary.delete())
            throw new IOException("Cannot replace partial file: " + temporary);

        HttpURLConnection connection = (HttpURLConnection) new URL(sourceUrl).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/octet-stream");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300)
                throw new IOException("HTTP " + status + " for " + sourceUrl);
            long contentLength = connection.getContentLengthLong();
            logThreadSafe("Downloading " + destination.getName() + " (" +
                    (contentLength >= 0 ? formatBytes(contentLength) : "unknown size") + ")...");
            setKpiStatusThreadSafe("Downloading " + destination.getName() + "…");

            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[1024 * 1024];
                long copied = 0;
                long lastReported = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    copied += read;
                    if (copied - lastReported >= 32L * 1024 * 1024) {
                        logThreadSafe("  downloaded " + formatBytes(copied));
                        lastReported = copied;
                    }
                }
            }
            validateFbin(temporary, minimumRows);
            if (destination.exists() && !destination.delete())
                throw new IOException("Cannot replace existing file: " + destination);
            if (!temporary.renameTo(destination))
                throw new IOException("Cannot finalize downloaded file: " + destination);
            logThreadSafe("Downloaded " + destination.getName() + " to " + destination);
            setKpiStatusThreadSafe("Downloaded " + destination.getName() + ".");
        } finally {
            connection.disconnect();
            if (temporary.exists() && !temporary.delete()) Log.w(KPI_LOG_TAG, "Could not delete " + temporary);
        }
    }

    private static void validateFbin(File file, int minimumRows) throws IOException {
        try (FileInputStream input = new FileInputStream(file); FileChannel channel = input.getChannel()) {
            if (channel.size() < 8) throw new IOException("Downloaded file is too small: " + file);
            ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            channel.read(header);
            header.flip();
            int rows = header.getInt();
            int columns = header.getInt();
            long expectedBytes = 8L + (long) rows * columns * Float.BYTES;
            if (rows < minimumRows || columns != KPI_DIMENSIONS || channel.size() != expectedBytes)
                throw new IOException("Invalid fbin (expected >= " + minimumRows + " x 768 f32): " + file);
        }
    }

    /**
     * Measures HNSW construction and one-at-a-time query latency on this Android
     * device. The files are standard little-endian .fbin matrices: uint32 rows,
     * uint32 columns, followed by row-major float32 values.
     *
     * Place them in Android/data/cloud.unum.usearch.demo/files/datasets/ as
     * cohere-768-base.fbin and cohere-768-query.fbin before launching with
     * `adb shell am start ... --ez run_kpi true`.
     */
    private void runDeviceKpiBenchmark() {
        File datasetsDir = new File(getExternalFilesDir(null), "datasets");
        File baseFile = new File(datasetsDir, KPI_BASE_FILE);
        File queryFile = new File(datasetsDir, KPI_QUERY_FILE);
        File reportFile = new File(getExternalFilesDir(null), "usearch-device-kpi.txt");

        try (FileWriter report = new FileWriter(reportFile, false);
             FbinMatrix base = FbinMatrix.open(baseFile);
             FbinMatrix queries = FbinMatrix.open(queryFile)) {
            if (base.columns != KPI_DIMENSIONS || queries.columns != KPI_DIMENSIONS)
                throw new IllegalArgumentException("Expected 768D f32 matrices; got " + base.columns + "D / " + queries.columns + "D");
            if (base.rows < 100_000 || queries.rows < KPI_QUERY_COUNT)
                throw new IllegalArgumentException("Need at least 100000 base vectors and 1000 queries");

            writeKpi(report, "device=" + android.os.Build.MODEL + ", abi=" + USearchAndroid.ABI);
            writeKpi(report, "dataset=ANN-Benchmarks cohere-768-angular, dtype=f32, metric=cos, dimensions=768");
            writeKpi(report, "memory_cap=" + formatBytes(KPI_MEMORY_CAP_BYTES) + " for JNI add/search input buffers");
            writeKpi(report, "base=" + baseFile + ", queries=" + queryFile);
            for (int count : new int[] {50_000, 100_000})
                benchmarkKpiSize(base, queries, count, report);
            writeKpi(report, "report=" + reportFile);
            setKpiStatusThreadSafe("Complete. Results saved to usearch-device-kpi.txt.");
        } catch (Throwable t) {
            Log.e(KPI_LOG_TAG, "KPI benchmark failed", t);
            logThreadSafe("KPI benchmark failed: " + t.getMessage());
            setKpiStatusThreadSafe("Benchmark failed: " + t.getMessage());
        }
    }

    private void benchmarkKpiSize(FbinMatrix base, FbinMatrix queries, int count, FileWriter report) throws IOException {
        Index benchmarkIndex = null;
        try {
            benchmarkIndex = USearchAndroid.newIndexConfig()
                    .metric(Index.Metric.COSINE)
                    .quantization(Index.Quantization.FLOAT32)
                    .dimensions(KPI_DIMENSIONS)
                    .capacity(count)
                    .connectivity(16)
                    .expansion_add(128)
                    .expansion_search(64)
                    .memoryCapBytes(KPI_MEMORY_CAP_BYTES)
                    .build();
            benchmarkIndex.reserve(count, 1, 1); // Single thread makes per-add latency meaningful.

            int rowsPerBatch = rowsPerMemoryCap();
            long[] addLatenciesNs = new long[(count + rowsPerBatch - 1) / rowsPerBatch];
            long indexStartNs = SystemClock.elapsedRealtimeNanos();
            for (int i = 0, batch = 0; i < count; i += rowsPerBatch, batch++) {
                int rowsThisBatch = Math.min(rowsPerBatch, count - i);
                long startNs = SystemClock.elapsedRealtimeNanos();
                benchmarkIndex.add(i, base.sliceRows(i, rowsThisBatch));
                addLatenciesNs[batch] = SystemClock.elapsedRealtimeNanos() - startNs;
            }
            long indexElapsedNs = SystemClock.elapsedRealtimeNanos() - indexStartNs;

            LongBuffer results = ByteBuffer.allocateDirect(KPI_RESULT_LIMIT * Long.BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asLongBuffer();
            // Warm up native dispatch and memory paths; warm-up samples are excluded.
            for (int i = 0; i < 100; i++) {
                results.clear();
                benchmarkIndex.searchInto(queries.sliceRows(i, 1), results, KPI_RESULT_LIMIT);
            }
            long[] searchLatenciesNs = new long[KPI_QUERY_COUNT];
            long searchStartNs = SystemClock.elapsedRealtimeNanos();
            for (int i = 0; i < KPI_QUERY_COUNT; i++) {
                results.clear();
                long startNs = SystemClock.elapsedRealtimeNanos();
                benchmarkIndex.searchInto(queries.sliceRows(i, 1), results, KPI_RESULT_LIMIT);
                searchLatenciesNs[i] = SystemClock.elapsedRealtimeNanos() - startNs;
            }
            long searchElapsedNs = SystemClock.elapsedRealtimeNanos() - searchStartNs;

            String result = String.format(Locale.US,
                    "vectors=%d | cap_batch=%d rows, index=%.3f s, %.0f vec/s, add_batch_p99=%.3f ms | " +
                    "search_1k=%.3f s, %.0f q/s, p50=%.3f ms, p95=%.3f ms, p99=%.3f ms | memory=%s",
                    count, rowsPerBatch, indexElapsedNs / 1e9, count / (indexElapsedNs / 1e9), percentileMs(addLatenciesNs, 99),
                    searchElapsedNs / 1e9, KPI_QUERY_COUNT / (searchElapsedNs / 1e9), percentileMs(searchLatenciesNs, 50),
                    percentileMs(searchLatenciesNs, 95), percentileMs(searchLatenciesNs, 99), formatBytes(benchmarkIndex.memoryUsage()));
            writeKpi(report, result);
        } finally {
            if (benchmarkIndex != null) benchmarkIndex.close();
        }
    }

    private static double percentileMs(long[] samplesNs, int percentile) {
        long[] sorted = samplesNs.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, index)] / 1_000_000.0;
    }

    private void writeKpi(FileWriter report, String line) throws IOException {
        Log.i(KPI_LOG_TAG, line);
        report.write(line + "\n");
        report.flush();
        logThreadSafe(line);
    }

    private static final class FbinMatrix implements AutoCloseable {
        final FileChannel channel;
        final int rows;
        final int columns;
        final FloatBuffer values;

        private FbinMatrix(FileChannel channel, int rows, int columns, FloatBuffer values) {
            this.channel = channel;
            this.rows = rows;
            this.columns = columns;
            this.values = values;
        }

        static FbinMatrix open(File file) throws IOException {
            if (!file.isFile()) throw new IOException("Missing dataset file: " + file);
            FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
            try {
                ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                channel.read(header);
                header.flip();
                int rows = header.getInt();
                int columns = header.getInt();
                long expectedBytes = 8L + (long) rows * columns * Float.BYTES;
                if (rows <= 0 || columns <= 0 || channel.size() != expectedBytes)
                    throw new IOException("Invalid .fbin matrix: " + file);
                FloatBuffer values = channel.map(FileChannel.MapMode.READ_ONLY, 8, expectedBytes - 8)
                        .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                return new FbinMatrix(channel, rows, columns, values);
            } catch (Throwable t) {
                channel.close();
                throw t;
            }
        }

        FloatBuffer sliceRows(int row, int count) {
            FloatBuffer view = values.duplicate();
            view.position(row * columns);
            view.limit((row + count) * columns);
            return view.slice();
        }

        void copyRow(int row, float[] target) {
            sliceRows(row, 1).get(target, 0, columns);
        }

        @Override public void close() throws IOException { channel.close(); }
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
        setBusy(true);
        
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

                // Reserve memory up-front (with per-thread add contexts) so the
                // parallel insert below doesn't repeatedly reallocate the graph.
                int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
                logThreadSafe(String.format(Locale.US, "Reserving capacity for %d vectors using %d threads...", cap, threads));
                index.reserve(cap, threads);

                logThreadSafe(String.format(Locale.US,
                    "Generating %d random vectors and adding to index (parallel, %d threads)...", cap, threads));

                long addStart = System.currentTimeMillis();
                addVectorsParallel(vecType, dims, cap, threads);

                long duration = System.currentTimeMillis() - addStart;
                logThreadSafe(String.format(Locale.US, "SUCCESS: Generated and inserted %d vectors in %d ms.", cap, duration));
                logThreadSafe(String.format(Locale.US, "Current Index Size: %d, Memory: %s", index.size(), formatBytes(index.memoryUsage())));
                
                updateDiagnosticsThreadSafe();
            } catch (Throwable t) {
                logThreadSafe("ERROR building index: " + t.getClass().getSimpleName() + " - " + t.getMessage());
            } finally {
                mainHandler.post(() -> setBusy(false));
            }
        });
    }

    /**
     * Inserts {@code cap} randomly generated vectors into the index using a pool of
     * worker threads. USearch {@code add()} is thread-safe once {@code reserve()} has
     * allocated enough contexts, so parallelizing across CPU cores dramatically cuts
     * build time for large datasets. Progress is logged periodically (throttled by
     * both count and wall-clock time) so the UI never appears frozen.
     */
    private void addVectorsParallel(final String vecType, final int dims, final int cap, int threads)
            throws InterruptedException {
        final java.util.concurrent.atomic.AtomicInteger inserted =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicLong lastLogTime =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        // Log roughly every 2% of progress, but no more than a handful of times per second.
        final int logEvery = Math.max(1000, cap / 50);

        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final int chunk = (cap + threads - 1) / threads;
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int startIdx = t * chunk;
                final int endIdx = Math.min(cap, startIdx + chunk);
                if (startIdx >= endIdx) {
                    break;
                }
                futures.add(pool.submit(() -> {
                    // Per-thread RNG avoids contention on the shared Random instance.
                    Random rng = new Random();
                    for (int i = startIdx; i < endIdx; i++) {
                        if (vecType.equals("i8")) {
                            byte[] vec = new byte[dims];
                            rng.nextBytes(vec);
                            index.add(i, vec);
                        } else if (vecType.equals("i4")) {
                            byte[] vec = new byte[dims];
                            for (int d = 0; d < dims; d++) {
                                vec[d] = (byte) (rng.nextInt(16) - 8); // signed 4-bit: -8..7
                            }
                            index.add(i, vec);
                        } else {
                            // f16 or f32 - both accept float[] at the Java layer
                            float[] vec = new float[dims];
                            for (int d = 0; d < dims; d++) {
                                vec[d] = rng.nextFloat() * 2.0f - 1.0f; // range [-1.0, 1.0]
                            }
                            index.add(i, vec);
                        }

                        int done = inserted.incrementAndGet();
                        if (done % logEvery == 0 || done == cap) {
                            long now = System.currentTimeMillis();
                            // Throttle UI updates to at most ~5/sec regardless of thread count.
                            if (now - lastLogTime.getAndSet(now) >= 200 || done == cap) {
                                logThreadSafe(String.format(Locale.US,
                                    "  ...added %d / %d vectors (%d%%)", done, cap, (done * 100L) / cap));
                            }
                        }
                    }
                }));
            }

            // Propagate any worker exception (e.g. OutOfMemoryError) to the caller.
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException) {
                        throw (RuntimeException) cause;
                    }
                    if (cause instanceof Error) {
                        throw (Error) cause;
                    }
                    throw new RuntimeException(cause);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void handleSearch() {
        if (index == null) {
            log("\nERROR: Index has not been created yet!");
            return;
        }

        setBusy(true);
        
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
                mainHandler.post(() -> setBusy(false));
            }
        });
    }

    private void handleSaveIndex() {
        if (index == null) {
            log("\nERROR: Index has not been created yet!");
            return;
        }

        setBusy(true);

        log("\n[Index Save]: Saving index to storage...");
        executor.execute(() -> {
            try {
                String path = indexFilePath();
                index.save(path);
                logThreadSafe("SUCCESS: Saved index to " + path);
            } catch (Throwable t) {
                logThreadSafe("ERROR saving index: " + t.getClass().getSimpleName() + " - " + t.getMessage());
            } finally {
                mainHandler.post(() -> setBusy(false));
            }
        });
    }

    private void handleLoadIndex() {
        setBusy(true);

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

                String path = indexFilePath();
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
                mainHandler.post(() -> setBusy(false));
            }
        });
    }

    private void handleClearData() {
        setBusy(true);
        log("\n[Clear Data]: Closing index and removing saved index files...");

        executor.execute(() -> {
            try {
                if (index != null) {
                    try {
                        index.close();
                    } catch (Throwable ignored) {
                    }
                    index = null;
                }

                long[] result = deleteIndexFiles(getFilesDir());
                final long freed = result[0];
                final int deleted = (int) result[1];
                mainHandler.post(() -> {
                    tvQueryResults.setText("No search executed yet.");
                    updateDiagnosticsThreadSafe();
                    if (deleted == 0) {
                        log("SUCCESS: No saved index files found on disk.");
                    } else {
                        log(String.format(Locale.US,
                            "SUCCESS: Cleared index data. Deleted %d index file(s), freed %s of disk space.",
                            deleted, formatBytes(freed)));
                    }
                });
            } catch (Throwable t) {
                logThreadSafe("ERROR clearing data: " + t.getClass().getSimpleName() + " - " + t.getMessage());
            } finally {
                mainHandler.post(() -> setBusy(false));
            }
        });
    }

    private String indexFilePath() {
        return getFilesDir().getAbsolutePath() + "/" + INDEX_FILE_NAME;
    }

    /** Deletes only `*.usearch` index files. Returns {bytesFreed, filesDeleted}. */
    private static long[] deleteIndexFiles(java.io.File dir) {
        long bytes = 0L;
        long files = 0L;
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return new long[] {0L, 0L};
        }
        java.io.File[] children = dir.listFiles();
        if (children == null) {
            return new long[] {0L, 0L};
        }
        for (java.io.File child : children) {
            if (child.isDirectory()) {
                long[] nested = deleteIndexFiles(child);
                bytes += nested[0];
                files += nested[1];
                continue;
            }
            String name = child.getName();
            if (name == null || !name.endsWith(".usearch")) {
                continue;
            }
            long size = child.length();
            if (child.delete()) {
                bytes += size;
                files++;
            }
        }
        return new long[] {bytes, files};
    }

    private void setBusy(boolean busy) {
        btnBuildIndex.setEnabled(!busy);
        btnLoadIndex.setEnabled(!busy);
        btnClearData.setEnabled(!busy);
        btnRunKpi.setEnabled(!busy);
        btnSearch.setEnabled(!busy && index != null);
        btnSaveIndex.setEnabled(!busy && index != null);
    }

    private void log(String message) {
        consoleText.append(message + "\n");
        consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void logThreadSafe(final String message) {
        mainHandler.post(() -> log(message));
    }

    private void setKpiStatus(String message) {
        tvKpiStatus.setText(message);
    }

    private void setKpiStatusThreadSafe(String message) {
        mainHandler.post(() -> setKpiStatus(message));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.US, "%.2f %cB", bytes / Math.pow(1024, exp), pre);
    }

    private static int rowsPerMemoryCap() {
        long rowBytes = (long) KPI_DIMENSIONS * Float.BYTES;
        return (int) Math.max(1L, KPI_MEMORY_CAP_BYTES / rowBytes);
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
