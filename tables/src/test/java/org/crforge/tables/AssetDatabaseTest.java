/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.tables.AssetDatabaseFixture.Asset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The asset database container and the chaos-config step's data assets, on small hand-written
 * databases (schema {@code test-2}). None of the values is the game's.
 */
class AssetDatabaseTest {

  private static final ClientSchema.AssetDatabase SCHEMA =
      ClientSchema.load("test-2").assetDatabase();

  @TempDir Path temp;

  private byte[] database(List<Asset> listed, List<Asset> others) throws IOException {
    List<String> references = new ArrayList<>();
    listed.forEach(a -> references.add(a.reference()));
    List<Asset> all = new ArrayList<>();
    all.add(
        Asset.json(
            SCHEMA.config(),
            SCHEMA.configClass(),
            "c0",
            AssetDatabaseFixture.config(SCHEMA.configList(), references)));
    all.addAll(listed);
    all.addAll(others);
    return AssetDatabaseFixture.database(temp, all);
  }

  private static Asset data(String name) {
    return Asset.json("logic/" + name, SCHEMA.dataClass(), "u-" + name, "{\"data\":{}}");
  }

  @Test
  @DisplayName("the container holds the database, checked by its sizes and its hash")
  void container() throws IOException {
    byte[] db = "not a database, only bytes".getBytes(StandardCharsets.UTF_8);
    byte[] served = AssetDatabaseFixture.container(db);
    assertThat(AssetDatabase.unpack(served)).isEqualTo(db);

    byte[] wrongHash = served.clone();
    wrongHash[20] ^= 1;
    assertThatThrownBy(() -> AssetDatabase.unpack(wrongHash))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("hash");
    byte[] wrongVersion = served.clone();
    wrongVersion[4] = 2;
    assertThatThrownBy(() -> AssetDatabase.unpack(wrongVersion))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("version 2");
    assertThatThrownBy(() -> AssetDatabase.unpack(db))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("scdb");
  }

  @Test
  @DisplayName("the listed data assets come in list order; one the database lacks is left out")
  void listOrder() throws IOException {
    Asset b = data("b");
    Asset a = data("a");
    Asset missing = data("missing");
    Asset unlisted = data("unlisted");
    List<String> references = List.of(b.reference(), missing.reference(), a.reference());
    byte[] db =
        AssetDatabaseFixture.database(
            temp,
            List.of(
                Asset.json(
                    SCHEMA.config(),
                    SCHEMA.configClass(),
                    "c0",
                    AssetDatabaseFixture.config(SCHEMA.configList(), references)),
                b,
                a,
                unlisted));
    assertThat(AssetDatabase.chaosAssets(db, SCHEMA))
        .extracting(AssetDatabase.DataAsset::path)
        .containsExactly(b.path(), a.path());
  }

  @Test
  @DisplayName("a data asset of a format the client loads nothing for is left out")
  void otherFormat() throws IOException {
    Asset kept = data("kept");
    Asset other = new Asset("logic/other", SCHEMA.dataClass(), "u-other", "{}", null, 7);
    assertThat(AssetDatabase.chaosAssets(database(List.of(other, kept), List.of()), SCHEMA))
        .extracting(AssetDatabase.DataAsset::path)
        .containsExactly(kept.path());
  }

  @Test
  @DisplayName(
      "the binary form, data outside the database, another type and a split reference refuse")
  void refusals() throws IOException {
    Asset binary =
        new Asset(
            "logic/binary", SCHEMA.dataClass(), "u-b", "{}", null, AssetDatabase.FORMAT_BINARY);
    assertThatThrownBy(
            () -> AssetDatabase.chaosAssets(database(List.of(binary), List.of()), SCHEMA))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("binary");

    Asset outside = new Asset("logic/outside", SCHEMA.dataClass(), "u-o", null, "elsewhere", 0);
    assertThatThrownBy(
            () -> AssetDatabase.chaosAssets(database(List.of(outside), List.of()), SCHEMA))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("outside");

    Asset texture = Asset.json("logic/texture", "Texture", "u-t", "{}");
    assertThatThrownBy(
            () -> AssetDatabase.chaosAssets(database(List.of(texture), List.of()), SCHEMA))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Texture");

    // listed by one asset's path and another's uuid
    Asset one = data("one");
    Asset two = data("two");
    Asset split = new Asset(one.path(), one.type(), two.uuid(), null, null, 0);
    byte[] db =
        AssetDatabaseFixture.database(
            temp,
            List.of(
                Asset.json(
                    SCHEMA.config(),
                    SCHEMA.configClass(),
                    "c0",
                    AssetDatabaseFixture.config(SCHEMA.configList(), List.of(split.reference()))),
                one,
                two));
    assertThatThrownBy(() -> AssetDatabase.chaosAssets(db, SCHEMA))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("different assets");
  }

  @Test
  @DisplayName("a database without the config refuses")
  void noConfig() throws IOException {
    byte[] db = AssetDatabaseFixture.database(temp, List.of(data("a")));
    assertThatThrownBy(() -> AssetDatabase.chaosAssets(db, SCHEMA))
        .isInstanceOf(IOException.class)
        .hasMessageContaining(SCHEMA.config());
  }
}
