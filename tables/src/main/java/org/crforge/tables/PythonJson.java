/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.tables;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Writes JSON the way the game tables have always been written: Python's {@code json.dumps} with
 * {@code ensure_ascii=False}, either compact ({@code ", "} and {@code ": "} between items) or
 * indented by a number of spaces per level. A float is written as Python's {@code repr}: the
 * shortest decimal that reads back as the same double, in fixed notation from 1e-4 up to below 1e16
 * and in exponent notation outside it.
 *
 * <p>The values are those the decoder builds: {@link Map} (written in its iteration order), {@link
 * List}, {@link String}, {@link Boolean}, {@link Long}, {@link Integer}, {@link BigInteger}, {@link
 * Double} and null.
 */
final class PythonJson {

  private PythonJson() {}

  /** The value as one line, as {@code json.dumps(value, ensure_ascii=False)} writes it. */
  static String compact(Object value) {
    StringBuilder out = new StringBuilder();
    write(out, value, -1, 0);
    return out.toString();
  }

  /**
   * The value indented, as {@code json.dumps(value, indent=indent, ensure_ascii=False)} writes it.
   */
  static String indented(Object value, int indent) {
    StringBuilder out = new StringBuilder();
    write(out, value, indent, 0);
    return out.toString();
  }

  private static void write(StringBuilder out, Object value, int indent, int depth) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String s) {
      string(out, s);
    } else if (value instanceof Boolean b) {
      out.append(b ? "true" : "false");
    } else if (value instanceof Double d) {
      out.append(floatRepr(d));
    } else if (value instanceof Long || value instanceof Integer || value instanceof BigInteger) {
      out.append(value);
    } else if (value instanceof Map<?, ?> map) {
      if (map.isEmpty()) {
        out.append("{}");
        return;
      }
      out.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : map.entrySet()) {
        separator(out, first, indent, depth + 1);
        first = false;
        string(out, (String) e.getKey());
        out.append(": ");
        write(out, e.getValue(), indent, depth + 1);
      }
      close(out, indent, depth);
      out.append('}');
    } else if (value instanceof List<?> list) {
      if (list.isEmpty()) {
        out.append("[]");
        return;
      }
      out.append('[');
      boolean first = true;
      for (Object element : list) {
        separator(out, first, indent, depth + 1);
        first = false;
        write(out, element, indent, depth + 1);
      }
      close(out, indent, depth);
      out.append(']');
    } else {
      throw new IllegalArgumentException("not a JSON value: " + value.getClass().getName());
    }
  }

  private static void separator(StringBuilder out, boolean first, int indent, int depth) {
    if (indent < 0) {
      if (!first) {
        out.append(", ");
      }
      return;
    }
    if (!first) {
      out.append(',');
    }
    out.append('\n').append(" ".repeat(indent * depth));
  }

  private static void close(StringBuilder out, int indent, int depth) {
    if (indent >= 0) {
      out.append('\n').append(" ".repeat(indent * depth));
    }
  }

  /** A string with Python's escapes: the quote, the backslash and every control character. */
  static void string(StringBuilder out, String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  /** A double as Python's {@code repr} writes it, and as {@code json.dumps} writes the specials. */
  static String floatRepr(double d) {
    if (Double.isNaN(d)) {
      return "NaN";
    }
    if (Double.isInfinite(d)) {
      return d > 0 ? "Infinity" : "-Infinity";
    }
    if (d == 0) {
      return (1 / d) < 0 ? "-0.0" : "0.0";
    }
    String sign = d < 0 ? "-" : "";
    BigDecimal shortest = shortest(Math.abs(d));
    // digits d1 d2 ... dn and decpt such that the value is 0.d1d2...dn times 10^decpt
    String digits = shortest.unscaledValue().toString();
    int decpt = digits.length() - shortest.scale();
    String body;
    if (decpt > -4 && decpt <= 16) {
      if (decpt <= 0) {
        body = "0." + "0".repeat(-decpt) + digits;
      } else if (decpt >= digits.length()) {
        body = digits + "0".repeat(decpt - digits.length()) + ".0";
      } else {
        body = digits.substring(0, decpt) + "." + digits.substring(decpt);
      }
    } else {
      int exponent = decpt - 1;
      String mantissa =
          digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
      String e = Integer.toString(Math.abs(exponent));
      body = mantissa + "e" + (exponent < 0 ? "-" : "+") + (e.length() < 2 ? "0" + e : e);
    }
    return sign + body;
  }

  /**
   * The shortest decimal that reads back as d (positive, finite), with no trailing zeros (its scale
   * may be negative); of two such decimals of the same length, the nearer to d.
   */
  private static BigDecimal shortest(double d) {
    BigDecimal exact = new BigDecimal(d);
    for (int precision = 1; precision <= 17; precision++) {
      MathContext down = new MathContext(precision, RoundingMode.FLOOR);
      MathContext up = new MathContext(precision, RoundingMode.CEILING);
      BigDecimal low = exact.round(down);
      BigDecimal high = exact.round(up);
      boolean lowReads = low.doubleValue() == d;
      boolean highReads = high.doubleValue() == d;
      BigDecimal pick = null;
      if (lowReads && highReads) {
        int cmp = exact.subtract(low).compareTo(high.subtract(exact));
        pick = cmp < 0 ? low : cmp > 0 ? high : evenLast(low, high);
      } else if (lowReads) {
        pick = low;
      } else if (highReads) {
        pick = high;
      }
      if (pick != null) {
        return pick.stripTrailingZeros();
      }
    }
    throw new IllegalStateException("no decimal of 17 digits reads back as " + d);
  }

  private static BigDecimal evenLast(BigDecimal a, BigDecimal b) {
    return a.unscaledValue().testBit(0) ? b : a;
  }
}
