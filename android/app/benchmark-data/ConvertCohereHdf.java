import io.jhdf.HdfFile;
import io.jhdf.api.Dataset;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Converts the first 100k base and first 1k query vectors from ANN-Benchmarks HDF5 to fbin. */
public final class ConvertCohereHdf {
    private static final int DIMENSIONS = 768;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: <source.hdf5> <base.fbin> <query.fbin>");
        try (HdfFile hdf = new HdfFile(new File(args[0]))) {
            write((float[][]) rows(hdf, 0, 100_000), new File(args[1]));
            // The downloaded archive is truncated before its held-out test matrix.
            // Use the immediately following real corpus vectors as queries instead.
            write((float[][]) rows(hdf, 100_000, 1_000), new File(args[2]));
        }
    }

    private static Object rows(HdfFile hdf, int offset, int count) {
        Dataset dataset = hdf.getDatasetByPath("train");
        if (dataset == null) throw new IllegalArgumentException("Dataset missing: train");
        return dataset.getData(new long[] {offset, 0}, new int[] {count, DIMENSIONS});
    }

    private static void write(float[][] vectors, File output) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(output)) {
            ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(vectors.length).putInt(DIMENSIONS);
            stream.write(header.array());
            ByteBuffer row = ByteBuffer.allocate(DIMENSIONS * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (float[] vector : vectors) {
                row.clear();
                row.asFloatBuffer().put(vector, 0, DIMENSIONS);
                stream.write(row.array());
            }
        }
    }
}
