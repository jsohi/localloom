package com.localloom.service;

import com.localloom.model.SourceType;
import com.localloom.service.dto.ResolvedEpisode;
import com.localloom.service.dto.ResolvedPodcast;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class UrlResolver {

    private static final Logger log = LoggerFactory.getLogger(UrlResolver.class);

    private static final Pattern YOUTUBE_WATCH     = Pattern.compile("(?:https?://)?(?:www\\.)?youtube\\.com/watch\\?.*v=([\\w-]+)");
    private static final Pattern YOUTUBE_SHORT     = Pattern.compile("(?:https?://)?youtu\\.be/([\\w-]+)");
    private static final Pattern YOUTUBE_PLAYLIST  = Pattern.compile("(?:https?://)?(?:www\\.)?youtube\\.com/playlist\\?.*list=([\\w-]+)");
    private static final Pattern APPLE_PODCASTS    = Pattern.compile("(?:https?://)?podcasts\\.apple\\.com/.*/id(\\d+)");
    private static final Pattern SPOTIFY_SHOW      = Pattern.compile("(?:https?://)?open\\.spotify\\.com/show/([\\w]+)");

    private static final String ITUNES_LOOKUP_URL  = "https://itunes.apple.com/lookup?id=%s&entity=podcast";
    private static final String ITUNES_SEARCH_URL  = "https://itunes.apple.com/search?term=%s&media=podcast&entity=podcast&limit=1";
    private static final String SPOTIFY_OEMBED_URL = "https://open.spotify.com/oembed?url=%s";

    private final RestClient restClient;

    public UrlResolver(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public enum UrlType {
        YOUTUBE, APPLE_PODCASTS, SPOTIFY, RSS
    }

    /**
     * Detects the type of the given URL based on domain and path pattern matching.
     */
    public UrlType detectType(String url) {
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
        log.debug("No specific match for URL, treating as RSS: {}", url);
        return UrlType.RSS;
    }

    /**
     * Resolves the given URL to a {@link ResolvedPodcast} containing metadata and episode list.
     */
    public ResolvedPodcast resolve(String url) {
        UrlType type = detectType(url);
        log.info("Resolving URL as {}: {}", type, url);
        return switch (type) {
            case YOUTUBE       -> resolveYoutube(url);
            case APPLE_PODCASTS -> resolveApplePodcasts(url);
            case SPOTIFY       -> resolveSpotify(url);
            case RSS           -> resolveRss(url);
        };
    }

    // -------------------------------------------------------------------------
    // Private resolvers
    // -------------------------------------------------------------------------

    /**
     * Resolves a YouTube URL. Extracts the video or playlist ID from the URL and
     * returns stub metadata.
     *
     * TODO: Replace stub with a full yt-dlp --dump-json subprocess call via
     *       ProcessBuilder to retrieve real title, description, uploader, and
     *       episode list (for playlists / channels).
     */
    private ResolvedPodcast resolveYoutube(String url) {
        log.debug("Resolving YouTube URL: {}", url);

        String videoId = extractYoutubeId(url);
        log.info("Extracted YouTube ID: {}", videoId);

        // TODO: invoke yt-dlp for real metadata, e.g.:
        //   ProcessBuilder pb = new ProcessBuilder("yt-dlp", "--dump-json", "--no-playlist", url);
        //   Process proc = pb.start();
        //   String json = new String(proc.getInputStream().readAllBytes());
        //   -- then parse the JSON for title, uploader, description, thumbnail, etc.

        List<ResolvedEpisode> episodes = List.of(
                new ResolvedEpisode(
                        "YouTube video " + videoId,
                        null,
                        url,
                        null,
                        null,
                        videoId
                )
        );

        return new ResolvedPodcast(
                "YouTube: " + videoId,
                null,
                null,
                null,
                null,
                url,
                SourceType.PODCAST,
                episodes
        );
    }

    /**
     * Resolves an Apple Podcasts URL by extracting the podcast ID and calling the
     * iTunes Lookup API.
     */
    private ResolvedPodcast resolveApplePodcasts(String url) {
        log.debug("Resolving Apple Podcasts URL: {}", url);

        Matcher m = APPLE_PODCASTS.matcher(url);
        if (!m.find()) {
            throw new IllegalArgumentException("Could not extract podcast ID from Apple Podcasts URL: " + url);
        }
        String podcastId = m.group(1);
        log.info("Extracted Apple Podcasts ID: {}", podcastId);

        String lookupUrl = ITUNES_LOOKUP_URL.formatted(podcastId);
        log.debug("Calling iTunes Lookup API: {}", lookupUrl);

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> response = restClient.get()
                .uri(lookupUrl)
                .retrieve()
                .body(java.util.Map.class);

        return parseiTunesResponse(response, url);
    }

    /**
     * Resolves a Spotify show URL via the Spotify oEmbed API to get the show name,
     * then searches the iTunes API to find the corresponding podcast feed.
     */
    private ResolvedPodcast resolveSpotify(String url) {
        log.debug("Resolving Spotify URL: {}", url);

        String oEmbedUrl = SPOTIFY_OEMBED_URL.formatted(
                URLEncoder.encode(url, StandardCharsets.UTF_8));
        log.debug("Calling Spotify oEmbed API: {}", oEmbedUrl);

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> oEmbedResponse = restClient.get()
                .uri(oEmbedUrl)
                .retrieve()
                .body(java.util.Map.class);

        String showName = oEmbedResponse != null ? (String) oEmbedResponse.get("title") : null;
        if (showName == null || showName.isBlank()) {
            throw new IllegalStateException("Could not retrieve show name from Spotify oEmbed for: " + url);
        }
        log.info("Spotify show name from oEmbed: {}", showName);

        String searchUrl = ITUNES_SEARCH_URL.formatted(
                URLEncoder.encode(showName, StandardCharsets.UTF_8));
        log.debug("Searching iTunes for: {}", searchUrl);

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> searchResponse = restClient.get()
                .uri(searchUrl)
                .retrieve()
                .body(java.util.Map.class);

        return parseiTunesResponse(searchResponse, url);
    }

    /**
     * Resolves a generic RSS feed URL by fetching the XML and parsing it with the
     * standard Java DOM parser (no extra dependency required).
     */
    private ResolvedPodcast resolveRss(String feedUrl) {
        log.debug("Resolving RSS feed: {}", feedUrl);

        byte[] xmlBytes = restClient.get()
                .uri(feedUrl)
                .retrieve()
                .body(byte[].class);

        if (xmlBytes == null || xmlBytes.length == 0) {
            throw new IllegalStateException("Empty response fetching RSS feed: " + feedUrl);
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xmlBytes));
            doc.getDocumentElement().normalize();

            Element channel = (Element) doc.getElementsByTagName("channel").item(0);
            if (channel == null) {
                throw new IllegalStateException("No <channel> element found in RSS feed: " + feedUrl);
            }

            String title       = firstElementText(channel, "title");
            String description = firstElementText(channel, "description");
            String author      = firstElementText(channel, "itunes:author");
            String artworkUrl  = extractRssArtwork(channel);

            List<ResolvedEpisode> episodes = new ArrayList<>();
            NodeList items = channel.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                episodes.add(parseRssItem(item));
            }

            log.info("Parsed RSS feed '{}': {} episode(s)", title, episodes.size());

            return new ResolvedPodcast(
                    title,
                    author,
                    description,
                    artworkUrl,
                    feedUrl,
                    feedUrl,
                    SourceType.PODCAST,
                    episodes
            );

        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse RSS feed: " + feedUrl, e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String extractYoutubeId(String url) {
        Matcher watchMatcher = YOUTUBE_WATCH.matcher(url);
        if (watchMatcher.find()) {
            return watchMatcher.group(1);
        }
        Matcher shortMatcher = YOUTUBE_SHORT.matcher(url);
        if (shortMatcher.find()) {
            return shortMatcher.group(1);
        }
        Matcher playlistMatcher = YOUTUBE_PLAYLIST.matcher(url);
        if (playlistMatcher.find()) {
            return playlistMatcher.group(1);
        }
        throw new IllegalArgumentException("Could not extract YouTube ID from URL: " + url);
    }

    @SuppressWarnings("unchecked")
    private ResolvedPodcast parseiTunesResponse(java.util.Map<String, Object> response, String sourceUrl) {
        if (response == null) {
            throw new IllegalStateException("Null response from iTunes API for URL: " + sourceUrl);
        }

        List<java.util.Map<String, Object>> results =
                (List<java.util.Map<String, Object>>) response.get("results");

        if (results == null || results.isEmpty()) {
            throw new IllegalStateException("No results from iTunes API for URL: " + sourceUrl);
        }

        java.util.Map<String, Object> result = results.get(0);

        String title      = (String) result.get("collectionName");
        String author     = (String) result.get("artistName");
        String artwork    = (String) result.getOrDefault("artworkUrl600", result.get("artworkUrl100"));
        String feedUrl    = (String) result.get("feedUrl");
        String description = null; // iTunes Lookup does not return a description field

        log.info("iTunes resolved podcast: '{}' by '{}', feedUrl={}", title, author, feedUrl);

        // If we have a feedUrl, fetch the full episode list from RSS
        List<ResolvedEpisode> episodes = new ArrayList<>();
        if (feedUrl != null && !feedUrl.isBlank()) {
            try {
                ResolvedPodcast rssResolved = resolveRss(feedUrl);
                episodes = rssResolved.episodes();
                if (description == null) {
                    description = rssResolved.description();
                }
            } catch (Exception e) {
                log.warn("Could not fetch episodes from RSS feed '{}': {}", feedUrl, e.getMessage());
            }
        }

        return new ResolvedPodcast(
                title,
                author,
                description,
                artwork,
                feedUrl,
                sourceUrl,
                SourceType.PODCAST,
                episodes
        );
    }

    private ResolvedEpisode parseRssItem(Element item) {
        String title       = firstElementText(item, "title");
        String description = firstElementText(item, "description");
        if (description == null) {
            description = firstElementText(item, "itunes:summary");
        }
        String audioUrl    = extractEnclosureUrl(item);
        String guid        = firstElementText(item, "guid");
        String pubDateStr  = firstElementText(item, "pubDate");
        String durationStr = firstElementText(item, "itunes:duration");

        Instant publishedAt = parseRfc822Date(pubDateStr);
        Integer durationSeconds = parseDurationSeconds(durationStr);

        return new ResolvedEpisode(title, description, audioUrl, publishedAt, durationSeconds, guid);
    }

    private String extractEnclosureUrl(Element item) {
        NodeList enclosures = item.getElementsByTagName("enclosure");
        if (enclosures.getLength() > 0) {
            Element enc = (Element) enclosures.item(0);
            String url = enc.getAttribute("url");
            if (url != null && !url.isBlank()) {
                return url;
            }
        }
        return null;
    }

    private String extractRssArtwork(Element channel) {
        // Prefer <itunes:image href="...">
        NodeList itunesImages = channel.getElementsByTagNameNS("*", "image");
        for (int i = 0; i < itunesImages.getLength(); i++) {
            Element img = (Element) itunesImages.item(i);
            String href = img.getAttribute("href");
            if (href != null && !href.isBlank()) {
                return href;
            }
        }
        // Fall back to <image><url>...</url></image>
        NodeList imageNodes = channel.getElementsByTagName("image");
        if (imageNodes.getLength() > 0) {
            Element imageEl = (Element) imageNodes.item(0);
            return firstElementText(imageEl, "url");
        }
        return null;
    }

    private String firstElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            String text = nodes.item(0).getTextContent();
            return (text != null && !text.isBlank()) ? text.strip() : null;
        }
        return null;
    }

    /**
     * Parses an RFC-822 date string (standard in RSS) to an {@link Instant}.
     * Returns {@code null} if the string is blank or unparseable.
     */
    private Instant parseRfc822Date(String pubDateStr) {
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
     * Parses an itunes:duration value into total seconds.
     * Accepts formats: {@code HH:MM:SS}, {@code MM:SS}, or a plain integer (seconds).
     * Returns {@code null} if the string is blank or unparseable.
     */
    private Integer parseDurationSeconds(String durationStr) {
        if (durationStr == null || durationStr.isBlank()) {
            return null;
        }
        try {
            String[] parts = durationStr.strip().split(":");
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
}
