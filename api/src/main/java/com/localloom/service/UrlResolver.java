package com.localloom.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localloom.model.SourceType;
import com.localloom.service.dto.ResolvedEpisode;
import com.localloom.service.dto.ResolvedPodcast;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Element;

@Service
public class UrlResolver {

  private static final Logger log = LogManager.getLogger(UrlResolver.class);

  private static final Pattern YOUTUBE_WATCH =
      Pattern.compile("(?:https?://)?(?:www\\.)?youtube\\.com/watch\\?.*v=([\\w-]+)");
  private static final Pattern YOUTUBE_SHORT =
      Pattern.compile("(?:https?://)?youtu\\.be/([\\w-]+)");
  private static final Pattern YOUTUBE_PLAYLIST =
      Pattern.compile("(?:https?://)?(?:www\\.)?youtube\\.com/playlist\\?.*list=([\\w-]+)");
  private static final Pattern APPLE_PODCASTS =
      Pattern.compile("(?:https?://)?podcasts\\.apple\\.com/.*/id(\\d+)");
  private static final Pattern SPOTIFY_SHOW =
      Pattern.compile("(?:https?://)?open\\.spotify\\.com/show/([\\w]+)");

  private static final String ITUNES_LOOKUP_URL =
      "https://itunes.apple.com/lookup?id=%s&entity=podcast";
  private static final String ITUNES_SEARCH_URL =
      "https://itunes.apple.com/search?term=%s&media=podcast&entity=podcast&limit=1";
  private static final String SPOTIFY_OEMBED_URL = "https://open.spotify.com/oembed?url=%s";

  private static final Set<String> RSS_PATH_HINTS =
      Set.of(".xml", ".rss", ".atom", "/feed", "/rss");
  private static final int YTDLP_TIMEOUT_SECONDS = 30;

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final String ytdlpPath;
  private final SsrfValidator ssrfValidator;

  public UrlResolver(
      final RestClient.Builder restClientBuilder,
      final ObjectMapper objectMapper,
      @Value("${localloom.audio.ytdlp-path:yt-dlp}") final String ytdlpPath,
      final SsrfValidator ssrfValidator) {
    this.restClient = restClientBuilder.build();
    this.objectMapper = objectMapper;
    this.ytdlpPath = ytdlpPath;
    this.ssrfValidator = ssrfValidator;
  }

  // -------------------------------------------------------------------------
  // Public API
  // -------------------------------------------------------------------------

  public enum UrlType {
    YOUTUBE,
    APPLE_PODCASTS,
    SPOTIFY,
    RSS,
    WEB_PAGE
  }

