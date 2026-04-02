package com.localloom.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localloom.model.ContentFragment;
import com.localloom.model.ContentType;
import com.localloom.model.ContentUnit;
import com.localloom.model.ContentUnitStatus;
import com.localloom.model.FragmentType;
import com.localloom.model.SourceType;
import com.localloom.model.SyncStatus;
import com.localloom.repository.ContentFragmentRepository;
import com.localloom.repository.ContentUnitRepository;
import com.localloom.repository.SourceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WebPageService {

  private static final Logger log = LogManager.getLogger(WebPageService.class);
  private static final int CONNECT_TIMEOUT_MS = 15_000;
  private static final Pattern HEADING_PATTERN =
      Pattern.compile("^h[1-6]$", Pattern.CASE_INSENSITIVE);

  private final SourceRepository sourceRepository;
  private final ContentUnitRepository contentUnitRepository;
  private final ContentFragmentRepository contentFragmentRepository;
  private final EmbeddingService embeddingService;
  private final JobService jobService;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate tx;

  public WebPageService(
      final SourceRepository sourceRepository,
      final ContentUnitRepository contentUnitRepository,
      final ContentFragmentRepository contentFragmentRepository,
      final EmbeddingService embeddingService,
      final JobService jobService,
      final ObjectMapper objectMapper,
      final TransactionTemplate transactionTemplate) {
    this.sourceRepository = sourceRepository;
    this.contentUnitRepository = contentUnitRepository;
    this.contentFragmentRepository = contentFragmentRepository;
    this.embeddingService = embeddingService;
    this.jobService = jobService;
    this.objectMapper = objectMapper;
    this.tx = transactionTemplate;
  }

  @Async
  public CompletableFuture<Void> importWebPage(final UUID sourceId, final UUID jobId) {
    log.info("Starting web page import: sourceId={}", sourceId);

    try {
      final var source =
          sourceRepository
              .findById(sourceId)
              .orElseThrow(() -> new IllegalArgumentException("Source not found: " + sourceId));

      final var url = source.getOriginUrl();
      log.info("Fetching web page: {}", url);

      final var doc =
          Jsoup.connect(url).timeout(CONNECT_TIMEOUT_MS).userAgent("LocalLoom/1.0").get();

      final var title = doc.title().isBlank() ? source.getName() : doc.title();

      // Remove navigation, sidebars, footers, and other non-content elements
      doc.select(
              "nav, header, footer, aside, [role=navigation], [role=banner], "
                  + "[role=complementary], .sidebar, .nav, .menu, .toc, .mw-jump-link, "
                  + ".vector-header, .vector-column-start, .vector-column-end, "
                  + ".mw-editsection, .reflist, .navbox, .catlinks, .mw-footer, "
                  + "#mw-navigation, #footer, #catlinks, script, style, noscript")
          .remove();

      final var body = doc.body();
      if (body == null) {
        throw new IllegalStateException("Page has no body content: " + url);
      }

      // Prefer <main> or <article> if present; fall back to full body
      var content =
          doc.selectFirst("main, article, [role=main], #mw-content-text, .mw-body-content");
      if (content == null || content.text().isBlank()) {
        content = body;
      }
      final var fullText = content.text();

      tx.executeWithoutResult(
          s -> {
            if (source.getDescription() == null) {
              final var metaDesc = doc.select("meta[name=description]").attr("content");
              if (!metaDesc.isBlank()) {
                source.setDescription(metaDesc);
              }
            }
            source.setSyncStatus(SyncStatus.SYNCING);
            sourceRepository.save(source);
          });

      final var unit =
          tx.execute(
              s -> {
                var cu = new ContentUnit();
                cu.setSource(source);
                cu.setTitle(title);
                cu.setContentType(ContentType.PAGE);
                cu.setExternalId(url);
                cu.setExternalUrl(url);
                cu.setStatus(ContentUnitStatus.EXTRACTING);
                cu.setPublishedAt(Instant.now());
                cu.setRawText(fullText);
                return contentUnitRepository.save(cu);
              });

      final var fragments = createFragments(unit, doc);

      tx.executeWithoutResult(
          s -> {
            unit.setStatus(ContentUnitStatus.EMBEDDING);
            contentUnitRepository.save(unit);
          });

      embeddingService.embedContent(
          source.getId(), unit.getId(), title, SourceType.WEB_PAGE, ContentType.PAGE, fragments);

      tx.executeWithoutResult(
          s -> {
            unit.setStatus(ContentUnitStatus.INDEXED);
            contentUnitRepository.save(unit);
            source.setSyncStatus(SyncStatus.IDLE);
            source.setLastSyncedAt(Instant.now());
            sourceRepository.save(source);
          });

      jobService.completeJob(jobId);
      log.info("Web page import complete: sourceId={} title='{}'", sourceId, title);

    } catch (Exception e) {
      log.error("Web page import failed: sourceId={}: {}", sourceId, e.getMessage(), e);
      sourceRepository
          .findById(sourceId)
          .ifPresent(
              s ->
                  tx.executeWithoutResult(
                      status -> {
                        s.setSyncStatus(SyncStatus.ERROR);
                        sourceRepository.save(s);
                      }));
      jobService.failJob(jobId, e.getMessage());
    }

    return CompletableFuture.completedFuture(null);
  }

  private List<ContentFragment> createFragments(final ContentUnit unit, final Document doc) {
    final var sections = splitBySections(doc);
    if (sections.size() <= 1) {
      // No headings found — treat the whole page as one fragment
      final var text = doc.body().text().strip();
      if (text.isEmpty()) return List.of();
      return tx.execute(
          s -> {
            var fragment = new ContentFragment();
            fragment.setContentUnit(unit);
            fragment.setFragmentType(FragmentType.TIMED_SEGMENT);
            fragment.setSequenceIndex(0);
            fragment.setText(text);
            fragment.setLocation(toJson(Map.of("section", "full page")));
            return List.of(contentFragmentRepository.save(fragment));
          });
    }

    return tx.execute(
        s -> {
          final var fragments = new ArrayList<ContentFragment>(sections.size());
          for (var i = 0; i < sections.size(); i++) {
            final var section = sections.get(i);
            final var sectionText = section.text().strip();
            if (sectionText.isEmpty()) continue;
            var fragment = new ContentFragment();
            fragment.setContentUnit(unit);
            fragment.setFragmentType(FragmentType.TIMED_SEGMENT);
            fragment.setSequenceIndex(i);
            fragment.setText(sectionText);
            fragment.setLocation(toJson(Map.of("section", section.heading())));
            fragments.add(contentFragmentRepository.save(fragment));
          }
          return fragments;
        });
  }

  private record Section(String heading, String text) {}

  private List<Section> splitBySections(final Document doc) {
    final var body = doc.body();
    if (body == null) return List.of();

    final var headings = body.select("h1, h2, h3, h4, h5, h6");
    if (headings.isEmpty()) return List.of();

    // Walk all top-level elements in order, grouping content under headings.
    // This handles complex DOM structures (e.g. Wikipedia tables wrapped in divs)
    // where nextElementSibling-based traversal misses nested content.
    final var sections = new ArrayList<Section>();
    String currentHeading = null;
    final var currentContent = new StringBuilder();

    for (final var child : body.children()) {
      final var childHeading = child.selectFirst("h1, h2, h3, h4, h5, h6");
      if (isHeading(child) || (childHeading != null && childHeading == child.children().first())) {
        // Flush previous section
        if (currentHeading != null && !currentContent.isEmpty()) {
          sections.add(new Section(currentHeading, currentContent.toString().strip()));
        }
        currentHeading = childHeading != null ? childHeading.text().strip() : child.text().strip();
        currentContent.setLength(0);
        currentContent.append(currentHeading).append("\n");
      } else if (currentHeading != null) {
        final var text = child.text().strip();
        if (!text.isEmpty()) {
          currentContent.append(text).append("\n");
        }
      }
    }
    // Flush last section
    if (currentHeading != null && !currentContent.isEmpty()) {
      sections.add(new Section(currentHeading, currentContent.toString().strip()));
    }

    return sections;
  }

  private boolean isHeading(final Element element) {
    return HEADING_PATTERN.matcher(element.tagName()).matches();
  }

  private String toJson(final Map<String, ?> map) {
    try {
      return objectMapper.writeValueAsString(map);
    } catch (JsonProcessingException e) {
      log.warn("Could not serialise location metadata: {}", e.getMessage());
      return "{}";
    }
  }
}
