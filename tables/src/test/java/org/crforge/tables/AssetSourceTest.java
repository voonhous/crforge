package org.crforge.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The asset source a setting names: the game's CDN only by name, else a URI. */
class AssetSourceTest {

  @Test
  @DisplayName("the game's asset CDN is the source only when it is named")
  void gameCdnByName() {
    assertThat(AssetSource.named(AssetSource.GAME_CDN_NAME).base()).isEqualTo(AssetSource.GAME_CDN);
    assertThat(AssetSource.named(" cdn ").base()).isEqualTo(AssetSource.GAME_CDN);
  }

  @Test
  @DisplayName("a file: URI names a local copy, read as a folder")
  void localCopy() {
    assertThat(AssetSource.named("file:///data/cdn").base())
        .isEqualTo(URI.create("file:///data/cdn/"));
  }

  @Test
  @DisplayName("a source that is no http(s) or file URI is refused")
  void otherSchemes() {
    assertThatThrownBy(() -> AssetSource.named("ftp://example.org/"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AssetSource.named("relative/folder"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
