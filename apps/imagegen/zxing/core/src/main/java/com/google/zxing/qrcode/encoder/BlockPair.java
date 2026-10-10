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

/**
 * Represents a pair of data and error correction blocks for QR code encoding.
 * This class holds the data bytes and their corresponding error correction bytes
 * as generated during the QR code encoding process.
 */
final class BlockPair {

  private final byte[] dataBytes;
  private final byte[] errorCorrectionBytes;

  /**
   * Creates a block holding one slice of the message bytes and its error correction bytes.
   * @param data this block's slice of the message bytes, before interleaving
   * @param errorCorrection Reed-Solomon check codewords computed for this block
   */
  BlockPair(byte[] data, byte[] errorCorrection) {
    dataBytes = data;
    errorCorrectionBytes = errorCorrection;
  }

  /**
   * Returns the message bytes belonging to this block, before interleaving.
   * @return this block's slice of the message bytes
   */
  public byte[] getDataBytes() {
    return dataBytes;
  }

  /**
   * Returns the error correction bytes belonging to this block, before interleaving.
   * @return this block's Reed-Solomon check codewords
   */
  public byte[] getErrorCorrectionBytes() {
    return errorCorrectionBytes;
  }

}
