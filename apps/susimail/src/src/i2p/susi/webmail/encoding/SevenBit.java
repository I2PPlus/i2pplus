// License: GPLv2+. See docs/LICENSES.md
package i2p.susi.webmail.encoding;

import i2p.susi.util.Buffer;
import i2p.susi.util.ReadBuffer;
import java.io.IOException;
import java.io.InputStream;
import net.i2p.data.DataHelper;

/**
 * Decode only.
 */
public class SevenBit extends Encoding {

	/**
	 * A bare instance is ready to decode; the only inherited state is the logger that
	 * Encoding's constructor sets up from the global context. encode() always rejects.
	 */
	public SevenBit() {}

	@Override
	public String getName() {
		return "7bit";
	}

	/**
	 * Verify the bytes are 7-bit clean and return them as text.
	 *
	 * @throws EncodingException always; 7bit decodes but has no encoder
	 */
	public String encode(byte[] in) throws EncodingException {
		throw new EncodingException("unsupported");
	}

	/**
	 * Pass the bytes through after checking they are 7-bit.
	 *
	 * @throws DecodingException on illegal characters
	 */
	@Override
	public Buffer decode(byte[] in, int offset, int length)
			throws DecodingException {
		int backupLength = length;
		int backupOffset = offset;
		while( length-- > 0 ) {
			byte b = in[offset++];
			if( b >= 32 && b < 127 )
				continue;
			if( b == '\t' )
				continue;
			if( b == '\r' || b == '\n' )
				continue;
			throw new DecodingException( "No 8 bit data allowed in 7 bit encoding (" + b + ')' );
		}
		return new ReadBuffer(in, backupOffset, backupLength);
	}

	/**
	 * We don't do any 8-bit checks like we do for decode(byte[]).
	 * @return the buffer unchanged, without the 8-bit check decode(byte[]) makes
	 */
	@Override
	public Buffer decode(Buffer in) {
		return in;
	}

	/**
	 * Copy in to out, unchanged
	 * We don't do any 8-bit checks like we do for decode(byte[]).
	 * @since 0.9.34
	 */
	public void decode(InputStream in, Buffer out) throws IOException {
		DataHelper.copy(in, out.getOutputStream());
		// read complete, write complete
	}
}
