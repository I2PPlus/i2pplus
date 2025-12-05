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
     * @param file to record hashes of destinations that breach the threshold
     * @param threshold the threshold that needs to be breached to trigger recording
     */
    Recorder(File file, Threshold threshold) {
        this.file = file;
        this.threshold = threshold;
    }

    File getFile() {
        return file;
    }

    Threshold getThreshold() {
        return threshold;
    }
}
