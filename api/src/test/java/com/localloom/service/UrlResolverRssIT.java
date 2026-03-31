package com.localloom.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class UrlResolverRssIT {

  private static WireMockServer wireMock;
  private UrlResolver urlResolver;

  @BeforeAll
  static void startWireMock() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stopWireMock() {
    wireMock.stop();
  }

  @BeforeEach
  void setUp() {
    wireMock.resetAll();
    urlResolver = new UrlResolver(RestClient.builder());
  }

  @Test
  void resolveRssFeedParsesEpisodes() {
    var feedUrl = "http://localhost:" + wireMock.port() + "/feed.xml";

    wireMock.stubFor(
        get(urlEqualTo("/feed.xml"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/xml")
                    .withBody(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
                          <channel>
                            <title>Test Podcast</title>
                            <description>A podcast about testing</description>
                            <itunes:author>Test Author</itunes:author>
                            <itunes:image href="https://example.com/artwork.jpg"/>
                            <item>
                              <title>Episode 1: Getting Started</title>
                              <description>Introduction to testing</description>
                              <enclosure url="https://example.com/ep1.mp3" type="audio/mpeg" length="12345"/>
                              <guid>ep1-guid</guid>
                              <pubDate>Mon, 01 Jan 2024 08:00:00 GMT</pubDate>
                              <itunes:duration>01:30:00</itunes:duration>
                            </item>
                            <item>
                              <title>Episode 2: Advanced Topics</title>
                              <description>Deep dive into integration tests</description>
                              <enclosure url="https://example.com/ep2.mp3" type="audio/mpeg" length="67890"/>
                              <guid>ep2-guid</guid>
                              <pubDate>Mon, 08 Jan 2024 08:00:00 GMT</pubDate>
                              <itunes:duration>45:30</itunes:duration>
                            </item>
                          </channel>
                        </rss>
                        """)));

    var result = urlResolver.resolve(feedUrl);

    assertThat(result.title()).isEqualTo("Test Podcast");
    assertThat(result.description()).isEqualTo("A podcast about testing");
    assertThat(result.author()).isEqualTo("Test Author");
    assertThat(result.artworkUrl()).isEqualTo("https://example.com/artwork.jpg");

    assertThat(result.episodes()).hasSize(2);

    var ep1 = result.episodes().get(0);
    assertThat(ep1.title()).isEqualTo("Episode 1: Getting Started");
    assertThat(ep1.audioUrl()).isEqualTo("https://example.com/ep1.mp3");
    assertThat(ep1.externalId()).isEqualTo("ep1-guid");
    assertThat(ep1.durationSeconds()).isEqualTo(5400); // 1h30m
    assertThat(ep1.publishedAt()).isNotNull();

    var ep2 = result.episodes().get(1);
    assertThat(ep2.title()).isEqualTo("Episode 2: Advanced Topics");
    assertThat(ep2.durationSeconds()).isEqualTo(2730); // 45m30s
  }

  @Test
  void resolveRssFeedWithNoEpisodes() {
    var feedUrl = "http://localhost:" + wireMock.port() + "/empty-feed.xml";

    wireMock.stubFor(
        get(urlEqualTo("/empty-feed.xml"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/xml")
                    .withBody(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <rss version="2.0">
                          <channel>
                            <title>Empty Podcast</title>
                            <description>No episodes yet</description>
                          </channel>
                        </rss>
                        """)));

    var result = urlResolver.resolve(feedUrl);

    assertThat(result.title()).isEqualTo("Empty Podcast");
    assertThat(result.episodes()).isEmpty();
  }

  @Test
  void resolveRssFeedWithMissingEnclosure() {
    var feedUrl = "http://localhost:" + wireMock.port() + "/no-enclosure.xml";

    wireMock.stubFor(
        get(urlEqualTo("/no-enclosure.xml"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/xml")
                    .withBody(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <rss version="2.0">
                          <channel>
                            <title>Podcast</title>
                            <item>
                              <title>No Audio Episode</title>
                              <description>This episode has no audio file</description>
                              <guid>no-audio-guid</guid>
                            </item>
                          </channel>
                        </rss>
                        """)));

    var result = urlResolver.resolve(feedUrl);

    assertThat(result.episodes()).hasSize(1);
    assertThat(result.episodes().getFirst().audioUrl()).isNull();
  }

  @Test
  void resolveRssFeedWithPlainSecondsDuration() {
    var feedUrl = "http://localhost:" + wireMock.port() + "/seconds-duration.xml";

    wireMock.stubFor(
        get(urlEqualTo("/seconds-duration.xml"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/xml")
                    .withBody(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
                          <channel>
                            <title>Duration Test</title>
                            <item>
                              <title>Short Episode</title>
                              <enclosure url="https://example.com/short.mp3" type="audio/mpeg"/>
                              <itunes:duration>300</itunes:duration>
                            </item>
                          </channel>
                        </rss>
                        """)));

    var result = urlResolver.resolve(feedUrl);

    assertThat(result.episodes().getFirst().durationSeconds()).isEqualTo(300);
  }

  @Test
  void malformedXmlThrowsDescriptiveException() {
    var feedUrl = "http://localhost:" + wireMock.port() + "/malformed.xml";

    wireMock.stubFor(
        get(urlEqualTo("/malformed.xml"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/xml")
                    .withBody("this is not valid xml <<<")));

    assertThatThrownBy(() -> urlResolver.resolve(feedUrl))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to parse RSS feed");
  }

  @Test
  void emptyFeedResponseThrows() {
    var feedUrl = "http://localhost:" + wireMock.port() + "/empty.xml";

    wireMock.stubFor(
        get(urlEqualTo("/empty.xml")).willReturn(aResponse().withStatus(200).withBody("")));

    assertThatThrownBy(() -> urlResolver.resolve(feedUrl))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Empty response");
  }
}
