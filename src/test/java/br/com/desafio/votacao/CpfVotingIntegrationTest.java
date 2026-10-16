package br.com.desafio.votacao;

import static br.com.desafio.votacao.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:test_cpf;DB_CLOSE_DELAY=-1",
      "app.callback-base-url=https://mobile.example",
      "app.cpf.enabled=true",
      "app.cpf.fallback-enabled=true"
    })
@AutoConfigureMockMvc
class CpfVotingIntegrationTest {
  @Autowired VotingService service;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @MockitoBean Clock clock;
  final Instant start = Instant.parse("2026-10-14T12:00:00Z");

  @BeforeEach
  void reset() {
    when(clock.instant()).thenReturn(start);
    db.update("DELETE FROM voto");
    db.update("DELETE FROM sessao");
    db.update("DELETE FROM pauta");
  }

  UUID pauta() {
    return service.create(new NovaPauta("Pauta com CPF", "Descricao")).id();
  }

  @Test
  void voteWithoutCpfWhenEnabledReturnsBadRequest() throws Exception {
    var id = pauta();
    service.open(id, null);

    // Sem campo cpf
    mvc.perform(
            post("/api/v1/pautas/" + id + "/votos")
                .contentType("application/json")
                .content("{\"associadoId\":\"associado-1\",\"voto\":\"SIM\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("CPF deve conter 11 dígitos."));
  }

  @Test
  void voteWithInvalidCpfReturnsNotFound() throws Exception {
    var id = pauta();
    service.open(id, null);

    // 12345678901 possui 11 dígitos numéricos mas dígitos verificadores inválidos
    mvc.perform(
            post("/api/v1/pautas/" + id + "/votos")
                .contentType("application/json")
                .content("{\"associadoId\":\"associado-1\",\"voto\":\"SIM\",\"cpf\":\"12345678901\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("CPF inválido."));
  }

  @Test
  void voteWithValidCpfReturnsEitherSuccessOrForbidden() throws Exception {
    var id = pauta();
    service.open(id, null);

    // 52998224725 é um CPF válido com dígitos verificadores corretos
    var result =
        mvc.perform(
                post("/api/v1/pautas/" + id + "/votos")
                    .contentType("application/json")
                    .content("{\"associadoId\":\"associado-1\",\"voto\":\"SIM\",\"cpf\":\"52998224725\"}"))
            .andReturn();

    int status = result.getResponse().getStatus();
    assertThat(status).isIn(204, 403);
    if (status == 403) {
      assertThat(result.getResponse().getContentAsString())
          .contains("Associado não habilitado para votar.");
    }
  }
}
