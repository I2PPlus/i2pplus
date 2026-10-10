/*
 * Copyright 2007 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.common.reedsolomon;

/**
 * <p>This class contains utility methods for performing mathematical operations over
 * the Galois Fields. Operations use a given primitive polynomial in calculations.</p>
 *
 * <p>Throughout this package, elements of the GF are represented as an {@code int}
 * for convenience and speed (but at the cost of memory).
 * </p>
 *
 * @author Sean Owen
 * @author David Olivier
 */
public final class GenericGF {

  /**
   * Galois field for Aztec 12-bit data symbols (4096 elements).
   */
  public static final GenericGF AZTEC_DATA_12 = new GenericGF(0x1069, 4096, 1); // x^12 + x^6 + x^5 + x^3 + 1
  /**
   * Galois field for Aztec 10-bit data symbols (1024 elements).
   */
  public static final GenericGF AZTEC_DATA_10 = new GenericGF(0x409, 1024, 1); // x^10 + x^3 + 1
  /**
   * Galois field for Aztec 6-bit data symbols (64 elements).
   */
  public static final GenericGF AZTEC_DATA_6 = new GenericGF(0x43, 64, 1); // x^6 + x + 1
  /**
   * Galois field for Aztec parameter symbols (16 elements).
   */
  public static final GenericGF AZTEC_PARAM = new GenericGF(0x13, 16, 1); // x^4 + x + 1
  /**
   * Galois field for QR Code symbols (256 elements).
   */
  public static final GenericGF QR_CODE_FIELD_256 = new GenericGF(0x011D, 256, 0); // x^8 + x^4 + x^3 + x^2 + 1
  /**
   * Galois field for Data Matrix symbols (256 elements).
   */
  public static final GenericGF DATA_MATRIX_FIELD_256 = new GenericGF(0x012D, 256, 1); // x^8 + x^5 + x^3 + x^2 + 1
  /**
   * Alias for DATA_MATRIX_FIELD_256.
   */
  public static final GenericGF AZTEC_DATA_8 = DATA_MATRIX_FIELD_256;
  /**
   * Alias for AZTEC_DATA_6.
   */
  public static final GenericGF MAXICODE_FIELD_64 = AZTEC_DATA_6;

  private final int[] expTable;
  private final int[] logTable;
  private final GenericGFPoly zero;
  private final GenericGFPoly one;
  private final int size;
  private final int primitive;
  private final int generatorBase;

  /**
   * Create a representation of GF(size) using the given primitive polynomial.
   *
   * @param primitive irreducible polynomial whose coefficients are represented by
   * the bits of an int, where the least-significant bit represents the constant
   * coefficient
   * @param size the number of elements in the field, a power of two
   * @param b the factor b in the generator polynomial can be 0- or 1-based
   * (g(x) = (x+a^b)(x+a^(b+1))...(x+a^(b+2t-1))).
   * In most cases it should be 1, but for QR code it is 0.
   */
  public GenericGF(int primitive, int size, int b) {
    this.primitive = primitive;
    this.size = size;
    this.generatorBase = b;

    expTable = new int[size];
    logTable = new int[size];
    int x = 1;
    for (int i = 0; i < size; i++) {
      expTable[i] = x;
      x *= 2; // we're assuming the generator alpha is 2
      if (x >= size) {
        x ^= primitive;
        x &= size - 1;
      }
    }
    for (int i = 0; i < size - 1; i++) {
      logTable[expTable[i]] = i;
    }
    // logTable[0] == 0 but this should never be used
    zero = new GenericGFPoly(this, new int[]{0});
    one = new GenericGFPoly(this, new int[]{1});
  }

  /**
   * Get the additive identity of this field.
   *
   * @return the zero polynomial, the additive identity in GF(size)
   */
  GenericGFPoly getZero() {
    return zero;
  }

  /**
   * Get the multiplicative identity of this field.
   *
   * @return the one polynomial, the multiplicative identity in GF(size)
   */
  GenericGFPoly getOne() {
    return one;
  }

  /**
   * Build the monomial coefficient * x^degree.
   *
   * @param degree the exponent of x, not negative
   * @param coefficient the leading coefficient, whose field value is in [0, size)
   * @return the monomial, or the zero polynomial if coefficient is 0
   * @throws IllegalArgumentException if degree is negative
   */
  GenericGFPoly buildMonomial(int degree, int coefficient) {
    if (degree < 0) {
      throw new IllegalArgumentException();
    }
    if (coefficient == 0) {
      return zero;
    }
    int[] coefficients = new int[degree + 1];
    coefficients[0] = coefficient;
    return new GenericGFPoly(this, coefficients);
  }

  /**
   * Implements both addition and subtraction -- they are the same in GF(size).
   *
   * @param a the first operand, whose field value is in [0, size)
   * @param b the second operand, whose field value is in [0, size)
   * @return sum/difference of a and b
   */
  static int addOrSubtract(int a, int b) {
    return a ^ b;
  }

  /**
   * Get a field element's antilog.
   *
   * @param a the exponent, in [0, size)
   * @return 2 to the power of a in GF(size)
   */
  int exp(int a) {
    return expTable[a];
  }

  /**
   * Get a field element's log.
   *
   * @param a a nonzero field value in [1, size)
   * @return base 2 log of a in GF(size)
   * @throws IllegalArgumentException if a is 0, whose log is undefined
   */
  int log(int a) {
    if (a == 0) {
      throw new IllegalArgumentException();
    }
    return logTable[a];
  }

  /**
   * Get the multiplicative inverse of a field element.
   *
   * @param a a nonzero field value in [1, size)
   * @return multiplicative inverse of a, that is a such that a * result is 1
   * @throws ArithmeticException if a is 0, which has no inverse
   */
  int inverse(int a) {
    if (a == 0) {
      throw new ArithmeticException();
    }
    return expTable[size - logTable[a] - 1];
  }

  /**
   * Multiply two field elements.
   *
   * @param a the first factor, whose field value is in [0, size)
   * @param b the second factor, whose field value is in [0, size)
   * @return product of a and b in GF(size), 0 if either factor is 0
   */
  int multiply(int a, int b) {
    if (a == 0 || b == 0) {
      return 0;
    }
    return expTable[(logTable[a] + logTable[b]) % (size - 1)];
  }

  /**
   * Get the field's cardinality.
   *
   * @return the number of elements in this field
   */
  public int getSize() {
    return size;
  }

  /**
   * Get the integer the Reed-Solomon generator polynomial is built over.
   *
   * @return the generator base
   */
  public int getGeneratorBase() {
    return generatorBase;
  }

  /**
   * Human-readable string representation of this Galois field.
   */
  @Override
  public String toString() {
    return "GF(0x" + Integer.toHexString(primitive) + ',' + size + ')';
  }

}
