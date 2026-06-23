package net.i2p.imagegen;

/* contains code adapted from jrobin: */
/*******************************************************************************
 * Copyright (c) 2001-2005 Sasa Markovic and Ciaran Treanor.
 * Copyright (c) 2011 The OpenNMS Group, Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *******************************************************************************/

import com.docuverse.identicon.IdenticonCache;
import com.docuverse.identicon.IdenticonUtil;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.i2p.util.SystemVersion;

/**
 * This servlet generates QR code images.
 *
 * @author modiied from identicon
 * @since 0.9.25
 */
public class QRServlet extends HttpServlet {

    private static final long serialVersionUID = -3507466186902317988L;
    private static final String INIT_PARAM_VERSION = "version";
    private static final String INIT_PARAM_CACHE_PROVIDER = "cacheProvider";
    private static final String PARAM_IDENTICON_SIZE_SHORT = "s";
    private static final String PARAM_IDENTICON_CODE_SHORT = "c";
    private static final String PARAM_IDENTICON_TEXT_SHORT = "t";
    private static final String PARAM_FORMAT = "fmt";
    private static final String IDENTICON_IMAGE_FORMAT = "PNG";
    private static final String IDENTICON_IMAGE_MIMETYPE = "image/png";
    private static final String IDENTICON_SVG_MIMETYPE = "image/svg+xml";
    private static final long DEFAULT_IDENTICON_EXPIRES_IN_MILLIS = 24 * 60 * 60 * 1000;
    private static final String DEFAULT_FONT_NAME = SystemVersion.isWindows() ?
                                                    "Lucida Sans Typewriter" : Font.MONOSPACED;
    private static final Font DEFAULT_LARGE_FONT = new Font(DEFAULT_FONT_NAME, Font.BOLD, 16);

    private int version = 1;
    private IdenticonCache cache;
    private long identiconExpiresInMillis = DEFAULT_IDENTICON_EXPIRES_IN_MILLIS;

    private static boolean acceptsSvg(HttpServletRequest req) {
        String accept = req.getHeader("Accept");
        return accept != null && accept.contains("image/svg+xml");
    }

    private static String bitMatrixToSvg(BitMatrix matrix) {
        return bitMatrixToSvg(matrix, null, null);
    }

    private static String bitMatrixToSvg(BitMatrix matrix, String text, String textName) {
        int qrWidth = matrix.getWidth();
        int qrHeight = matrix.getHeight();
        String name = text;
        if (name == null || name.length() == 0)
            name = textName;
        if (name != null && name.length() > 0) {
            float shrink = Math.min(1.0f, 14.0f / name.length());
            int fontSize = Math.max(2, Math.round(shrink * qrWidth / 10));
            int gap = Math.max(2, fontSize);
            int contentHeight = qrHeight + gap + fontSize;
            int pad = Math.max(1, (qrWidth - contentHeight) / 2);
            if (pad < 1) pad = 1;
            int totalHeight = Math.max(qrWidth, contentHeight + 2 * pad);
            StringBuilder sb = new StringBuilder(1024);
            sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
              .append(qrWidth).append(' ').append(totalHeight).append("\">")
              .append("<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/>")
              .append("<path shape-rendering=\"crispEdges\" fill=\"#000\" d=\"");
            int maxQrY = appendQrPath(sb, matrix, qrWidth, qrHeight, pad);
            int textY = maxQrY + 1 + gap;
            sb.append("\"/><text x=\"").append(qrWidth / 2.0f).append("\" y=\"")
              .append(textY)
              .append("\" text-anchor=\"middle\" dominant-baseline=\"text-before-edge\" font-family=\"Open Sans\" font-weight=\"bold\" font-size=\"")
              .append(fontSize).append("\" fill=\"#000\">")
              .append(escapeXml(name)).append("</text></svg>");
            return sb.toString();
        } else {
            StringBuilder sb = new StringBuilder(256);
            sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
              .append(qrWidth).append(' ').append(qrHeight).append("\">")
              .append("<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/>")
              .append("<path shape-rendering=\"crispEdges\" fill=\"#000\" d=\"");
            appendQrPath(sb, matrix, qrWidth, qrHeight, 0);
            sb.append("\"/></svg>");
            return sb.toString();
        }
    }

    private static int appendQrPath(StringBuilder sb, BitMatrix matrix, int width, int height, int yOffset) {
        boolean first = true;
        int maxY = 0;
        for (int y = 0; y < height; y++) {
            int x = 0;
            while (x < width) {
                if (matrix.get(x, y)) {
                    int x1 = x;
                    while (x < width && matrix.get(x, y))
                        x++;
                    if (!first)
                        sb.append(' ');
                    else
                        first = false;
                    int yo = y + yOffset;
                    if (yo > maxY) maxY = yo;
                    sb.append('M').append(x1).append(' ').append(yo)
                      .append('h').append(x - x1).append('v').append(1)
                      .append('h').append(-(x - x1)).append('Z');
                } else {
                    x++;
                }
            }
        }
        return maxY;
    }

