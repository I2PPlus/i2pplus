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

import java.util.Arrays;

/**
 * JAVAPORT: The original code was a 2D array of ints, but since it only ever gets assigned
 * -1, 0, and 1, I'm going to use less memory and go with bytes.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 */
public final class ByteMatrix {

  /** internal 2D array of bytes */
  private final byte[][] bytes;
  /** width of the matrix */
  private final int width;
  /** height of the matrix */
  private final int height;

  /**
   * Creates a new ByteMatrix with the specified dimensions.
   * @param width the width of the matrix
   * @param height the height of the matrix
   */
  public ByteMatrix(int width, int height) {
    bytes = new byte[height][width];
    this.width = width;
    this.height = height;
  }

  /**
   * @return the height of the matrix
   */
  public int getHeight() {
    return height;
  }

  /**
   * @return the width of the matrix
   */
  public int getWidth() {
    return width;
  }

  /**
   * Gets the value at the specified coordinates.
   * @param x the x coordinate
   * @param y the y coordinate
   * @return the byte value at the specified position
   */
  public byte get(int x, int y) {
    return bytes[y][x];
  }

  /**
   * @return an internal representation as bytes, in row-major order. array[y][x] represents point (x,y)
   */
  public byte[][] getArray() {
    return bytes;
  }

  /**
   * Sets the value at the specified coordinates.
   * @param x the x coordinate
   * @param y the y coordinate
   * @param value the byte value to set
   */
  public void set(int x, int y, byte value) {
    bytes[y][x] = value;
  }

  /**
   * Sets the value at the specified coordinates using an int value.
   * @param x the x coordinate
   * @param y the y coordinate
   * @param value the int value to set (will be cast to byte)
   */
  public void set(int x, int y, int value) {
    bytes[y][x] = (byte) value;
  }

  /**
   * Sets the value at the specified coordinates using a boolean value.
   * @param x the x coordinate
   * @param y the y coordinate
   * @param value the boolean value to set (true = 1, false = 0)
   */
  public void set(int x, int y, boolean value) {
    bytes[y][x] = (byte) (value ? 1 : 0);
  }

  /**
   * Clears all cells in the matrix to the specified value.
   * @param value the byte value to set all cells to
   */
  public void clear(byte value) {
    for (byte[] aByte : bytes) {
      Arrays.fill(aByte, value);
    }
  }

  /**
   * @return a string representation of this ByteMatrix
   */
  @Override
  public String toString() {
    StringBuilder result = new StringBuilder(2 * width * height + 2);
    for (int y = 0; y < height; ++y) {
      byte[] bytesY = bytes[y];
      for (int x = 0; x < width; ++x) {
        switch (bytesY[x]) {
          case 0:
            result.append(" 0");
            break;
          case 1:
            result.append(" 1");
            break;
          default:
            result.append("  ");
            break;
        }
      }
      result.append('\n');
    }
    return result.toString();
  }

}
