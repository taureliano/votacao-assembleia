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
  String contentType;
  String serverHeader;

  @BeforeEach
  void setup() throws Exception {
    contentType = null;
    serverHeader = null;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/users/",
        exchange -> {
          if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
          }
          if (serverHeader != null) {
            exchange.getResponseHeaders().set("Server", serverHeader);
          }
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

  @Test
  void heroku404WithInvalidCpfReturns404() {
    code = 404;
    contentType = "text/html; charset=utf-8";
    serverHeader = "Heroku";
    body = "<!DOCTYPE html><html><head><title>No such app</title></head><body></body></html>";
    assertThatThrownBy(() -> client.validate("12345678901"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status.value()).isEqualTo(404));
  }

  @Test
  void heroku404WithValidCpfAbleToVote() {
    code = 404;
    contentType = "text/html; charset=utf-8";
    serverHeader = "Heroku";
    body = "<!DOCTYPE html><html><head><title>No such app</title></head><body></body></html>";
    var deterministicClient =
        new CpfClient(
            true,
            "http://127.0.0.1:" + server.getAddress().getPort(),
            true,
            new ObjectMapper(),
            java.net.http.HttpClient.newHttpClient(),
            new java.util.Random() {
              @Override
              public boolean nextBoolean() {
                return true;
              }
            });
    assertThatCode(() -> deterministicClient.validate("52998224725"))
        .doesNotThrowAnyException();
  }

  @Test
  void heroku404WithValidCpfUnableToVote() {
    code = 404;
    contentType = "text/html; charset=utf-8";
    serverHeader = "Heroku";
    body = "<!DOCTYPE html><html><head><title>No such app</title></head><body></body></html>";
    var deterministicClient =
        new CpfClient(
            true,
            "http://127.0.0.1:" + server.getAddress().getPort(),
            true,
            new ObjectMapper(),
            java.net.http.HttpClient.newHttpClient(),
            new java.util.Random() {
              @Override
              public boolean nextBoolean() {
                return false;
              }
            });
    assertThatThrownBy(() -> deterministicClient.validate("52998224725"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status.value()).isEqualTo(403));
  }

  @Test
  void testIsValidCpf() {
    assertThat(CpfClient.isValidCpf("52998224725")).isTrue();
    assertThat(CpfClient.isValidCpf("11144477735")).isTrue();
    assertThat(CpfClient.isValidCpf("01234567890")).isTrue();

    assertThat(CpfClient.isValidCpf("12345678901")).isFalse();
    assertThat(CpfClient.isValidCpf("00000000000")).isFalse();
    assertThat(CpfClient.isValidCpf("11111111111")).isFalse();
    assertThat(CpfClient.isValidCpf("99999999999")).isFalse();
    assertThat(CpfClient.isValidCpf("123")).isFalse();
    assertThat(CpfClient.isValidCpf(null)).isFalse();
  }
}
