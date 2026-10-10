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

package com.google.zxing.qrcode.encoder;

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.decoder.Mode;
import com.google.zxing.qrcode.decoder.Version;

/**
 * QR Code representation for encoding.
 * @author satorux@google.com (Satoru Takabayashi) - creator
 * @author dswitkin@google.com (Daniel Switkin) - ported from C++
 */
public final class QRCode {

  /**
   * Number of mask patterns defined by the QR Code model.
   */
  public static final int NUM_MASK_PATTERNS = 8;

  private Mode mode;
  private ErrorCorrectionLevel ecLevel;
  private Version version;
  private int maskPattern;
  private ByteMatrix matrix;

  /**
   * Construct an empty QR Code, with no mask pattern selected yet.
   */
  public QRCode() {
    maskPattern = -1;
  }

  /**
   * Return the encoding mode.
   * @return the encoding mode, null until setMode() has been called
   */
  public Mode getMode() {
    return mode;
  }

  /**
   * Return the error correction level.
   * @return the e c level
   */
  public ErrorCorrectionLevel getECLevel() {
    return ecLevel;
  }

  /**
   * Return the symbol version.
   * @return the version, null until setVersion() has been called
   */
  public Version getVersion() {
    return version;
  }

  /**
   * Return the index of the mask applied to the symbol.
   * @return the mask pattern
   */
  public int getMaskPattern() {
    return maskPattern;
  }

  /**
   * Return the encoded module matrix.
   * @return the matrix, null until setMatrix() has been called
   */
  public ByteMatrix getMatrix() {
    return matrix;
  }

  /**
   * Render the mode, correction level, version, mask and matrix as text.
   *
   * @return the QR Code rendered for debugging
   */
  @Override
  public String toString() {
    StringBuilder result = new StringBuilder(200);
    result.append("<<\n");
    result.append(" mode: ");
    result.append(mode);
    result.append("\n ecLevel: ");
    result.append(ecLevel);
    result.append("\n version: ");
    result.append(version);
    result.append("\n maskPattern: ");
    result.append(maskPattern);
    if (matrix == null) {
      result.append("\n matrix: null\n");
    } else {
      result.append("\n matrix:\n");
      result.append(matrix);
    }
    result.append(">>\n");
    return result.toString();
  }

  /**
   * Record the encoding mode.
   * @param value the encoding mode for the data being encoded
   */
  public void setMode(Mode value) {
    mode = value;
  }

  /**
   * Record the error correction level.
   * @param value the error correction level to encode the symbol with
   */
  public void setECLevel(ErrorCorrectionLevel value) {
    ecLevel = value;
  }

  /**
   * Record the symbol version.
   * @param version the symbol version, which fixes the matrix size
   */
  public void setVersion(Version version) {
    this.version = version;
  }

  /**
   * Record the index of the mask to apply.
   * @param value the index of the mask to apply, 0 to NUM_MASK_PATTERNS - 1
   */
  public void setMaskPattern(int value) {
    maskPattern = value;
  }

  /**
   * Record the encoded module matrix.
   * @param value the encoded module matrix to emit
   */
  public void setMatrix(ByteMatrix value) {
    matrix = value;
  }

  /**
   * Test whether a mask index is one of the eight the model defines.
   * @param maskPattern the mask index to test, in range only from 0 to NUM_MASK_PATTERNS - 1
   * @return whether valid mask pattern
   */
  public static boolean isValidMaskPattern(int maskPattern) {
    return maskPattern >= 0 && maskPattern < NUM_MASK_PATTERNS;
  }

}
