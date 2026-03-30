package com.localloom.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AudioService's pure-logic helpers and constants. These do not start Spring or
 * spawn processes.
 */
class AudioServiceTest {

  @Test
  void deriveExtensionFromMp3Url() throws Exception {
    assertThat(invokeDeriveExtension("https://cdn.example.com/episode01.mp3")).isEqualTo(".mp3");
  }

  @Test
  void deriveExtensionFromUrlWithQueryParams() throws Exception {
    assertThat(invokeDeriveExtension("https://cdn.example.com/ep.m4a?token=abc")).isEqualTo(".m4a");
  }

  @Test
  void deriveExtensionFromUrlWithNoExtension() throws Exception {
    assertThat(invokeDeriveExtension("https://cdn.example.com/audio/stream")).isEqualTo(".mp3");
  }

  @Test
  void deriveExtensionFromWavUrl() throws Exception {
    assertThat(invokeDeriveExtension("https://cdn.example.com/clip.wav")).isEqualTo(".wav");
  }

  @Test
  void constantsHaveExpectedValues() throws Exception {
    var maxRetries =
        AudioServiceTest.class.getClassLoader().loadClass("com.localloom.service.AudioService");
    var field = maxRetries.getDeclaredField("MAX_RETRIES");
    field.setAccessible(true);
    assertThat(field.getInt(null)).isEqualTo(3);

    var retryDelay = maxRetries.getDeclaredField("RETRY_DELAY_MS");
    retryDelay.setAccessible(true);
    assertThat(retryDelay.getLong(null)).isEqualTo(2_000L);

    var timeout = maxRetries.getDeclaredField("PROCESS_TIMEOUT_MINUTES");
    timeout.setAccessible(true);
    assertThat(timeout.getLong(null)).isEqualTo(30L);
  }

  /** Invokes the private static deriveExtension method via reflection. */
  private static String invokeDeriveExtension(final String url) throws Exception {
    Method method = AudioService.class.getDeclaredMethod("deriveExtension", String.class);
    method.setAccessible(true);
    return (String) method.invoke(null, url);
  }
}
