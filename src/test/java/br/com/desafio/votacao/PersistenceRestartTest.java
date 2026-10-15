package br.com.desafio.votacao;

import static br.com.desafio.votacao.Models.*;
import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;

class PersistenceRestartTest {
  @TempDir Path directory;

  @Test
  void votesSurviveRestart() {
    String url =
        "jdbc:h2:file:"
            + directory.resolve("persistent").toAbsolutePath()
            + ";DB_CLOSE_ON_EXIT=FALSE";
    UUID id;
    try (var first =
        new SpringApplicationBuilder(Application.class)
            .run("--server.port=0", "--spring.datasource.url=" + url, "--app.cpf.enabled=false")) {
      var service = first.getBean(VotingService.class);
      id = service.create(new NovaPauta("Persistência", "")).id();
      service.open(id, new Abertura(60L));
      service.vote(id, new NovoVoto("1", Escolha.SIM, null));
    }
    try (var second =
        new SpringApplicationBuilder(Application.class)
            .run("--server.port=0", "--spring.datasource.url=" + url, "--app.cpf.enabled=false")) {
      var service = second.getBean(VotingService.class);
      assertThat(service.find(id).titulo()).isEqualTo("Persistência");
      assertThat(service.result(id).sim()).isEqualTo(1);
      assertThatThrownBy(() -> service.vote(id, new NovoVoto("1", Escolha.NAO, null)))
          .hasMessageContaining("já votou");
    }
  }
}
