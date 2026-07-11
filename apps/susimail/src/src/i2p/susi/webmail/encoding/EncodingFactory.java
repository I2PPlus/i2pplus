// License: GPLv2+. See docs/LICENSES.md
package i2p.susi.webmail.encoding;


import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Manager class to handle content transfer encodings.
 * @author susi
 */
public class EncodingFactory {

	private static final String DEFAULT_ENCODINGS = "i2p.susi.webmail.encoding.HeaderLine;i2p.susi.webmail.encoding.QuotedPrintable;i2p.susi.webmail.encoding.Base64;i2p.susi.webmail.encoding.SevenBit;i2p.susi.webmail.encoding.EightBit;i2p.susi.webmail.encoding.HTML";

	private static final Map<String, Encoding> encodings;

	static {
		encodings = new HashMap<>();
		String list = DEFAULT_ENCODINGS;
		if( list != null ) {
			String[] classNames = list.split( ";" );
			for( int i = 0; i < classNames.length; i++ ) {
				try {
					Class<?> c = Class.forName( classNames[i] );
					Encoding e = (Encoding) (c.getDeclaredConstructor().newInstance());
					encodings.put( e.getName(), e );
				}
				catch (Exception e) {
					Log log = I2PAppContext.getGlobalContext().logManager().getLog(EncodingFactory.class);
					log.error("Error loading class '" + classNames[i] + "'", e);
				}
			}
		}
	}

	/**
	 * Retrieve instance of an encoder for a supported encoding (or null).
	 *
	 * @param name name of encoding (e.g. quoted-printable)
	 *
	 * @return Encoder instance
	 */
	public static Encoding getEncoding( String name )
	{
		return name != null && !name.isEmpty() ? encodings.get( name ) : null;
	}
	/**
	 * Returns list of available encodings;
	 *
	 * @return List of encodings
	 */
	public static Set<String> availableEncodings()
	{
		return encodings.keySet();
	}
}
