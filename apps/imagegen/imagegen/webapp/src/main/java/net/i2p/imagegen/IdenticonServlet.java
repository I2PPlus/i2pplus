package net.i2p.imagegen;

import com.docuverse.identicon.IdenticonCache;
import com.docuverse.identicon.IdenticonRenderer;
import com.docuverse.identicon.IdenticonUtil;
import com.docuverse.identicon.NineBlockIdenticonRenderer2;
import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.util.ConvertToHash;
import net.i2p.util.Log;

/**
 * This servlet generates <i>identicon</i> (visual identifier) images ranging
 * from 16x16 to 1024x1024 in size.
 *
 * <h2>Supported Image Formats</h2>
 * <p>
 * Currently only PNG is supported because <code>javax.imageio</code> package
 * does not come with built-in GIF encoder and PNG is the only remaining
 * reasonable format.
 * </p>
 * <h2>Initialization Parameters:</h2>
 * <blockquote>
 * <dl>
 * <dt>version</dt>
 * <dd>bump this to invalidate cached identicons after a change that alters
 * the rendered result. It is part of the ETag. Defaults to 1.
 * (Optional)</dd>
 * <dt>cacheProvider</dt>
 * <dd>full class path to <code>IdenticonCache</code> implementation.
 * Without one, every request is rendered and nothing is cached.
 * (Optional)</dd>
 * </dl>
 * </blockquote>
 * <h2>Request Parameters</h2>
 * <blockquote>
 * <dl>
 * <dt>c</dt>
 * <dd>identicon code to render, as a number, a base32 or base64 hash, or any
 * other string. Required: a request without it is answered with 404 rather
 * than an identicon derived from the requester's IP address.
 * (Required)</dd>
 * <dt>s</dt>
 * <dd>identicon size in pixels. Defaults to 32x32. Values outside 16 to 1024
 * are clamped to that range, and a non-numeric value is ignored in favour of
 * the default. (Optional)</dd>
 * </dl>
 * </blockquote>
 *
 * @author don
 * @since 0.9.25
 */
public class IdenticonServlet extends HttpServlet {

    private static final long serialVersionUID = -3507466186902317988L;
    private static final String INIT_PARAM_VERSION = "version";
    private static final String INIT_PARAM_CACHE_PROVIDER = "cacheProvider";
    private static final String PARAM_IDENTICON_SIZE_SHORT = "s";
    private static final String PARAM_IDENTICON_CODE_SHORT = "c";
    private static final String IDENTICON_IMAGE_FORMAT = "PNG";
    private static final String IDENTICON_IMAGE_MIMETYPE = "image/png";
    private static final long DEFAULT_IDENTICON_EXPIRES_IN_MILLIS = 24 * 60 * (long) 60 * 1000;
    /** Requested size when the "s" parameter is missing or unparsable. */
    static final int DEFAULT_IDENTICON_SIZE = 32;
    /** Smallest size the renderer is asked for; smaller requests are clamped up. */
    static final int MIN_IDENTICON_SIZE = 16;
    /** Largest size the renderer is asked for; larger requests are clamped down. */
    static final int MAX_IDENTICON_SIZE = 1024;
    private int version = 1;
    private final IdenticonRenderer renderer = new NineBlockIdenticonRenderer2();
    private IdenticonCache cache;
    private long identiconExpiresInMillis = DEFAULT_IDENTICON_EXPIRES_IN_MILLIS;

