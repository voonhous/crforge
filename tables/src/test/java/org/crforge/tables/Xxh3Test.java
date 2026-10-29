/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.tables;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * XXH3 128-bit against the reference implementation (xxHash 0.8, seed 0), on a byte pattern of
 * every length class the specification treats apart. The input is {@code (i * 31 + 7) & 0xff} for
 * byte {@code i}.
 */
class Xxh3Test {

  @ParameterizedTest(name = "{0} bytes")
  @CsvSource({
    "0, 6001c324468d497f, 99aa06d3014798d8",
    "1, 4c5cca45d0f4811f, 495b62073ef70ca4",
    "3, 15f7093b173d005c, 46f66cb935381565",
    "4, b987ca5d9241572a, 7fefeeffb4d0eab3",
    "8, 56bb836ceb6d4baa, 803c675a846cc6c2",
    "9, 4376673580310154, d46556872d230f22",
    "16, f853dd94614dfa07, 650fe308c566747d",
    "17, 78c349fe81b2f26c, 18217300b5132d5a",
    "33, 3b25275300c8b44e, 91a4c56ad1b91d88",
    "65, d0d1d7884590a330, 5642c5d38e6e787d",
    "97, fa4138b7dc44e45b, 0912f66857975b13",
    "128, 1e04fad9f0cacb4d, b4f87b99d2db8a51",
    "129, c51bc887976aef63, 6881633650cd8924",
    "160, f661814e66697391, c000b788df6dbbc4",
    "240, 93e173833f75ab66, de57aab31e77a2ff",
    "241, 0b3b630948ce4a00, 92b991a7192f3f08",
    "1024, 23bc880ebf0d29c6, 4c17271c906df792",
    "1025, c09fdfbc398c7d82, 70a4eb1b9691d77f",
    "5000, 559fff92c2b7f8ee, 3bf60aa89c7feeaa",
    "16384, c76b744a26488b31, 2168267102d5b431"
  })
  @DisplayName("the 128-bit hash is the reference implementation's")
  void hash128(int length, String low, String high) {
    byte[] input = new byte[length];
    for (int i = 0; i < length; i++) {
      input[i] = (byte) (i * 31 + 7);
    }
    long[] hash = Xxh3.hash128(input);
    assertThat(hash[0]).isEqualTo(Long.parseUnsignedLong(low, 16));
    assertThat(hash[1]).isEqualTo(Long.parseUnsignedLong(high, 16));
  }
}
