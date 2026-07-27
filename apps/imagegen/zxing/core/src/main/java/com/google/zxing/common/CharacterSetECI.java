/*
 * Copyright 2008 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.common;

import com.google.zxing.FormatException;
import java.util.HashMap;
import java.util.Map;

/**
 * Encapsulates a Character Set ECI, according to "Extended Channel Interpretations" 5.3.1.1
 * of ISO 18004.
 *
 * @author Sean Owen
 */
public enum CharacterSetECI {

  // Enum name is a Java encoding valid for java.lang and java.io
  /** Cp437 (0, 2) */
  Cp437(new int[]{0,2}),
  /** ISO-8859-1 (1, 3) */
  ISO8859_1(new int[]{1,3}, "ISO-8859-1"),
  /** ISO-8859-2 (4) */
  ISO8859_2(4, "ISO-8859-2"),
  /** ISO-8859-3 (5) */
  ISO8859_3(5, "ISO-8859-3"),
  /** ISO-8859-4 (6) */
  ISO8859_4(6, "ISO-8859-4"),
  /** ISO-8859-5 (7) */
  ISO8859_5(7, "ISO-8859-5"),
  /** ISO-8859-6 (8) */
  ISO8859_6(8, "ISO-8859-6"),
  /** ISO-8859-7 (9) */
  ISO8859_7(9, "ISO-8859-7"),
  /** ISO-8859-8 (10) */
  ISO8859_8(10, "ISO-8859-8"),
  /** ISO-8859-9 (11) */
  ISO8859_9(11, "ISO-8859-9"),
  /** ISO-8859-10 (12) */
  ISO8859_10(12, "ISO-8859-10"),
  /** ISO-8859-11 (13) */
  ISO8859_11(13, "ISO-8859-11"),
  /** ISO-8859-13 (15) */
  ISO8859_13(15, "ISO-8859-13"),
  /** ISO-8859-14 (16) */
  ISO8859_14(16, "ISO-8859-14"),
  /** ISO-8859-15 (17) */
  ISO8859_15(17, "ISO-8859-15"),
  /** ISO-8859-16 (18) */
  ISO8859_16(18, "ISO-8859-16"),
  /** Shift_JIS (20) */
  SJIS(20, "Shift_JIS"),
  /** windows-1250 (21) */
  Cp1250(21, "windows-1250"),
  /** windows-1251 (22) */
  Cp1251(22, "windows-1251"),
  /** windows-1252 (23) */
  Cp1252(23, "windows-1252"),
  /** windows-1256 (24) */
  Cp1256(24, "windows-1256"),
  /** UTF-16BE / UnicodeBig (25) */
  UnicodeBigUnmarked(25, "UTF-16BE", "UnicodeBig"),
  /** UTF-8 (26) */
  UTF8(26, "UTF-8"),
  /** US-ASCII (27, 170) */
  ASCII(new int[] {27, 170}, "US-ASCII"),
  /** Big5 (28) */
  Big5(28),
  /** GB2312 / EUC_CN / GBK (29) */
  GB18030(29, "GB2312", "EUC_CN", "GBK"),
  /** EUC-KR (30) */
  EUC_KR(30, "EUC-KR");

  private static final Map<Integer,CharacterSetECI> VALUE_TO_ECI = new HashMap<>();
  private static final Map<String,CharacterSetECI> NAME_TO_ECI = new HashMap<>();
  static {
    for (CharacterSetECI eci : values()) {
      for (int value : eci.values) {
        VALUE_TO_ECI.put(value, eci);
      }
      NAME_TO_ECI.put(eci.name(), eci);
      for (String name : eci.otherEncodingNames) {
        NAME_TO_ECI.put(name, eci);
      }
    }
  }

  private final int[] values;
  private final String[] otherEncodingNames;

  CharacterSetECI(int value) {
    this(new int[] {value});
  }

  CharacterSetECI(int value, String... otherEncodingNames) {
    this.values = new int[] {value};
    this.otherEncodingNames = otherEncodingNames;
  }

  CharacterSetECI(int[] values, String... otherEncodingNames) {
    this.values = values;
    this.otherEncodingNames = otherEncodingNames;
  }

  /**
   * Get the first ECI value.
   *
   * @return the first ECI value
   */
  public int getValue() {
    return values[0];
  }

  /**
   * Get CharacterSetECI by value.
   *
   * @param value character set ECI value
   * @return {@code CharacterSetECI} representing ECI of given value, or null if it is legal but
   *   unsupported
   * @throws FormatException if ECI value is invalid
   */
  public static CharacterSetECI getCharacterSetECIByValue(int value) throws FormatException {
    if (value < 0 || value >= 900) {
      throw FormatException.getFormatInstance();
    }
    return VALUE_TO_ECI.get(value);
  }

  /**
   * Get CharacterSetECI by name.
   *
   * @param name character set ECI encoding name
   * @return CharacterSetECI representing ECI for character encoding, or null if it is legal
   *   but unsupported
   */
  public static CharacterSetECI getCharacterSetECIByName(String name) {
    return NAME_TO_ECI.get(name);
  }

}
