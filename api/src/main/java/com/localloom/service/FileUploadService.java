package com.localloom.service;

import com.localloom.model.ContentFragment;
import com.localloom.model.ContentType;
import com.localloom.model.ContentUnit;
import com.localloom.model.ContentUnitStatus;
import com.localloom.model.FragmentType;
import com.localloom.model.Source;
import com.localloom.model.SourceType;
import com.localloom.model.SyncStatus;
import com.localloom.repository.ContentFragmentRepository;
import com.localloom.repository.ContentUnitRepository;
import com.localloom.repository.SourceRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileUploadService {

  private static final Logger log = LogManager.getLogger(FileUploadService.class);

  private final SourceRepository sourceRepository;
  private final ContentUnitRepository contentUnitRepository;
  private final ContentFragmentRepository contentFragmentRepository;
  private final EmbeddingService embeddingService;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate tx;
  private final Path uploadDir;

  public FileUploadService(
      final SourceRepository sourceRepository,
      final ContentUnitRepository contentUnitRepository,
      final ContentFragmentRepository contentFragmentRepository,
      final EmbeddingService embeddingService,
      final ObjectMapper objectMapper,
      final TransactionTemplate transactionTemplate,
      @Value("${localloom.upload.dir:data/uploads}") final String uploadDirPath) {
    this.sourceRepository = sourceRepository;
    this.contentUnitRepository = contentUnitRepository;
    this.contentFragmentRepository = contentFragmentRepository;
    this.embeddingService = embeddingService;
    this.objectMapper = objectMapper;
    this.tx = transactionTemplate;
    this.uploadDir = Path.of(uploadDirPath);
  }

  public record UploadResult(UUID sourceId, Source source) {}

  public UploadResult processUpload(
      final MultipartFile file, final String name, final SourceType sourceType) {
    try {
      Files.createDirectories(uploadDir);

      final var rawName =
          file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
      // Sanitize: strip path separators to prevent directory traversal
      final var filename = Path.of(rawName).getFileName().toString();
      final var storedPath = uploadDir.resolve(UUID.randomUUID() + "_" + filename);
      file.transferTo(storedPath);

      log.info("File stored at: {} ({})", storedPath, file.getSize());

      final var source =
          tx.execute(
              s -> {
                var src = new Source();
                src.setSourceType(sourceType);
                src.setName(name);
                src.setOriginUrl("upload://" + filename);
                src.setSyncStatus(SyncStatus.SYNCING);
                return sourceRepository.save(src);
              });

      final var text = extractText(storedPath, filename);

      final var unit =
          tx.execute(
              s -> {
                var cu = new ContentUnit();
                cu.setSource(source);
                cu.setTitle(name);
                cu.setContentType(ContentType.TEXT_FILE);
                cu.setExternalId(storedPath.getFileName().toString());
                cu.setExternalUrl("upload://" + filename);
                cu.setStatus(ContentUnitStatus.EXTRACTING);
                cu.setPublishedAt(Instant.now());
                cu.setRawText(text);
                return contentUnitRepository.save(cu);
              });

      final var fragments = createFragments(unit, text, filename);

      tx.executeWithoutResult(
          s -> {
            unit.setStatus(ContentUnitStatus.EMBEDDING);
            contentUnitRepository.save(unit);
          });

      embeddingService.embedContent(
          source.getId(),
          unit.getId(),
          name,
          sourceType,
          ContentType.TEXT_FILE,
          fragments);

      tx.executeWithoutResult(
          s -> {
            unit.setStatus(ContentUnitStatus.INDEXED);
            contentUnitRepository.save(unit);
            source.setSyncStatus(SyncStatus.IDLE);
            source.setLastSyncedAt(Instant.now());
            sourceRepository.save(source);
          });

      log.info("File upload complete: sourceId={} filename={}", source.getId(), filename);
      return new UploadResult(source.getId(), source);

    } catch (IOException e) {
      throw new FileUploadException("Failed to process uploaded file: " + e.getMessage(), e);
    }
  }

  private String extractText(final Path filePath, final String filename) throws IOException {
    final var lowerName = filename.toLowerCase();
    if (lowerName.endsWith(".pdf")) {
      return extractPdfText(filePath);
    }
    return Files.readString(filePath, StandardCharsets.UTF_8);
  }

  private String extractPdfText(final Path filePath) throws IOException {
    try (var document = Loader.loadPDF(filePath.toFile())) {
      var stripper = new PDFTextStripper();
      return stripper.getText(document);
    }
  }

  private List<ContentFragment> createFragments(
      final ContentUnit unit, final String text, final String filename) {
    final var lowerName = filename.toLowerCase();
    if (lowerName.endsWith(".pdf")) {
      return createPdfFragments(unit, text);
    }
    return createSingleFragment(unit, text, filename);
  }

  private List<ContentFragment> createPdfFragments(final ContentUnit unit, final String fullText) {
    // Split by form feed (page break) if present, otherwise treat as single page
    final var pages = fullText.split("\\f");
    return tx.execute(
        s -> {
          final var fragments = new ArrayList<ContentFragment>(pages.length);
          for (var i = 0; i < pages.length; i++) {
            final var pageText = pages[i].strip();
            if (pageText.isEmpty()) continue;
            var fragment = new ContentFragment();
            fragment.setContentUnit(unit);
            fragment.setFragmentType(FragmentType.TIMED_SEGMENT);
            fragment.setSequenceIndex(i);
            fragment.setText(pageText);
            fragment.setLocation(toJson(Map.of("page", i + 1)));
            fragments.add(contentFragmentRepository.save(fragment));
          }
          return fragments;
        });
  }

  private List<ContentFragment> createSingleFragment(
      final ContentUnit unit, final String text, final String filename) {
    return tx.execute(
        s -> {
          var fragment = new ContentFragment();
          fragment.setContentUnit(unit);
          fragment.setFragmentType(FragmentType.TIMED_SEGMENT);
          fragment.setSequenceIndex(0);
          fragment.setText(text.strip());
          fragment.setLocation(toJson(Map.of("file", filename)));
          return List.of(contentFragmentRepository.save(fragment));
        });
  }

  private String toJson(final Map<String, ?> map) {
    try {
      return objectMapper.writeValueAsString(map);
    } catch (JsonProcessingException e) {
      log.warn("Could not serialise location metadata: {}", e.getMessage());
      return "{}";
    }
  }

  public static class FileUploadException extends RuntimeException {
    public FileUploadException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }
}
