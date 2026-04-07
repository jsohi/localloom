package com.localloom.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.localloom.service.MlSidecarClient;
import com.localloom.service.OllamaService;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class HealthControllerTest {

  private final DataSource dataSource = mock(DataSource.class);
  private final OllamaService ollamaService = mock(OllamaService.class);
  private final MlSidecarClient mlSidecarClient = mock(MlSidecarClient.class);
  private final HealthController controller =
      new HealthController(dataSource, ollamaService, mlSidecarClient);

  @Test
  void postgresReportsUpWhenConnectionIsValid() throws SQLException {
    final var conn = mock(Connection.class);
    when(dataSource.getConnection()).thenReturn(conn);
    when(conn.isValid(3)).thenReturn(true);
    when(ollamaService.isHealthy()).thenReturn(true);
    when(mlSidecarClient.isHealthy()).thenReturn(true);

    final var result = controller.health();

    assertThat(result.get("status")).isEqualTo("UP");
    assertThat(components(result)).containsEntry("postgres", "UP");
  }

  @Test
  void postgresReportsDownWhenConnectionIsInvalid() throws SQLException {
    final var conn = mock(Connection.class);
    when(dataSource.getConnection()).thenReturn(conn);
    when(conn.isValid(3)).thenReturn(false);
    when(ollamaService.isHealthy()).thenReturn(true);
    when(mlSidecarClient.isHealthy()).thenReturn(true);

    final var result = controller.health();

    assertThat(result.get("status")).isEqualTo("DEGRADED");
    assertThat(components(result)).containsEntry("postgres", "DOWN");
  }

  @Test
  void postgresReportsDownWhenGetConnectionThrows() throws SQLException {
    when(dataSource.getConnection()).thenThrow(new SQLException("pool exhausted"));
    when(ollamaService.isHealthy()).thenReturn(true);
    when(mlSidecarClient.isHealthy()).thenReturn(true);

    final var result = controller.health();

    assertThat(result.get("status")).isEqualTo("DEGRADED");
    assertThat(components(result)).containsEntry("postgres", "DOWN");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> components(final Map<String, Object> result) {
    return (Map<String, String>) result.get("components");
  }
}
