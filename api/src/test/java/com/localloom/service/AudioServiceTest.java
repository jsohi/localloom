package com.localloom.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AudioService's pure-logic helpers. These do not start Spring or spawn processes.
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

  /** Invokes the private static deriveExtension method via reflection. */
  private static String invokeDeriveExtension(final String url) throws Exception {
    Method method = AudioService.class.getDeclaredMethod("deriveExtension", String.class);
    method.setAccessible(true);
    return (String) method.invoke(null, url);
  }
}
