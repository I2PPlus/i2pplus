package org.rrd4j.graph;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;

/**
 * Image worker implementation that creates buffered images for RRD graphs. Provides functionality
 * to create, manipulate, and save graph images in various formats.
 */
class BufferedImageWorker extends ImageWorker {

    /**
     * Builder class for creating BufferedImageWorker instances with configurable parameters.
     * Supports fluent API for setting image properties, writer, and output format.
     */
    static class Builder {
        private int width = 1;
        private int height = 1;
        private RrdGraphDef gdef;
        private ImageWriter writer;
        private ImageWriteParam imageWriteParam;

        BufferedImageWorker build() {
            return new BufferedImageWorker(this);
        }

        private ImageWriteParam getImageParams() {
            ImageWriteParam iwp = writer.getDefaultWriteParam();
            ImageWriterSpi imgProvider = writer.getOriginatingProvider();
            // If lossy compression, use the quality
            if (!imgProvider.isFormatLossless()) {
                iwp.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                iwp.setCompressionQuality(gdef.imageQuality);
            }

            if (iwp.canWriteProgressive()) {
                iwp.setProgressiveMode(
                        gdef.interlaced
                                ? ImageWriteParam.MODE_DEFAULT
                                : ImageWriteParam.MODE_DISABLED);
            }
            return iwp;
        }

        /**
         * Sets the width of the image.
         *
         * @param width The width in pixels.
         * @return This builder for method chaining.
         */
        public Builder setWidth(int width) {
            this.width = width;
            return this;
        }

        /**
         * Sets the height of the image.
         *
         * @param height The height in pixels.
         * @return This builder for method chaining.
         */
        public Builder setHeight(int height) {
            this.height = height;
            return this;
        }

        /**
         * Sets the graph definition for this worker.
         *
         * @param gdef The RRD graph definition.
         * @return This builder for method chaining.
         */
        public Builder setGdef(RrdGraphDef gdef) {
            this.gdef = gdef;
            if (this.writer == null) {
                this.writer = ImageIO.getImageWritersByFormatName(gdef.imageFormat).next();
            }
            if (this.imageWriteParam == null) {
                this.imageWriteParam = getImageParams();
            }
            return this;
        }

        /**
         * Sets the image writer for encoding the image.
         *
         * @param writer The image writer to use.
         * @return This builder for method chaining.
         */
        public Builder setWriter(ImageWriter writer) {
            this.writer = writer;
            if (this.imageWriteParam == null) {
                this.imageWriteParam = getImageParams();
            }
            return this;
        }

        /**
         * Sets the image write parameters for encoding.
         *
         * @param imageWriteParam The image write parameters.
         * @return This builder for method chaining.
         */
        public Builder setImageWriteParam(ImageWriteParam imageWriteParam) {
            this.imageWriteParam = imageWriteParam;
            return this;
        }
    }

    /**
     * Creates a new builder for BufferedImageWorker.
     *
     * @return A new builder instance.
     */
    public static Builder getBuilder() {
        return new Builder();
    }

    private BufferedImage img;
    private int imgWidth;
    private int imgHeight;
    private AffineTransform initialAffineTransform;
    private final ImageWriter writer;
    private final ImageWriteParam iwp;

    private BufferedImageWorker(Builder builder) {
        this.imgHeight = builder.height;
        this.imgWidth = builder.width;
        this.writer = builder.writer;
        this.iwp = builder.imageWriteParam;
        resize(imgWidth, imgHeight);
    }

    /*
     * Not for use, only for tests
     */
    BufferedImageWorker(int width, int height) {
        this.imgHeight = height;
        this.imgWidth = width;
        this.writer = ImageIO.getImageWritersByFormatName("png").next();
        this.iwp = writer.getDefaultWriteParam();
        resize(imgWidth, imgHeight);
    }

    /**
     * Resizes the worker to the specified dimensions.
     *
     * @param width The new width in pixels.
     * @param height The new height in pixels.
     */
    void resize(int width, int height) {
        imgWidth = width;
        imgHeight = height;
        img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        Graphics2D g2d = img.createGraphics();
        setG2d(g2d);
        initialAffineTransform = g2d.getTransform();

        setAntiAliasing(false);
        setTextAntiAliasing(false);

        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    protected void reset(Graphics2D g2d) {
        g2d.setTransform(initialAffineTransform);
        g2d.setClip(0, 0, imgWidth, imgHeight);
    }

    /**
     * Creates and writes the image to the specified output stream.
     *
     * @param stream The output stream to write the image to.
     * @throws java.io.IOException If an I/O error occurs during writing.
     */
    void makeImage(OutputStream stream) throws IOException {
        BufferedImage outputImage = img;

        ImageWriterSpi imgProvider = writer.getOriginatingProvider();

        img.coerceData(false);

        // Some format can't manage 16M colors images
        // JPEG don't like transparency
        if (!imgProvider.canEncodeImage(outputImage)
                || "image/jpeg".equalsIgnoreCase(imgProvider.getMIMETypes()[0])) {
            int w = img.getWidth();
            int h = img.getHeight();
            outputImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            outputImage.getGraphics().drawImage(img, 0, 0, w, h, null);
            if (!imgProvider.canEncodeImage(outputImage)) {
                throw new IllegalArgumentException("Invalid image type");
            }
        }

        if (!imgProvider.canEncodeImage(outputImage)) {
            throw new IllegalArgumentException("Invalid image type");
        }

        try (ImageOutputStream imageStream = ImageIO.createImageOutputStream(stream)) {
            writer.setOutput(imageStream);
            writer.write(null, new IIOImage(outputImage, null, null), iwp);
            imageStream.flush();
        } catch (IOException e) {
            writer.abort();
            throw e;
        } finally {
            writer.dispose();
        }
    }
}
