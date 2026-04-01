package com.localloom.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localloom.model.SourceType;
import com.localloom.service.UrlResolver.UrlType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class UrlResolverTest {

  private UrlResolver urlResolver;

  @BeforeEach
  void setUp() {
    var ssrfValidator = new SsrfValidator(List.of());
    urlResolver =
        new UrlResolver(RestClient.builder(), new ObjectMapper(), "yt-dlp", ssrfValidator);
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
  void detectsWebPageForPlainUrl() {
    assertThat(urlResolver.detectType("https://example.com/blog/post-123"))
        .isEqualTo(UrlType.WEB_PAGE);
  }

  @Test
  void detectsRssForFeedPath() {
    assertThat(urlResolver.detectType("https://blog.example.com/feed")).isEqualTo(UrlType.RSS);
  }

  @Test
  void detectsRssForAtomExtension() {
    assertThat(urlResolver.detectType("https://example.com/blog.atom")).isEqualTo(UrlType.RSS);
  }

  @Test
  void detectsRssForRssExtension() {
    assertThat(urlResolver.detectType("https://example.com/podcast.rss")).isEqualTo(UrlType.RSS);
  }

  @Test
  void toSourceTypeYoutube() {
    assertThat(urlResolver.toSourceType(UrlType.YOUTUBE)).isEqualTo(SourceType.YOUTUBE);
  }

  @Test
  void toSourceTypeRss() {
    assertThat(urlResolver.toSourceType(UrlType.RSS)).isEqualTo(SourceType.MEDIA);
  }

  @Test
  void toSourceTypeApplePodcasts() {
    assertThat(urlResolver.toSourceType(UrlType.APPLE_PODCASTS)).isEqualTo(SourceType.MEDIA);
  }

  @Test
  void toSourceTypeSpotify() {
    assertThat(urlResolver.toSourceType(UrlType.SPOTIFY)).isEqualTo(SourceType.MEDIA);
  }

  @Test
  void toSourceTypeWebPage() {
    assertThat(urlResolver.toSourceType(UrlType.WEB_PAGE)).isEqualTo(SourceType.WEB_PAGE);
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
