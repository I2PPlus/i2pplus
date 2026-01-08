package net.i2p.i2ptunnel;

import java.util.HashSet;
import java.util.Set;

/**
 * Utility class for MIME type detection based on URL file extensions.
 * <p>
 * This class provides static methods to determine the MIME type of a resource
 * based on its file extension. It is used by I2PTunnelHTTPServer to set
 * appropriate Content-Type headers for responses and to determine which
 * security headers and cache policies should be applied.
 * </p>
 * <p>
 * The MIME type detection follows the Common MIME types specification
 * as documented at MDN:
 * https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/MIME_types/Common_types
 * </p>
 *
 * @since 0.10.0
 */
public class MimeTypeDetector {

    /** MIME types that require security headers (Referrer-Policy, Allow) */
    private static final Set<String> CUSTOM_WHITELIST_SET = new HashSet<>();

    /** MIME types that should use immutable cache control */
    private static final Set<String> IMMUTABLE_CACHE_WHITELIST_SET = new HashSet<>();

    static {
        CUSTOM_WHITELIST_SET.add("text/html");
        CUSTOM_WHITELIST_SET.add("application/xhtml+xml");
        CUSTOM_WHITELIST_SET.add("application/xml");
        CUSTOM_WHITELIST_SET.add("text/plain");
        CUSTOM_WHITELIST_SET.add("application/json");

        IMMUTABLE_CACHE_WHITELIST_SET.add("application/pdf");
        IMMUTABLE_CACHE_WHITELIST_SET.add("audio");
        IMMUTABLE_CACHE_WHITELIST_SET.add("audio/midi");
        IMMUTABLE_CACHE_WHITELIST_SET.add("audio/mpeg");
        IMMUTABLE_CACHE_WHITELIST_SET.add("audio/ogg");
        IMMUTABLE_CACHE_WHITELIST_SET.add("audio/wav");
        IMMUTABLE_CACHE_WHITELIST_SET.add("audio/webm");
        IMMUTABLE_CACHE_WHITELIST_SET.add("font");
        IMMUTABLE_CACHE_WHITELIST_SET.add("font/woff");
        IMMUTABLE_CACHE_WHITELIST_SET.add("font/woff2");
        IMMUTABLE_CACHE_WHITELIST_SET.add("image");
        IMMUTABLE_CACHE_WHITELIST_SET.add("image/apng");
        IMMUTABLE_CACHE_WHITELIST_SET.add("image/bmp");
        IMMUTABLE_CACHE_WHITELIST_SET.add("image/gif");
        IMMUTABLE_CACHE_WHITELIST_SET.add("image/jpeg");
        IMMUTABLE_CACHE_WHITELIST_SET.add("image/png");
        IMMUTABLE_CACHE_WHITELIST_SET.add("image/svg+xml");
        IMMUTABLE_CACHE_WHITELIST_SET.add("image/tiff");
        IMMUTABLE_CACHE_WHITELIST_SET.add("image/webp");
        IMMUTABLE_CACHE_WHITELIST_SET.add("image/x-icon");
        IMMUTABLE_CACHE_WHITELIST_SET.add("text/css");
        IMMUTABLE_CACHE_WHITELIST_SET.add("video");
        IMMUTABLE_CACHE_WHITELIST_SET.add("video/mp4");
        IMMUTABLE_CACHE_WHITELIST_SET.add("video/ogg");
        IMMUTABLE_CACHE_WHITELIST_SET.add("video/webm");
    }