    private static String escapeXml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&')
                sb.append("&amp;");
            else if (c == '<')
                sb.append("&lt;");
            else if (c == '>')
                sb.append("&gt;");
            else if (c == '"')
                sb.append("&quot;");
            else if (c == '\'')
                sb.append("&apos;");
            else
                sb.append(c);
        }
        return sb.toString();
    }

    @Override
    public void init(ServletConfig cfg) throws ServletException {
        super.init(cfg);

        // Since identicons cache expiration is very long, version is
        // used in ETag to force identicons to be updated as needed.
        // Change version whenever rendering codes changes result in
        // visual changes.
        if (cfg.getInitParameter(INIT_PARAM_VERSION) != null)
            this.version = Integer.parseInt(cfg
                    .getInitParameter(INIT_PARAM_VERSION));

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

    @Override
    protected void doGet(HttpServletRequest request,
            HttpServletResponse response) throws ServletException, IOException {

        if (request.getCharacterEncoding() == null) {request.setCharacterEncoding("UTF-8");}
        String codeParam = request.getParameter(PARAM_IDENTICON_CODE_SHORT);
        boolean codeSpecified = codeParam != null && codeParam.length() > 0;
        if (!codeSpecified) {
            response.setStatus(404);
            return;
        }

        String fmt = request.getParameter(PARAM_FORMAT);
        boolean svg = "svg".equalsIgnoreCase(fmt);
        if (!svg && fmt == null)
            svg = acceptsSvg(request);
        String suffix = svg ? "-svg" : "";

        String sizeParam = request.getParameter(PARAM_IDENTICON_SIZE_SHORT);
        int size = Math.max(50, (2 * 4) + (int) (2 * 5 * Math.sqrt(codeParam.length())));
        if (sizeParam != null) {
            try {
                size = Integer.parseInt(sizeParam);
                if (size < 40) {size = 40;}
                else if (size > 1024) {size = 1024;}
            } catch (NumberFormatException nfe) { /* ignored */ }
        }

        String identiconETag = IdenticonUtil.getIdenticonETag(codeParam.hashCode(), size, version) + suffix;
        String requestETag = request.getHeader("If-None-Match");

        if (requestETag != null && requestETag.equals(identiconETag)) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
        } else {
            byte[] imageBytes = null;

            if (cache == null || (imageBytes = cache.get(identiconETag)) == null) {
                if (svg) {
                    QRCodeWriter qrcw = new QRCodeWriter();
                    BitMatrix matrix;
                    try {
                        matrix = qrcw.encode(codeParam, BarcodeFormat.QR_CODE, 0, 0);
                    } catch (WriterException we) {
                        throw new IOException("encode failed", we);
                    }
                    String text = request.getParameter(PARAM_IDENTICON_TEXT_SHORT);
                    String svgOut = bitMatrixToSvg(matrix, text, null);
                    imageBytes = svgOut.getBytes("UTF-8");
                } else {
                    ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
                    QRCodeWriter qrcw = new QRCodeWriter();
                    BitMatrix matrix;
                    try {matrix = qrcw.encode(codeParam, BarcodeFormat.QR_CODE, size, size);}
                    catch (WriterException we) {throw new IOException("encode failed", we);}
                    String text = request.getParameter(PARAM_IDENTICON_TEXT_SHORT);
                    if (text != null) {
                        // add 1 so it generates RGB instead of 1 bit,
                        // so text anti-aliasing works
                        MatrixToImageConfig cfg = new MatrixToImageConfig(MatrixToImageConfig.BLACK + 1,
                                                                          MatrixToImageConfig.WHITE);
                        BufferedImage qrImage = MatrixToImageWriter.toBufferedImage(matrix, cfg);
                        int qrPx = qrImage.getWidth();
                        // Find the last non-white row in the QR image (skip quiet zone)
                        int lastQRRow = qrPx - 1;
                        for (int y = qrPx - 1; y >= 0; y--) {
                            if (qrImage.getRGB(qrPx / 2, y) != -1) {
                                lastQRRow = y;
                                break;
                            }
                        }
                        float shrink = Math.min(1.0f, 14.0f / text.length());
                        int pts = Math.round(shrink * 16.0f * size / 160);
                        int gap = pts;
                        int contentHeight = lastQRRow + 1 + gap + pts;
                        int pad = Math.max(2, (size - contentHeight) / 2);
                        int bottomPad = Math.max(pad, pts / 3);
                        int totalHeight = Math.max(size, contentHeight + pad + bottomPad);
                        BufferedImage bi = new BufferedImage(size, totalHeight, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = bi.createGraphics();
                        g.setColor(Color.WHITE);
                        g.fillRect(0, 0, size, totalHeight);
                        g.drawImage(qrImage, 0, pad, null);
                        // anti-aliasing and hinting for the text
                        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                        Font font = new Font(DEFAULT_FONT_NAME, Font.BOLD, pts);
                        g.setFont(font);
                        g.setColor(Color.BLACK);
                        FontMetrics fm = g.getFontMetrics();
                        int x = (size - fm.stringWidth(text)) / 2;
                        int y = pad + lastQRRow + gap + fm.getAscent();
                        g.drawString(text, x, y);
                        g.dispose();
                        if (!ImageIO.write(bi, IDENTICON_IMAGE_FORMAT, byteOut))
                            throw new IOException("ImageIO.write() fail");
                    } else {
                        MatrixToImageWriter.writeToStream(matrix, IDENTICON_IMAGE_FORMAT, byteOut);
                    }
                    imageBytes = byteOut.toByteArray();
                }
                if (cache != null) {cache.add(identiconETag, imageBytes);}
            } else {
                response.setStatus(404);
                return;
            }

            // set ETag and, if code was provided, Expires header
            response.setHeader("ETag", identiconETag);
            if (codeSpecified) {
                long expires = System.currentTimeMillis() + identiconExpiresInMillis;
                response.addDateHeader("Expires", expires);
            }

            // return image bytes to requester
            response.setContentType(svg ? IDENTICON_SVG_MIMETYPE : IDENTICON_IMAGE_MIMETYPE);
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Accept-Ranges", "none");
            response.setHeader("Cache-control", "max-age=2628000, immutable");
            response.setContentLength(imageBytes.length);
            response.getOutputStream().write(imageBytes);
        }
    }
}