    /**
     * Load configuration from servlet initialization parameters.
     */
    @Override
    public void init(ServletConfig cfg) throws ServletException {
        super.init(cfg);

        // Since identicons cache expiration is very long, version is
        // used in ETag to force identicons to be updated as needed.
        // Change version whenever rendering codes changes result in
        // visual changes.
        if (cfg.getInitParameter(INIT_PARAM_VERSION) != null) {
            try {
                this.version = Integer.parseInt(cfg
                        .getInitParameter(INIT_PARAM_VERSION));
            } catch (NumberFormatException nfe) {
                this.version = 1;
            }
        }

        String cacheProvider = cfg.getInitParameter(INIT_PARAM_CACHE_PROVIDER);
        if (cacheProvider != null) {
            try {
                Class<?> cacheClass = Class.forName(cacheProvider);
                this.cache = (IdenticonCache) cacheClass.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Resolve the requested pixel size, clamping it to the supported range.
     *
     * @param sizeParam the "s" request parameter, may be null
     * @return {@link #MIN_IDENTICON_SIZE} to {@link #MAX_IDENTICON_SIZE},
     *         {@link #DEFAULT_IDENTICON_SIZE} if sizeParam is null or not a number
     */
    static int parseSize(String sizeParam) {
        int size = DEFAULT_IDENTICON_SIZE;
        if (sizeParam == null)
            return size;
        try {
            size = Integer.parseInt(sizeParam);
        } catch (NumberFormatException nfe) { /* keep the default */ }
        if (size < MIN_IDENTICON_SIZE)
            size = MIN_IDENTICON_SIZE;
        else if (size > MAX_IDENTICON_SIZE)
            size = MAX_IDENTICON_SIZE;
        return size;
    }

    /**
     * Convert the "c" request parameter to the int the renderer works with:
     * a number is used as-is, a base32 or base64 hash is reduced to its
     * Java hashCode, and anything else is hashed as a string.
     *
     * @param codeParam the "c" request parameter, must not be null or empty
     * @return the identicon code to render
     */
    static int parseCode(String codeParam) {
        try {
            return Integer.parseInt(codeParam);
        } catch (NumberFormatException nfe) {
            Hash h = ConvertToHash.getHash(codeParam);
            if (h != null)
                return Arrays.hashCode(h.getData());
            return codeParam.hashCode();
        }
    }

    /**
     * Handle GET request: generate identicon image.
     */
    @Override
    protected void doGet(HttpServletRequest request,
            HttpServletResponse response) throws ServletException, IOException {

        if (request.getCharacterEncoding() == null)
            request.setCharacterEncoding("UTF-8");
        String codeParam = request.getParameter(PARAM_IDENTICON_CODE_SHORT);
        if (codeParam == null || codeParam.isEmpty()) {
            response.setStatus(404);
            return;
        }
        int size = parseSize(request.getParameter(PARAM_IDENTICON_SIZE_SHORT));
        // The ETag has to be derived from the same code we render, since it
        // is the cache key: deriving it from the raw parameter instead would
        // let a cached image be served for a different code.
        int code = parseCode(codeParam);

        String identiconETag = IdenticonUtil.getIdenticonETag(code, size, version);
        String requestETag = request.getHeader("If-None-Match");

        if (requestETag != null && requestETag.equals(identiconETag)) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
        } else {
            // retrieve image bytes from either cache or renderer
            byte[] imageBytes = (cache != null) ? cache.get(identiconETag) : null;
            if (imageBytes == null) {
                ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
                RenderedImage image;
                try {
                    image = renderer.render(code, size);
                } catch (Throwable t) {
                    // java.lang.NoClassDefFoundError: Could not initialize class java.awt.GraphicsEnvironment$LocalGE
                    Log log = I2PAppContext.getGlobalContext().logManager().getLog(IdenticonServlet.class);
                    log.logAlways(Log.WARN, "Identicon render failure: " + t);
                    response.setStatus(404);
                    return;
                }
                ImageIO.write(image, IDENTICON_IMAGE_FORMAT, byteOut);
                imageBytes = byteOut.toByteArray();
                if (cache != null)
                    cache.add(identiconETag, imageBytes);
            }

            // set ETag and Expires header; the code parameter is required,
            // so the rendered image never varies with the requester's IP
            response.setHeader("ETag", identiconETag);
            long expires = System.currentTimeMillis() + identiconExpiresInMillis;
            response.addDateHeader("Expires", expires);

            // return image bytes to requester
            response.setContentType(IDENTICON_IMAGE_MIMETYPE);
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Accept-Ranges", "none");
            response.setHeader("Cache-control", "max-age=2628000, immutable");
            response.setContentLength(imageBytes.length);
            response.getOutputStream().write(imageBytes);
        }
    }
}
