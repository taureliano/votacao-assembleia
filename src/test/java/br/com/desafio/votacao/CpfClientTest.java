package br.com.desafio.votacao;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;

class CpfClientTest {
  HttpServer server;
  CpfClient client;
  int code;
  String body;

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/users/",
        exchange -> {
          var bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(code, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    client =
        new CpfClient(
            true, "http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void able() {
    code = 200;
    body = "{\"status\":\"ABLE_TO_VOTE\"}";
    assertThatCode(() -> client.validate("12345678901")).doesNotThrowAnyException();
  }

  @Test
  void unable() {
    code = 200;
    body = "{\"status\":\"UNABLE_TO_VOTE\"}";
    assertThatThrownBy(() -> client.validate("12345678901"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status.value()).isEqualTo(403));
  }

  @Test
  void invalid() {
    code = 404;
    body = "{}";
    assertThatThrownBy(() -> client.validate("12345678901"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status.value()).isEqualTo(404));
  }

  @Test
  void upstreamError() {
    code = 500;
    body = "{}";
    assertThatThrownBy(() -> client.validate("12345678901"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status.value()).isEqualTo(503));
  }

  @Test
  void malformedResponse() {
    code = 200;
    body = "not-json";
    assertThatThrownBy(() -> client.validate("12345678901"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status.value()).isEqualTo(503));
  }

  @Test
  void missingStatusFailsClosed() {
    code = 200;
    body = "{}";
    assertThatThrownBy(() -> client.validate("12345678901")).isInstanceOf(ApiException.class);
  }

  @Test
  void missingCpf() {
    assertThatThrownBy(() -> client.validate(null))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status.value()).isEqualTo(400));
  }

  @Test
  void disabledDoesNotCallExternalService() {
    var disabled = new CpfClient(false, "http://invalid", new ObjectMapper());
    assertThatCode(() -> disabled.validate(null)).doesNotThrowAnyException();
  }
}
