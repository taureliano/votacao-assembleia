package br.com.desafio.votacao;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CpfClient {
  private static final Logger log = LoggerFactory.getLogger(CpfClient.class);
  private final boolean enabled;
  private final String baseUrl;
  private final boolean fallbackEnabled;
  private final ObjectMapper mapper;
  private final HttpClient client;
  private final Random random;

  @Autowired
  public CpfClient(
      @Value("${app.cpf.enabled}") boolean enabled,
      @Value("${app.cpf.base-url}") String baseUrl,
      @Value("${app.cpf.fallback-enabled:true}") boolean fallbackEnabled,
      ObjectMapper mapper) {
    this(
        enabled,
        baseUrl,
        fallbackEnabled,
        mapper,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
        new Random());
  }

  public CpfClient(
      boolean enabled,
      String baseUrl,
      ObjectMapper mapper) {
    this(enabled, baseUrl, true, mapper);
  }

  public CpfClient(
      boolean enabled,
      String baseUrl,
      boolean fallbackEnabled,
      ObjectMapper mapper,
      HttpClient client,
      Random random) {
    this.enabled = enabled;
    this.baseUrl = baseUrl;
    this.fallbackEnabled = fallbackEnabled;
    this.mapper = mapper;
    this.client = client;
    this.random = random;
  }

  public void validate(String cpf) {
    if (!enabled) return;
    if (cpf == null || !cpf.matches("[0-9]{11}")) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "CPF deve conter 11 dígitos.");
    }

    try {
      var request =
          HttpRequest.newBuilder(URI.create(baseUrl + "/users/" + cpf))
              .timeout(Duration.ofSeconds(3))
              .GET()
              .build();
      log.debug("Consultando CPF url={}", request.uri());

      HttpResponse<String> response;
      try {
        response = client.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (IOException ex) {
        log.warn("Falha de conexão com serviço externo de CPF ({})", ex.getMessage());
        if (fallbackEnabled) {
          log.warn("Ativando tratamento de fallback para validação de CPF.");
          handleFallback(cpf);
          return;
        }
        throw unavailable();
      }

      log.debug("Resposta da validação de CPF: status={} body={}", response.statusCode(), response.body());

      if (isHerokuError(response)) {
        log.warn("Serviço de CPF no Heroku indisponível ou inexistente (status={} body={}). Aplicando tratamento de fallback.",
            response.statusCode(), response.body());
        handleFallback(cpf);
        return;
      }

      if (response.statusCode() == 404) {
        throw new ApiException(HttpStatus.NOT_FOUND, "CPF inválido.");
      }

      if (response.statusCode() != 200) {
        throw unavailable();
      }

      String status = mapper.readTree(response.body()).path("status").asText();
      if ("UNABLE_TO_VOTE".equals(status)) {
        throw new ApiException(HttpStatus.FORBIDDEN, "Associado não habilitado para votar.");
      }
      if (!"ABLE_TO_VOTE".equals(status)) {
        throw unavailable();
      }
    } catch (ApiException ex) {
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.debug("Validação de CPF interrompida", ex);
      throw unavailable();
    } catch (Exception ex) {
      log.debug("Erro ao consultar serviço de CPF", ex);
      throw unavailable();
    }
  }

  private boolean isHerokuError(HttpResponse<String> response) {
    int code = response.statusCode();
    String contentType = response.headers().firstValue("Content-Type").orElse("");
    String server = response.headers().firstValue("Server").orElse("");
    String via = response.headers().firstValue("Via").orElse("");
    String body = response.body() != null ? response.body() : "";

    boolean isHtml = contentType.contains("text/html");
    boolean isHeroku = "Heroku".equalsIgnoreCase(server) || via.toLowerCase().contains("heroku");
    boolean isNoSuchApp = body.contains("No such app") || body.contains("no-such-app");

    return isNoSuchApp || (isHtml && isHeroku) || (code == 404 && isHtml);
  }

  private void handleFallback(String cpf) {
    if (!fallbackEnabled) {
      throw unavailable();
    }
    if (!isValidCpf(cpf)) {
      throw new ApiException(HttpStatus.NOT_FOUND, "CPF inválido.");
    }
    boolean able = random.nextBoolean();
    if (!able) {
      throw new ApiException(HttpStatus.FORBIDDEN, "Associado não habilitado para votar.");
    }
  }

  public static boolean isValidCpf(String cpf) {
    if (cpf == null || cpf.length() != 11 || !cpf.matches("\\d{11}")) {
      return false;
    }

    boolean allSame = true;
    for (int i = 1; i < 11; i++) {
      if (cpf.charAt(i) != cpf.charAt(0)) {
        allSame = false;
        break;
      }
    }
    if (allSame) {
      return false;
    }

    int sum1 = 0;
    for (int i = 0; i < 9; i++) {
      sum1 += (cpf.charAt(i) - '0') * (10 - i);
    }
    int d1 = 11 - (sum1 % 11);
    if (d1 >= 10) {
      d1 = 0;
    }
    if (d1 != (cpf.charAt(9) - '0')) {
      return false;
    }

    int sum2 = 0;
    for (int i = 0; i < 10; i++) {
      sum2 += (cpf.charAt(i) - '0') * (11 - i);
    }
    int d2 = 11 - (sum2 % 11);
    if (d2 >= 10) {
      d2 = 0;
    }
    return d2 == (cpf.charAt(10) - '0');
  }

  private ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE, "Validação de CPF indisponível. Tente novamente.");
  }
}

