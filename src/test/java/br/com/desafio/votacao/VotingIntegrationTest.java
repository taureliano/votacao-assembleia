package br.com.desafio.votacao;

import static br.com.desafio.votacao.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
      "app.callback-base-url=https://mobile.example",
      "app.cpf.enabled=false"
    })
@AutoConfigureMockMvc
class VotingIntegrationTest {
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
    return service.create(new NovaPauta("Nova sede", "Reforma")).id();
  }

  @Test
  void defaultSessionAndTally() {
    var id = pauta();
    assertThat(service.result(id).status()).isEqualTo("NAO_ABERTA");
    assertThat(service.open(id, new Abertura(null)).encerraEm()).isEqualTo(start.plusSeconds(60));
    service.vote(id, new NovoVoto("1", Escolha.SIM, null));
    service.vote(id, new NovoVoto("2", Escolha.NAO, null));
    var r = service.result(id);
    assertThat(r.total()).isEqualTo(2);
    assertThat(r.resultado()).isEqualTo("EMPATE");
  }

  @Test
  void exactDeadlineIsClosed() {
    var id = pauta();
    service.open(id, null);
    when(clock.instant()).thenReturn(start.plusSeconds(60));
    assertThatThrownBy(() -> service.vote(id, new NovoVoto("1", Escolha.SIM, null)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("encerrada");
    assertThat(service.result(id).status()).isEqualTo("ENCERRADA");
  }

  @Test
  void customDurationAndNoReopening() {
    var id = pauta();
    assertThat(service.open(id, new Abertura(5L)).encerraEm()).isEqualTo(start.plusSeconds(300));
    when(clock.instant()).thenReturn(start.plusSeconds(301));
    assertThatThrownBy(() -> service.open(id, null)).isInstanceOf(ApiException.class);
  }

  @Test
  void cannotVoteWithoutSession() {
    var id = pauta();
    assertThatThrownBy(() -> service.vote(id, new NovoVoto("1", Escolha.SIM, null)))
        .hasMessageContaining("não aberta");
  }

  @Test
  void oneVotePerPautaButAllowedOnAnother() {
    var a = pauta();
    var b = pauta();
    service.open(a, null);
    service.open(b, null);
    service.vote(a, new NovoVoto("1", Escolha.SIM, null));
    assertThatThrownBy(() -> service.vote(a, new NovoVoto("1", Escolha.NAO, null)))
        .hasMessageContaining("já votou");
    service.vote(b, new NovoVoto("1", Escolha.NAO, null));
    assertThat(service.result(a).sim()).isEqualTo(1);
    assertThat(service.result(b).nao()).isEqualTo(1);
  }

  @Test
  void concurrentDuplicateHasExactlyOneWinner() throws Exception {
    var id = pauta();
    service.open(id, null);
    var executor = Executors.newFixedThreadPool(12);
    try {
      var jobs = new ArrayList<Callable<Boolean>>();
      for (int i = 0; i < 30; i++)
        jobs.add(
            () -> {
              try {
                service.vote(id, new NovoVoto("same", Escolha.SIM, null));
                return true;
              } catch (ApiException ex) {
                assertThat(ex.status.value()).isEqualTo(409);
                return false;
              }
            });
      long successes = 0;
      for (var f : executor.invokeAll(jobs)) if (f.get()) successes++;
      assertThat(successes).isEqualTo(1);
      assertThat(service.result(id).total()).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentDistinctVotesAreNotLost() throws Exception {
    var id = pauta();
    service.open(id, null);
    var executor = Executors.newFixedThreadPool(12);
    try {
      var jobs = new ArrayList<Callable<Void>>();
      for (int i = 0; i < 100; i++) {
        var user = "u" + i;
        jobs.add(
            () -> {
              service.vote(id, new NovoVoto(user, Escolha.SIM, null));
              return null;
            });
      }
      for (var f : executor.invokeAll(jobs)) f.get();
      assertThat(service.result(id).sim()).isEqualTo(100);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void validatesHttpInputsAndNotFound() throws Exception {
    mvc.perform(
            post("/api/v1/pautas").contentType("application/json").content("{\"titulo\":\" \"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/pautas/" + UUID.randomUUID())).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/pautas/not-a-uuid")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/pautas?pagina=-1")).andExpect(status().isBadRequest());
    var id = pauta();
    mvc.perform(
            post("/api/v1/pautas/" + id + "/sessao")
                .contentType("application/json")
                .content("{\"duracaoMinutos\":0}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/pautas/" + id + "/votos")
                .contentType("application/json")
                .content("{\"associadoId\":\"1\",\"voto\":\"TALVEZ\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void restFlow() throws Exception {
    var id = pauta();
    mvc.perform(post("/api/v1/pautas/" + id + "/sessao")).andExpect(status().isCreated());
    mvc.perform(
            post("/api/v1/pautas/" + id + "/votos")
                .contentType("application/json")
                .content("{\"associadoId\":\"a\",\"voto\":\"SIM\"}"))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/pautas/" + id + "/resultado"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sim").value(1))
        .andExpect(jsonPath("$.resultado").value("APROVADA"));
  }

  @Test
  void mobileContractAndPostCallbacks() throws Exception {
    mvc.perform(get("/api/v1/mobile"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tipo").value("SELECAO"));
    mvc.perform(post("/api/v1/mobile")).andExpect(status().isOk());
    mvc.perform(post("/api/v1/mobile/nova"))
        .andExpect(jsonPath("$.tipo").value("FORMULARIO"))
        .andExpect(jsonPath("$.itens[0].tipo").value("INPUT_TEXTO"))
        .andExpect(jsonPath("$.botaoOk.url").value("https://mobile.example/api/v1/mobile/pautas"));
    mvc.perform(
            post("/api/v1/mobile/pautas")
                .contentType("application/json")
                .content("{\"titulo\":\"Reforma\"}"))
        .andExpect(jsonPath("$.tipo").value("SELECAO"));
    var id = pauta();
    service.open(id, null);
    mvc.perform(post("/api/v1/mobile/pautas/" + id + "/votar"))
        .andExpect(jsonPath("$.botaoOk.body.voto").value("SIM"))
        .andExpect(jsonPath("$.botaoCancelar.body.voto").value("NAO"));
    mvc.perform(
            post("/api/v1/mobile/pautas/" + id + "/votos")
                .contentType("application/json")
                .content("{\"associadoId\":\"mobile\",\"voto\":\"NAO\",\"cpf\":\"\"}"))
        .andExpect(jsonPath("$.tipo").value("FORMULARIO"));
    mvc.perform(post("/api/v1/mobile/pautas/" + id + "/resultado"))
        .andExpect(jsonPath("$.titulo").value("Resultado"));
  }
}
