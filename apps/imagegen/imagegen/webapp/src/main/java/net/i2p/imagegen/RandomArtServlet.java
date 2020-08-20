package net.i2p.imagegen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.docuverse.identicon.IdenticonUtil;

import net.i2p.data.Hash;
import net.i2p.util.ConvertToHash;

/**
 * This servlet generates random art (visual identifier) images.
 *
 * @author modiied from identicon
 * @since 0.9.25
 */
public class RandomArtServlet extends HttpServlet {

	private static final long serialVersionUID = -3507466186902317988L;
	private static final String PARAM_IDENTICON_CODE_SHORT = "c";
	private static final String PARAM_IDENTICON_MODE_SHORT = "m";
	private static final long DEFAULT_IDENTICON_EXPIRES_IN_MILLIS = 24 * 60 * 60 * 1000;
	private int version = 1;
	private long identiconExpiresInMillis = DEFAULT_IDENTICON_EXPIRES_IN_MILLIS;

	@Override
	protected void doGet(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		if (request.getCharacterEncoding() == null)
			request.setCharacterEncoding("UTF-8");
		String codeParam = request.getParameter(PARAM_IDENTICON_CODE_SHORT);
		boolean codeSpecified = codeParam != null && codeParam.length() > 0;
		if (!codeSpecified) {
			response.setStatus(403);
			return;
                }
		String modeParam = request.getParameter(PARAM_IDENTICON_MODE_SHORT);
		boolean html = modeParam == null || modeParam.startsWith("h");
		String identiconETag = IdenticonUtil.getIdenticonETag(codeParam.hashCode(), 0,
				version);
		String requestETag = request.getHeader("If-None-Match");

		if (requestETag != null && requestETag.equals(identiconETag)) {
			response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
		} else {
			Hash h = ConvertToHash.getHash(codeParam);
			if (h == null) {
				response.setStatus(403);
			} else {
				StringBuilder buf = new StringBuilder(512);
				if (html) {
					response.setContentType("text/html");
					buf.append("<!DOCTYPE html>\n<html>\n<head>\n<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\">");
					buf.append("<style type=\"text/css\">html,body{margin:0;padding:0;min-height:100%;overflow:hidden}");
					buf.append("#container{width:100%;height:100%;display:table;position:absolute;top:calc(50% - 192px);text-align:center}");
					buf.append("span{padding:2px;display:inline-block;line-height:24px;vertical-align:middle;text-align:center;");
					buf.append("font-size:24pt;border:1px solid #ccc;width:24px;height:24px}.spacer{background:#e8e8e8}#title{display:none}");
					buf.append("</style>\n</head>\n<body>\n");
				} else {
					response.setContentType("text/plain");
					response.setCharacterEncoding("UTF-8");
				}
				response.setHeader("X-Content-Type-Options", "nosniff");
				response.setHeader("Accept-Ranges", "none");
				buf.append(RandomArt.gnutls_key_fingerprint_randomart(h.getData(), "SHA", 256, "", true, html));
				if (html)
					buf.append("\n</body>\n</html>");

				// set ETag and, if code was provided, Expires header
				response.setHeader("ETag", identiconETag);
				if (codeSpecified) {
					long expires = System.currentTimeMillis()
							+ identiconExpiresInMillis;
					response.addDateHeader("Expires", expires);
				}

				// return image bytes to requester
				byte[] imageBytes = buf.toString().getBytes("UTF-8");
				response.setContentLength(imageBytes.length);
				response.getOutputStream().write(imageBytes);
			}
		}
	}
}
