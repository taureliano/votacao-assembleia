package br.com.desafio.votacao;

import static br.com.desafio.votacao.Models.*;
import static org.springframework.http.HttpStatus.*;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VotingService {
  private static final Logger log = LoggerFactory.getLogger(VotingService.class);
  private final JdbcTemplate db;
  private final Clock clock;
  private final CpfClient cpf;

  public VotingService(JdbcTemplate db, Clock clock, CpfClient cpf) {
    this.db = db;
    this.clock = clock;
    this.cpf = cpf;
  }

  public Pauta create(NovaPauta input) {
    var p =
        new Pauta(
            UUID.randomUUID(),
            input.titulo().trim(),
            Objects.requireNonNullElse(input.descricao(), ""),
            clock.instant());
    db.update(
        "INSERT INTO pauta VALUES (?,?,?,?)",
        p.id(),
        p.titulo(),
        p.descricao(),
        Timestamp.from(p.criadaEm()));
    log.info("Pauta criada id={}", p.id());
    return p;
  }

  public Pauta find(UUID id) {
    return db
        .query(
            "SELECT * FROM pauta WHERE id=?",
            (rs, n) ->
                new Pauta(
                    rs.getObject("id", UUID.class),
                    rs.getString("titulo"),
                    rs.getString("descricao"),
                    rs.getTimestamp("criada_em").toInstant()),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiException(NOT_FOUND, "Pauta não encontrada."));
  }

  public List<Pauta> list(int page) {
    return db.query(
        "SELECT * FROM pauta ORDER BY criada_em DESC, id LIMIT 50 OFFSET ?",
        (rs, n) ->
            new Pauta(
                rs.getObject("id", UUID.class),
                rs.getString("titulo"),
                rs.getString("descricao"),
                rs.getTimestamp("criada_em").toInstant()),
        (long) page * 50);
  }

  public Sessao session(UUID id) {
    find(id);
    return db
        .query(
            "SELECT * FROM sessao WHERE pauta_id=?",
            (rs, n) ->
                new Sessao(
                    id,
                    rs.getTimestamp("aberta_em").toInstant(),
                    rs.getTimestamp("encerra_em").toInstant()),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiException(CONFLICT, "Sessão ainda não aberta."));
  }

  public Sessao open(UUID id, Abertura input) {
    find(id);
    var now = clock.instant();
    long minutes = input == null || input.duracaoMinutos() == null ? 1 : input.duracaoMinutos();
    var session = new Sessao(id, now, now.plus(Duration.ofMinutes(minutes)));
    try {
      db.update(
          "INSERT INTO sessao VALUES (?,?,?)",
          id,
          Timestamp.from(now),
          Timestamp.from(session.encerraEm()));
    } catch (DuplicateKeyException ex) {
      throw new ApiException(CONFLICT, "Já existe sessão para esta pauta.");
    }
    log.info("Sessão aberta pauta={} encerraEm={}", id, session.encerraEm());
    return session;
  }

  public void vote(UUID id, NovoVoto input) {
    // A chamada remota fica fora da transação e antes da checagem definitiva de prazo.
    session(id);
    cpf.validate(input.cpf());
    persistVote(id, input);
  }

  private void persistVote(UUID id, NovoVoto input) {
    var now = clock.instant();
    // INSERT condicional: valida o prazo na mesma operação que grava, sem bloquear a pauta.
    try {
      int rows =
          db.update(
              "INSERT INTO voto (id,pauta_id,associado_id,escolha,criado_em) SELECT ?,?,?,?,? FROM"
                  + " sessao WHERE pauta_id=? AND aberta_em<=? AND encerra_em>?",
              UUID.randomUUID(),
              id,
              input.associadoId(),
              input.voto().name(),
              Timestamp.from(now),
              id,
              Timestamp.from(now),
              Timestamp.from(now));
      if (rows == 0) throw new ApiException(CONFLICT, "Sessão encerrada.");
    } catch (DuplicateKeyException ex) {
      throw new ApiException(CONFLICT, "Associado já votou nesta pauta.");
    }
    log.info("Voto registrado pauta={}", id);
  }

  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public Resultado result(UUID id) {
    find(id);
    var sessions =
        db.query(
            "SELECT * FROM sessao WHERE pauta_id=?",
            (rs, n) ->
                new Sessao(
                    id,
                    rs.getTimestamp("aberta_em").toInstant(),
                    rs.getTimestamp("encerra_em").toInstant()),
            id);
    String status =
        sessions.isEmpty()
            ? "NAO_ABERTA"
            : clock.instant().isBefore(sessions.get(0).encerraEm()) ? "ABERTA" : "ENCERRADA";
    var counts =
        db.queryForMap(
            "SELECT COUNT(CASE WHEN escolha='SIM' THEN 1 END) AS sim, COUNT(CASE WHEN escolha='NAO'"
                + " THEN 1 END) AS nao FROM voto WHERE pauta_id=?",
            id);
    long sim = ((Number) counts.get("sim")).longValue(),
        nao = ((Number) counts.get("nao")).longValue();
    return new Resultado(
        id,
        status,
        sim,
        nao,
        sim + nao,
        sim == nao ? "EMPATE" : sim > nao ? "APROVADA" : "REJEITADA");
  }
}
