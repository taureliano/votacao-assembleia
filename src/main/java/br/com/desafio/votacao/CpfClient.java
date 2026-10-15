package br.com.desafio.votacao;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CpfClient {
  private static final Logger log = LoggerFactory.getLogger(CpfClient.class);
  private final boolean enabled;
  private final String baseUrl;
  private final ObjectMapper mapper;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  public CpfClient(
      @Value("${app.cpf.enabled}") boolean enabled,
      @Value("${app.cpf.base-url}") String baseUrl,
      ObjectMapper mapper) {
    this.enabled = enabled;
    this.baseUrl = baseUrl;
    this.mapper = mapper;
  }

  public void validate(String cpf) {
    if (!enabled) return;
    if (cpf == null || !cpf.matches("[0-9]{11}"))
      throw new ApiException(HttpStatus.BAD_REQUEST, "CPF deve conter 11 dígitos.");
    try {
      var request =
          HttpRequest.newBuilder(URI.create(baseUrl + "/users/" + cpf))
              .timeout(Duration.ofSeconds(3))
              .GET()
              .build();
      log.debug("Consultando CPF url={}", request.uri());
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      log.debug("Resposta da validação de CPF: status={} body={}", response.statusCode(), response.body());
      if (response.statusCode() == 404)
        throw new ApiException(HttpStatus.NOT_FOUND, "CPF inválido.");
      if (response.statusCode() != 200) throw unavailable();
      String status = mapper.readTree(response.body()).path("status").asText();
      if ("UNABLE_TO_VOTE".equals(status))
        throw new ApiException(HttpStatus.FORBIDDEN, "Associado não habilitado para votar.");
      if (!"ABLE_TO_VOTE".equals(status)) throw unavailable();
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

  private ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE, "Validação de CPF indisponível. Tente novamente.");
  }
}
