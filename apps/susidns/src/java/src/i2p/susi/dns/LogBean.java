/*
 * Created on Sep 02, 2005
 *
 *  This file is part of susidns project, see http://susi.i2p/
 *
 *  Copyright (C) 2005 <susi23@mail.i2p>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 * $Revision: 1.3 $
 */

package i2p.susi.dns;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.i2p.data.DataHelper;
import net.i2p.util.PortMapper;
import net.i2p.util.SecureFileOutputStream;

public class LogBean extends BaseBean
{
	private String logName, logged;
	private static final String LOG_FILE = "log.txt";
	public String getLogName()
	{
		loadConfig();
		logName = logFile().toString();
		return logName;
	}

	/**
	 * @since 0.9.35
	 */
	private File logFile() {
		return new File(addressbookDir(), LOG_FILE);
	}

	private void reloadLog() {
		synchronized(LogBean.class) {
			locked_reloadLog();
		}
	}

	private void locked_reloadLog()
	{
		File log = logFile();
		if(log.isFile()) {
			StringBuilder buf = new StringBuilder();
			BufferedReader br = null;
			try {
				br = new BufferedReader(new InputStreamReader(new FileInputStream(log), "UTF-8"));
				String line;
				while( ( line = br.readLine() ) != null ) {
					buf.append( line );
					buf.append( "\n" );
				}
				logged = buf.toString();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} finally {
				if (br != null)
					try { br.close(); } catch (IOException ioe) {}
			}
		} else {
			logged = LOG_FILE;
		}
	}

	public String getMessages() {
		String message = "";
		if( action != null ) {
					if (logged != null && logged.length() > 2)
					reloadLog();
					message = _t("Subscription log reloaded.");
		}
		if( message.length() > 0 )
			message = "<p class=\"messages\">" + message + "</p>";
		return message;
		}

	public void setLogged(String logged) {
		// will come from form with \r\n line endings
		this.logged = DataHelper.stripHTML(logged);
	}

	public String getLogged()
	{
		if( logged != null )
			return logged;
		reloadLog();
		return logged;
	}
}