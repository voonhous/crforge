package org.crforge.tables;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Where the game's files are read from: a base URI laid out as the game's asset CDN serves them,
 * {@code <base>/<content sha>/fingerprint.json} and {@code <base>/<content sha>/<path>}. By default
 * that is the game's asset CDN itself; a {@code file:} URI names a local copy laid out the same
 * way, such as the {@code cdn/} folder of the game data repository.
 */
public final class AssetSource {

  /** The game's asset CDN. */
  public static final URI GAME_CDN = URI.create("https://game-assets.clashroyaleapp.com/");

  private static final Duration TIMEOUT = Duration.ofMinutes(2);

  private final URI base;
  private HttpClient http;

  private AssetSource(URI base) {
    String text = base.toString();
    this.base = text.endsWith("/") ? base : URI.create(text + "/");
  }

  /** The game's asset CDN. */
  public static AssetSource gameCdn() {
    return new AssetSource(GAME_CDN);
  }

  /** A source at a base URI: {@code https:}, {@code http:} or {@code file:}. */
  public static AssetSource at(URI base) {
    String scheme = base.getScheme();
    if (!"https".equals(scheme) && !"http".equals(scheme) && !"file".equals(scheme)) {
      throw new IllegalArgumentException("an asset source is an http(s) or file URI: " + base);
    }
    return new AssetSource(base);
  }

  /** A local folder laid out as the CDN. */
  public static AssetSource folder(Path folder) {
    return new AssetSource(folder.toAbsolutePath().toUri());
  }

  /** The base URI. */
  public URI base() {
    return base;
  }

  /** The fingerprint of a content set, as served. */
  byte[] fingerprint(String contentSha) throws IOException {
    return read(contentSha + "/fingerprint.json");
  }

  /** One file of a content set, as served. */
  byte[] file(String contentSha, String path) throws IOException {
    return read(contentSha + "/" + path);
  }

  private byte[] read(String relative) throws IOException {
    URI uri = base.resolve(relative);
    if ("file".equals(uri.getScheme())) {
      try {
        return Files.readAllBytes(Path.of(uri));
      } catch (NoSuchFileException e) {
        throw new IOException("not in the asset source: " + uri, e);
      }
    }
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
    HttpResponse<byte[]> response;
    try {
      response = client().send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while fetching " + uri, e);
    }
    if (response.statusCode() != 200) {
      throw new IOException("HTTP " + response.statusCode() + " for " + uri);
    }
    return response.body();
  }

  private synchronized HttpClient client() {
    if (http == null) {
      http =
          HttpClient.newBuilder()
              .connectTimeout(TIMEOUT)
              .followRedirects(HttpClient.Redirect.NORMAL)
              .build();
    }
    return http;
  }

  @Override
  public String toString() {
    return base.toString();
  }
}
