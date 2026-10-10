package net.i2p.i2ptunnel.access;

import java.io.File;

/**
 * Recorder definition for threshold breach logging.
 * <p>
 * Records destination hashes to specified file when threshold breach occurs.
 *
 * @since 0.9.40
 */
class Recorder {

    private final File file;
    private final Threshold threshold;

    /**
     * Create a recorder writing to the given file.
     *
     * @param file to record hashes of destinations that breach the threshold
     * @param threshold the threshold that needs to be breached to trigger recording
     */
    Recorder(File file, Threshold threshold) {
        this.file = file;
        this.threshold = threshold;
    }

    /**
     * Read the file that breaching destinations are recorded to.
     *
     * @return the file whose contents are re-read on each filter cycle
     */
    File getFile() {
        return file;
    }

    /**
     * Read the threshold that triggers a recording.
     *
     * @return the threshold, whose seconds bound how far back the filter looks
     */
    Threshold getThreshold() {
        return threshold;
    }
}