  /** Detects the type of the given URL based on domain and path pattern matching. */
  public UrlType detectType(final String url) {
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("URL must not be blank");
    }
    if (YOUTUBE_WATCH.matcher(url).find()
        || YOUTUBE_SHORT.matcher(url).find()
        || YOUTUBE_PLAYLIST.matcher(url).find()) {
      log.debug("Detected URL type YOUTUBE for: {}", url);
      return UrlType.YOUTUBE;
    }
    if (APPLE_PODCASTS.matcher(url).find()) {
      log.debug("Detected URL type APPLE_PODCASTS for: {}", url);
      return UrlType.APPLE_PODCASTS;
    }
    if (SPOTIFY_SHOW.matcher(url).find()) {
      log.debug("Detected URL type SPOTIFY for: {}", url);
      return UrlType.SPOTIFY;
    }
    if (looksLikeRss(url)) {
      log.debug("URL looks like an RSS feed: {}", url);
      return UrlType.RSS;
    }
    log.debug("No specific match for URL, treating as WEB_PAGE: {}", url);
    return UrlType.WEB_PAGE;
  }

  /** Maps a detected {@link UrlType} to the corresponding {@link SourceType} pipeline. */
  public SourceType toSourceType(final UrlType urlType) {
    return switch (urlType) {
      case YOUTUBE -> SourceType.YOUTUBE;
      case APPLE_PODCASTS, SPOTIFY, RSS -> SourceType.MEDIA;
      case WEB_PAGE -> SourceType.WEB_PAGE;
    };
  }

  /** Resolves the given URL to a {@link ResolvedPodcast} containing metadata and episode list. */
  public ResolvedPodcast resolve(final String url) {
    final var type = detectType(url);
    log.info("Resolving URL as {}: {}", type, url);
    return switch (type) {
      case YOUTUBE -> resolveYoutube(url);
      case APPLE_PODCASTS -> resolveApplePodcasts(url);
      case SPOTIFY -> resolveSpotify(url);
      case RSS -> resolveRss(url);
      case WEB_PAGE ->
          throw new IllegalArgumentException(
              "Cannot resolve WEB_PAGE URLs via UrlResolver: " + url);
    };
  }

  // -------------------------------------------------------------------------
  // Private resolvers
  // -------------------------------------------------------------------------

  /**
   * Resolves a YouTube URL by calling {@code yt-dlp --dump-json} to retrieve real metadata (title,
   * uploader, description, thumbnail, duration).
   */
  private ResolvedPodcast resolveYoutube(final String url) {
    log.debug("Resolving YouTube URL via yt-dlp: {}", url);

    try {
      // Use -- to prevent user URLs starting with - from being parsed as yt-dlp flags
      final var pb = new ProcessBuilder(ytdlpPath, "--dump-json", "--no-playlist", "--", url);
      pb.redirectErrorStream(false);
      final var process = pb.start();

      // Read stdout and stderr concurrently in background threads to avoid pipe-buffer deadlock
      // and ensure waitFor timeout is respected even if streams block
      final var stdoutFuture =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return new String(
                      process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                  return "";
                }
              });
      final var stderrFuture =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return new String(
                      process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                  return "";
                }
              });

      final var exited = process.waitFor(YTDLP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!exited) {
        process.destroyForcibly();
        throw new IllegalStateException("yt-dlp timed out after " + YTDLP_TIMEOUT_SECONDS + "s");
      }

      final var json = stdoutFuture.join();
      final var stderr = stderrFuture.join();

      if (process.exitValue() != 0) {
        throw new IllegalStateException(
            "yt-dlp failed (exit " + process.exitValue() + "): " + stderr);
      }

      final var node = objectMapper.readTree(json);

      final var videoId = nodeText(node, "id");
      final var title = nodeText(node, "title");
      final var uploader = nodeText(node, "uploader");
      final var description = nodeText(node, "description");
      final var thumbnail = nodeText(node, "thumbnail");
      final var durationNode = node.get("duration");
      final var durationSeconds =
          durationNode != null && durationNode.isNumber() ? durationNode.intValue() : null;

      log.info(
          "yt-dlp resolved: title='{}' uploader='{}' duration={}s",
          title,
          uploader,
          durationSeconds);

      final var episodes =
          List.of(
              new ResolvedEpisode(
                  title != null ? title : "YouTube video " + videoId,
                  description,
                  url,
                  null,
                  durationSeconds,
                  videoId));

      return new ResolvedPodcast(
          title, uploader, description, thumbnail, null, url, SourceType.YOUTUBE, episodes);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("yt-dlp interrupted", e);
    } catch (Exception e) {
      if (e instanceof IllegalStateException) throw (IllegalStateException) e;
      throw new IllegalStateException(
          "Failed to resolve YouTube URL via yt-dlp: " + e.getMessage(), e);
    }
  }

  private static String nodeText(final JsonNode node, final String field) {
    final var child = node.get(field);
    return child != null && child.isTextual() ? child.asText() : null;
  }

  /**
   * Resolves an Apple Podcasts URL by extracting the podcast ID and calling the iTunes Lookup API.
   */
  private ResolvedPodcast resolveApplePodcasts(final String url) {
    log.debug("Resolving Apple Podcasts URL: {}", url);

    final var m = APPLE_PODCASTS.matcher(url);
    if (!m.find()) {
      throw new IllegalArgumentException(
          "Could not extract podcast ID from Apple Podcasts URL: " + url);
    }
    final var podcastId = m.group(1);
    log.info("Extracted Apple Podcasts ID: {}", podcastId);

    final var lookupUrl = ITUNES_LOOKUP_URL.formatted(podcastId);
    log.debug("Calling iTunes Lookup API: {}", lookupUrl);

    @SuppressWarnings("unchecked")
    final var response =
        (java.util.Map<String, Object>)
            restClient.get().uri(lookupUrl).retrieve().body(java.util.Map.class);

    return parseiTunesResponse(response, url);
  }

  /**
   * Resolves a Spotify show URL via the Spotify oEmbed API to get the show name, then searches the
   * iTunes API to find the corresponding podcast feed.
   */
  private ResolvedPodcast resolveSpotify(final String url) {
    log.debug("Resolving Spotify URL: {}", url);

    final var oEmbedUrl =
        SPOTIFY_OEMBED_URL.formatted(URLEncoder.encode(url, StandardCharsets.UTF_8));
    log.debug("Calling Spotify oEmbed API: {}", oEmbedUrl);

    @SuppressWarnings("unchecked")
    final var oEmbedResponse =
        (java.util.Map<String, Object>)
            restClient.get().uri(oEmbedUrl).retrieve().body(java.util.Map.class);

    final var showName = oEmbedResponse != null ? (String) oEmbedResponse.get("title") : null;
    if (showName == null || showName.isBlank()) {
      throw new IllegalStateException(
          "Could not retrieve show name from Spotify oEmbed for: " + url);
    }
    log.info("Spotify show name from oEmbed: {}", showName);

    final var searchUrl =
        ITUNES_SEARCH_URL.formatted(URLEncoder.encode(showName, StandardCharsets.UTF_8));
    log.debug("Searching iTunes for: {}", searchUrl);

    @SuppressWarnings("unchecked")
    final var searchResponse =
        (java.util.Map<String, Object>)
            restClient.get().uri(searchUrl).retrieve().body(java.util.Map.class);

    return parseiTunesResponse(searchResponse, url);
  }

  /**
   * Resolves a generic RSS feed URL by fetching the XML and parsing it with the standard Java DOM
   * parser (no extra dependency required).
   */
  private ResolvedPodcast resolveRss(final String feedUrl) {
    log.debug("Resolving RSS feed: {}", feedUrl);
    ssrfValidator.validate(feedUrl);

    final var xmlBytes = restClient.get().uri(feedUrl).retrieve().body(byte[].class);

    if (xmlBytes == null || xmlBytes.length == 0) {
      throw new IllegalStateException("Empty response fetching RSS feed: " + feedUrl);
    }

    try {
      final var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      // Prevent XXE attacks on user-supplied RSS feeds (allow DTDs since RSS uses them)
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      final var builder = factory.newDocumentBuilder();
      final var doc = builder.parse(new ByteArrayInputStream(xmlBytes));
      doc.getDocumentElement().normalize();

      final var channel = (Element) doc.getElementsByTagName("channel").item(0);
      if (channel == null) {
        throw new IllegalStateException("No <channel> element found in RSS feed: " + feedUrl);
      }

      final var title = firstElementText(channel, "title");
      final var description = firstElementText(channel, "description");
      final var author = firstElementText(channel, "itunes:author");
      final var artworkUrl = extractRssArtwork(channel);

      final var episodes = new ArrayList<ResolvedEpisode>();
      final var items = channel.getElementsByTagName("item");
      for (int i = 0; i < items.getLength(); i++) {
        final var item = (Element) items.item(i);
        episodes.add(parseRssItem(item));
      }

      log.info("Parsed RSS feed '{}': {} episode(s)", title, episodes.size());

      return new ResolvedPodcast(
          title, author, description, artworkUrl, feedUrl, feedUrl, SourceType.MEDIA, episodes);

    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse RSS feed: " + feedUrl, e);
    }
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private ResolvedPodcast parseiTunesResponse(
      final java.util.Map<String, Object> response, final String sourceUrl) {
    if (response == null) {
      throw new IllegalStateException("Null response from iTunes API for URL: " + sourceUrl);
    }

    final var results = (List<java.util.Map<String, Object>>) response.get("results");

    if (results == null || results.isEmpty()) {
      throw new IllegalStateException("No results from iTunes API for URL: " + sourceUrl);
    }

    final var result = results.get(0);

    final var title = (String) result.get("collectionName");
    final var author = (String) result.get("artistName");
    final var artwork = (String) result.getOrDefault("artworkUrl600", result.get("artworkUrl100"));
    final var feedUrl = (String) result.get("feedUrl");
    var description = (String) null; // iTunes Lookup does not return a description field

    log.info("iTunes resolved podcast: '{}' by '{}', feedUrl={}", title, author, feedUrl);

    // If we have a feedUrl, fetch the full episode list from RSS
    var episodes = new ArrayList<ResolvedEpisode>();
    if (feedUrl != null && !feedUrl.isBlank()) {
      try {
        final var rssResolved = resolveRss(feedUrl);
        episodes = new ArrayList<>(rssResolved.episodes());
        if (description == null) {
          description = rssResolved.description();
        }
      } catch (Exception e) {
        log.warn("Could not fetch episodes from RSS feed '{}': {}", feedUrl, e.getMessage());
      }
    }

    return new ResolvedPodcast(
        title, author, description, artwork, feedUrl, sourceUrl, SourceType.MEDIA, episodes);
  }

  private ResolvedEpisode parseRssItem(final Element item) {
    final var title = firstElementText(item, "title");
    var description = firstElementText(item, "description");
    if (description == null) {
      description = firstElementText(item, "itunes:summary");
    }
    final var audioUrl = extractEnclosureUrl(item);
    if (audioUrl != null) {
      ssrfValidator.validate(audioUrl);
    }
    final var guid = firstElementText(item, "guid");
    final var pubDateStr = firstElementText(item, "pubDate");
    final var durationStr = firstElementText(item, "itunes:duration");

    final var publishedAt = parseRfc822Date(pubDateStr);
    final var durationSeconds = parseDurationSeconds(durationStr);

    return new ResolvedEpisode(title, description, audioUrl, publishedAt, durationSeconds, guid);
  }

  private String extractEnclosureUrl(final Element item) {
    final var enclosures = item.getElementsByTagName("enclosure");
    if (enclosures.getLength() > 0) {
      final var enc = (Element) enclosures.item(0);
      final var url = enc.getAttribute("url");
      if (url != null && !url.isBlank()) {
        return url;
      }
    }
    return null;
  }

  private String extractRssArtwork(final Element channel) {
    // Prefer <itunes:image href="...">
    final var itunesImages = channel.getElementsByTagNameNS("*", "image");
    for (int i = 0; i < itunesImages.getLength(); i++) {
      final var img = (Element) itunesImages.item(i);
      final var href = img.getAttribute("href");
      if (href != null && !href.isBlank()) {
        return href;
      }
    }
    // Fall back to <image><url>...</url></image>
    final var imageNodes = channel.getElementsByTagName("image");
    if (imageNodes.getLength() > 0) {
      final var imageEl = (Element) imageNodes.item(0);
      return firstElementText(imageEl, "url");
    }
    return null;
  }

  private String firstElementText(final Element parent, final String tagName) {
    final var nodes = parent.getElementsByTagName(tagName);
    if (nodes.getLength() > 0) {
      final var text = nodes.item(0).getTextContent();
      return (text != null && !text.isBlank()) ? text.strip() : null;
    }
    return null;
  }

  /**
   * Parses an RFC-822 date string (standard in RSS) to an {@link Instant}. Returns {@code null} if
   * the string is blank or unparseable.
   */
  private Instant parseRfc822Date(final String pubDateStr) {
    if (pubDateStr == null || pubDateStr.isBlank()) {
      return null;
    }
    try {
      return DateTimeFormatter.RFC_1123_DATE_TIME
          .parse(pubDateStr, java.time.ZonedDateTime::from)
          .toInstant();
    } catch (DateTimeParseException e) {
      log.debug("Could not parse pubDate '{}': {}", pubDateStr, e.getMessage());
      return null;
    }
  }

  /**
   * Parses an itunes:duration value into total seconds. Accepts formats: {@code HH:MM:SS}, {@code
   * MM:SS}, or a plain integer (seconds). Returns {@code null} if the string is blank or
   * unparseable.
   */
  private Integer parseDurationSeconds(final String durationStr) {
    if (durationStr == null || durationStr.isBlank()) {
      return null;
    }
    try {
      final var parts = durationStr.strip().split(":");
      if (parts.length == 1) {
        return Integer.parseInt(parts[0]);
      } else if (parts.length == 2) {
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
      } else if (parts.length == 3) {
        return Integer.parseInt(parts[0]) * 3600
            + Integer.parseInt(parts[1]) * 60
            + Integer.parseInt(parts[2]);
      }
    } catch (NumberFormatException e) {
      log.debug("Could not parse duration '{}': {}", durationStr, e.getMessage());
    }
    return null;
  }

  /**
   * Heuristic check: does the URL path look like an RSS/Atom feed? Checks for common extensions
   * (.xml, .rss, .atom) and path segments (/feed, /rss).
   */
  private boolean looksLikeRss(final String url) {
    try {
      final var path = URI.create(url).getPath();
      if (path == null) return false;
      final var lower = path.toLowerCase();
      return RSS_PATH_HINTS.stream().anyMatch(lower::contains);
    } catch (Exception e) {
      return false;
    }
  }
}
