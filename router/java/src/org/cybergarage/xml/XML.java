/******************************************************************
*
*	CyberXML for Java
*
*	Copyright (C) Satoshi Konno 2002-2003
*
*	File: XML.java
*
*	Revision;
*
*	01/05/03
*		- first revision.
*	12/15/03
*		- Terje Bakken
*		- Added escapeXMLChars()
*
******************************************************************/

package org.cybergarage.xml;

/**
 * Utility class for XML character escaping and unescaping operations.
 * 
 * <p>This class provides static methods to escape and unescape XML special characters
 * to ensure valid XML output and proper parsing of XML content.</p>
 * 
 * <p>The following characters are handled:</p>
 * <ul>
 *   <li>&amp; - escaped as &amp;amp;</li>
 *   <li>&lt; - escaped as &amp;lt;</li>
 *   <li>&gt; - escaped as &amp;gt;</li>
 *   <li>&quot; - escaped as &amp;quot; (when quotes are enabled)</li>
 *   <li>&apos; - escaped as &amp;apos; (when quotes are enabled)</li>
 * </ul>
 * 
 * @author Satoshi Konno
 * @since 1.0
 */
public class XML
{
	public final static String DEFAULT_CONTENT_TYPE = "text/xml; charset=\"utf-8\"";
	public final static String DEFAULT_CONTENT_LANGUAGE = "en";
	public final static String CHARSET_UTF8 = "utf-8";

	////////////////////////////////////////////////
	// escapeXMLChars
	////////////////////////////////////////////////

	/**
	 * Escapes XML special characters in the input string.
	 * 
	 * <p>This method replaces the following characters with their XML entities:</p>
	 * <ul>
	 *   <li>&amp; - always escaped as &amp;amp;</li>
	 *   <li>&lt; - always escaped as &amp;lt;</li>
	 *   <li>&gt; - always escaped as &amp;gt;</li>
	 *   <li>&apos; - escaped as &amp;apos; when quote is true</li>
	 *   <li>&quot; - escaped as &amp;quot; when quote is true</li>
	 * </ul>
	 * 
	 * @param input the string to escape, may be null
	 * @param quote whether to escape quote characters (&apos; and &quot;)
	 * @return the escaped string, or null if input is null
	 */
	private final static String escapeXMLChars(String input, boolean quote)
	{
		if (input == null)
			return null;
		StringBuffer out = new StringBuffer();
		int oldsize=input.length();
		char[] old=new char[oldsize];
		input.getChars(0,oldsize,old,0);
		int selstart = 0;
		String entity=null;
		for (int i=0;i<oldsize;i++) {
			switch (old[i]) {
			case '&': entity="&amp;"; break;
			case '<': entity="&lt;"; break;
			case '>': entity="&gt;"; break;
			case '\'': if (quote) { entity="&apos;"; break; }
			case '"': if (quote) { entity="&quot;"; break; }
			}
			if (entity != null) {
				out.append(old,selstart,i-selstart);
				out.append(entity);
				selstart=i+1;
				entity=null;
			}
		}
		if (selstart == 0)
			return input;
		out.append(old,selstart,oldsize-selstart);
		return out.toString();
	}

	/**
	 * Escapes XML special characters in the input string, including quotes.
	 * 
	 * <p>This is a convenience method that calls {@link #escapeXMLChars(String, boolean)}
	 * with quote set to true.</p>
	 * 
	 * @param input the string to escape, may be null
	 * @return the escaped string, or null if input is null
	 */
	public final static String escapeXMLChars(String input)
	{
		return escapeXMLChars(input, true);
	}

	////////////////////////////////////////////////
	// unescapeXMLChars
	////////////////////////////////////////////////

	/**
	 * Unescapes XML entities in the input string.
	 * 
	 * <p>This method replaces the following XML entities with their characters:</p>
	 * <ul>
	 *   <li>&amp;amp; - replaced with &amp;</li>
	 *   <li>&amp;lt; - replaced with &lt;</li>
	 *   <li>&amp;gt; - replaced with &gt;</li>
	 *   <li>&amp;apos; - replaced with &apos;</li>
	 *   <li>&amp;quot; - replaced with &quot;</li>
	 * </ul>
	 * 
	 * @param input string to unescape, may be null
	 * @return the unescaped string, or null if input is null
	 */
	public final static String unescapeXMLChars(String input)
	{
		if (input == null)
			return null;

		String outStr;

		outStr = input.replace("&amp;", "&");
		outStr = outStr.replace("&lt;", "<");
		outStr = outStr.replace("&gt;", ">");
		outStr = outStr.replace("&apos;", "\'");
		outStr = outStr.replace("&quot;", "\"");

		return outStr;
	}
}

