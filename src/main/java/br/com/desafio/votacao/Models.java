package br.com.desafio.votacao;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class Models {
  private Models() {}

  public enum Escolha {
    SIM,
    NAO
  }

  public record NovaPauta(
      @NotBlank @Size(max = 200) String titulo, @Size(max = 2000) String descricao) {}

  public record Abertura(@Min(1) @Max(10080) Long duracaoMinutos) {}

  public record NovoVoto(
      @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String associadoId,
      @NotNull Escolha voto,
      @Pattern(regexp = "(?:[0-9]{11})?") String cpf) {}

  public record Pauta(UUID id, String titulo, String descricao, Instant criadaEm) {}

  public record Sessao(UUID pautaId, Instant abertaEm, Instant encerraEm) {}

  public record Resultado(
      UUID pautaId, String status, long sim, long nao, long total, String resultado) {}
}
