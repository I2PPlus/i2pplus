/*
 * Created on Nov 16, 2004
 * 
 *  This file is part of susimail project, see http://susi.i2p/
 *  
 *  Copyright (C) 2004-2005  <susi23@mail.i2p>
 *
 * Licensed under the GPLv2 or later.
 *  
 * $Revision: 1.3 $
 */
package i2p.susi.webmail.encoding;

import i2p.susi.util.Buffer;
import i2p.susi.util.ReadBuffer;
import java.io.IOException;
import java.io.InputStream;
import net.i2p.data.DataHelper;

/**
 * Decode only. See encode().
 * @author susi
 */
public class EightBit extends Encoding {

	public String getName() {
		return "8bit";
	}

	/**
	 * TODO would be nice to implement this, as it is supported on the project server,
	 * but content must be CRLF terminated with a max of 998 chars per line.
	 * And you can't have leading dots either, we'd have to prevent or double-dot it.
	 * That would be expensive to check, using either a double read or
	 * pulling it all into memory.
	 * So it's prohibitive for attachments. We could do it for the message body,
	 * since it's in memory already, but that's not much of a win.
	 * ref: https://stackoverflow.com/questions/29510178/how-to-handle-1000-character-lines-in-8bit-mime
	 *
	 * @throws EncodingException always
	 */
	public String encode(byte[] in) throws EncodingException {
		throw new EncodingException("unsupported");
	}

	@Override
	public Buffer decode(byte[] in, int offset, int length) {
		return new ReadBuffer(in, offset, length);
	}

	/**
	 * @return in unchanged
	 */
	@Override
	public Buffer decode(Buffer in) {
		return in;
	}

	/**
	 * Copy in to out, unchanged
	 * @since 0.9.34
	 */
	public void decode(InputStream in, Buffer out) throws IOException {
		DataHelper.copy(in, out.getOutputStream());
		// read complete, write complete
	}
}