    /**
     * Detects the MIME type based on a URL's file extension.
     * <p>
     * If the URL contains a query string, it is stripped before checking
     * the extension. If no matching extension is found or the URL is null,
     * returns "application/octet-stream" as a safe default.
     * </p>
     *
     * @param url the URL or file path to detect the MIME type for; may be null
     * @return the detected MIME type, or "application/octet-stream" if unknown
     */
    public static String detectMimeType(String url) {
        if (url == null) {
            return "application/octet-stream";
        }

        String lcUrl = url.toLowerCase();
        int queryIndex = lcUrl.indexOf("?");
        if (queryIndex != -1) {
            lcUrl = lcUrl.substring(0, queryIndex);
        }

        if (lcUrl.endsWith(".3g2")) {return "video/3gpp2";}
        else if (lcUrl.endsWith(".3gp")) {return "video/3gpp";}
        else if (lcUrl.endsWith(".aac")) {return "audio/aac";}
        else if (lcUrl.endsWith(".abw")) {return "application/x-abiword";}
        else if (lcUrl.endsWith(".arc")) {return "application/x-freearc";}
        else if (lcUrl.endsWith(".avif")) {return "image/avif";}
        else if (lcUrl.endsWith(".avi")) {return "video/x-msvideo";}
        else if (lcUrl.endsWith(".azw")) {return "application/vnd.amazon.ebook";}
        else if (lcUrl.endsWith(".bin")) {return "application/octet-stream";}
        else if (lcUrl.endsWith(".bmp")) {return "image/bmp";}
        else if (lcUrl.endsWith(".bz")) {return "application/x-bzip";}
        else if (lcUrl.endsWith(".bz2")) {return "application/x-bzip2";}
        else if (lcUrl.endsWith(".cda")) {return "application/x-cdf";}
        else if (lcUrl.endsWith(".css")) {return "text/css";}
        else if (lcUrl.endsWith(".csv")) {return "text/csv";}
        else if (lcUrl.endsWith(".doc")) {return "application/msword";}
        else if (lcUrl.endsWith(".docx")) {return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";}
        else if (lcUrl.endsWith(".eot")) {return "application/vnd.ms-fontobject";}
        else if (lcUrl.endsWith(".epub")) {return "application/epub+zip";}
        else if (lcUrl.endsWith(".gif")) {return "image/gif";}
        else if (lcUrl.endsWith(".gz")) {return "application/gzip";}
        else if (lcUrl.endsWith(".htm") || lcUrl.endsWith(".html")) {return "text/html";}
        else if (lcUrl.endsWith(".ico")) {return "image/vnd.microsoft.icon";}
        else if (lcUrl.endsWith(".ics")) {return "text/calendar";}
        else if (lcUrl.endsWith(".jar")) {return "application/java-archive";}
        else if (lcUrl.endsWith(".jpeg") || lcUrl.endsWith(".jpg")) {return "image/jpeg";}
        else if (lcUrl.endsWith(".json")) {return "application/json";}
        else if (lcUrl.endsWith(".jsonld")) {return "application/ld+json";}
        else if (lcUrl.endsWith(".js") || lcUrl.endsWith(".mjs")) {return "text/javascript";}
        else if (lcUrl.endsWith(".mid") || lcUrl.endsWith(".midi")) {return "audio/midi";}
        else if (lcUrl.endsWith(".mp3")) {return "audio/mpeg";}
        else if (lcUrl.endsWith(".mp4")) {return "video/mp4";}
        else if (lcUrl.endsWith(".mpeg")) {return "video/mpeg";}
        else if (lcUrl.endsWith(".mpkg")) {return "application/vnd.apple.installer+xml";}
        else if (lcUrl.endsWith(".ods")) {return "application/vnd.oasis.opendocument.spreadsheet";}
        else if (lcUrl.endsWith(".odt")) {return "application/vnd.oasis.opendocument.text";}
        else if (lcUrl.endsWith(".odp")) {return "application/vnd.oasis.opendocument.presentation";}
        else if (lcUrl.endsWith(".oga")) {return "audio/ogg";}
        else if (lcUrl.endsWith(".ogv")) {return "video/ogg";}
        else if (lcUrl.endsWith(".ogx")) {return "application/ogg";}
        else if (lcUrl.endsWith(".opus")) {return "audio/opus";}
        else if (lcUrl.endsWith(".otf")) {return "font/otf";}
        else if (lcUrl.endsWith(".pdf")) {return "application/pdf";}
        else if (lcUrl.endsWith(".php")) {return "application/x-httpd-php";}
        else if (lcUrl.endsWith(".png")) {return "image/png";}
        else if (lcUrl.endsWith(".ppt")) {return "application/vnd.ms-powerpoint";}
        else if (lcUrl.endsWith(".pptx")) {return "application/vnd.openxmlformats-officedocument.presentationml.presentation";}
        else if (lcUrl.endsWith(".rar")) {return "application/x-rar-compressed";}
        else if (lcUrl.endsWith(".rtf")) {return "application/rtf";}
        else if (lcUrl.endsWith(".sh")) {return "application/x-sh";}
        else if (lcUrl.endsWith(".svg")) {return "image/svg+xml";}
        else if (lcUrl.endsWith(".tar")) {return "application/x-tar";}
        else if (lcUrl.endsWith(".tif") || lcUrl.endsWith(".tiff")) {return "image/tiff";}
        else if (lcUrl.endsWith(".ts")) {return "video/mp2t";}
        else if (lcUrl.endsWith(".txt")) {return "text/plain";}
        else if (lcUrl.endsWith(".ttf")) {return "font/ttf";}
        else if (lcUrl.endsWith(".vsd")) {return "application/vnd.visio";}
        else if (lcUrl.endsWith(".wav")) {return "audio/wav";}
        else if (lcUrl.endsWith(".weba")) {return "audio/webm";}
        else if (lcUrl.endsWith(".webm")) {return "video/webm";}
        else if (lcUrl.endsWith(".woff")) {return "font/woff";}
        else if (lcUrl.endsWith(".woff2")) {return "font/woff2";}
        else if (lcUrl.endsWith(".xhtml")) {return "application/xhtml+xml";}
        else if (lcUrl.endsWith(".xls")) {return "application/vnd.ms-excel";}
        else if (lcUrl.endsWith(".xlsx")) {return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";}
        else if (lcUrl.endsWith(".xml")) {return "application/xml";}
        else if (lcUrl.endsWith(".xul")) {return "application/vnd.mozilla.xul+xml";}
        else if (lcUrl.endsWith(".zip")) {return "application/zip";}

        return "application/octet-stream";
    }

    /**
     * Checks if a MIME type requires custom security headers.
     * <p>
     * MIME types in this whitelist (text/html, application/xhtml+xml, etc.)
     * will receive additional security headers such as Referrer-Policy
     * and Allow headers.
     * </p>
     *
     * @param mimeType the MIME type to check; may be null
     * @return true if the MIME type requires security headers, false otherwise
     */
    public static boolean isCustomWhitelist(String mimeType) {
        return CUSTOM_WHITELIST_SET.contains(mimeType);
    }

    /**
     * Checks if a MIME type should use immutable cache control headers.
     * <p>
     * MIME types that match known immutable content patterns (images, fonts,
     * video, audio, etc.) will receive "Cache-Control: private, max-age=31536000, immutable"
     * to enable long-term caching.
     * </p>
     *
     * @param mimeType the MIME type to check; may be null
     * @return true if the MIME type should use immutable cache control, false otherwise
     */
    public static boolean isImmutableCache(String mimeType) {
        if (mimeType == null) {return false;}
        for (String prefix : IMMUTABLE_CACHE_WHITELIST_SET) {
            if (mimeType.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
