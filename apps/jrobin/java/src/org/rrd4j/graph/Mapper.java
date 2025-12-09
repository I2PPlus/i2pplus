package org.rrd4j.graph;

/**
 * Maps data coordinates to pixel coordinates for RRD graph rendering. Handles conversion between
 * time/value coordinates and screen pixel positions.
 */
class Mapper {
    private final RrdGraphDef gdef;
    private final ImageParameters im;
    private final double pixieX, pixieY;

    /**
     * Creates a new mapper for the specified graph definition and image parameters.
     *
     * @param gdef the graph definition containing scaling information
     * @param im the image parameters with dimensions and ranges
     */
    Mapper(RrdGraphDef gdef, ImageParameters im) {
        this.gdef = gdef;
        this.im = im;
        pixieX = (double) im.xsize / (double) (im.end - im.start);
        if (!gdef.logarithmic) {
            pixieY = im.ysize / (im.maxval - im.minval);
        } else {
            pixieY = im.ysize / (im.log.applyAsDouble(im.maxval) - im.log.applyAsDouble(im.minval));
        }
    }

    /**
     * Converts time coordinate to pixel x-coordinate.
     *
     * @param mytime timestamp in seconds
     * @return x pixel position
     */
    int xtr(double mytime) {
        return (int) (im.xorigin + pixieX * (mytime - im.start));
    }

    /**
     * Converts value coordinate to pixel y-coordinate. Handles both linear and logarithmic scaling
     * with rigid bounds.
     *
     * @param value data value to convert
     * @return y pixel position
     */
    int ytr(double value) {
        double yval;
        if (!gdef.logarithmic) {
            yval = im.yorigin - pixieY * (value - im.minval) + 0.5;
        } else {
            if (value < im.minval) {
                yval = im.yorigin;
            } else {
                yval =
                        im.yorigin
                                - pixieY
                                        * (im.log.applyAsDouble(value)
                                                - im.log.applyAsDouble(im.minval))
                                + 0.5;
            }
        }
        if (!gdef.rigid) {
            return (int) yval;
        } else if ((int) yval > im.yorigin) {
            return im.yorigin + 2;
        } else if ((int) yval < im.yorigin - im.ysize) {
            return im.yorigin - im.ysize - 2;
        } else {
            return (int) yval;
        }
    }
}
