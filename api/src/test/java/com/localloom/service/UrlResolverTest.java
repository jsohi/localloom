package com.localloom.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.localloom.service.UrlResolver.UrlType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class UrlResolverTest {

  private UrlResolver urlResolver;

  @BeforeEach
  void setUp() {
    urlResolver = new UrlResolver(RestClient.builder());
  }

  @Test
  void detectsYoutubeWatchUrl() {
    assertThat(urlResolver.detectType("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        .isEqualTo(UrlType.YOUTUBE);
  }

  @Test
  void detectsYoutubeShortUrl() {
    assertThat(urlResolver.detectType("https://youtu.be/dQw4w9WgXcQ")).isEqualTo(UrlType.YOUTUBE);
  }

  @Test
  void detectsYoutubePlaylistUrl() {
    assertThat(
            urlResolver.detectType(
                "https://www.youtube.com/playlist?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"))
        .isEqualTo(UrlType.YOUTUBE);
  }

  @Test
  void detectsApplePodcastsUrl() {
    assertThat(urlResolver.detectType("https://podcasts.apple.com/us/podcast/id1234567890"))
        .isEqualTo(UrlType.APPLE_PODCASTS);
  }

  @Test
  void detectsSpotifyUrl() {
    assertThat(urlResolver.detectType("https://open.spotify.com/show/4rOoJ6Egrf8K2IrywzwOMk"))
        .isEqualTo(UrlType.SPOTIFY);
  }

  @Test
  void detectsRssFallback() {
    assertThat(urlResolver.detectType("https://example.com/feed.xml")).isEqualTo(UrlType.RSS);
  }

  @Test
  void blankUrlThrows() {
    assertThatThrownBy(() -> urlResolver.detectType(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be blank");
  }

  @Test
  void nullUrlThrows() {
    assertThatThrownBy(() -> urlResolver.detectType(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
