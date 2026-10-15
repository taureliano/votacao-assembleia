package br.com.desafio.votacao;

import static br.com.desafio.votacao.Models.*;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/** Contrato server-driven UI do Anexo 1: callbacks são sempre POST. */
@RestController
@RequestMapping("/api/v1/mobile")
public class MobileController {
  private final VotingService service;
  private final String base;

  public MobileController(VotingService service, @Value("${app.callback-base-url}") String base) {
    this.service = service;
    this.base = base.replaceAll("/+$", "") + "/api/v1/mobile";
  }

  private Map<String, Object> action(String text, String path, Map<String, ?> body) {
    return Map.of("texto", text, "url", base + path, "body", body);
  }

  private Map<String, Object> text(String value) {
    return Map.of("tipo", "TEXTO", "texto", value);
  }

  private Map<String, Object> input(String type, String id, String title, Object value) {
    return Map.of("tipo", type, "id", id, "titulo", title, "valor", value);
  }

  private Map<String, Object> form(String title, List<?> items, Map<String, Object> ok) {
    return Map.of(
        "tipo",
        "FORMULARIO",
        "titulo",
        title,
        "itens",
        items,
        "botaoOk",
        ok,
        "botaoCancelar",
        action("Voltar", "", Map.of()));
  }

  @RequestMapping(method = {RequestMethod.GET, RequestMethod.POST})
  public Map<String, Object> home() {
    return Map.of(
        "tipo",
        "SELECAO",
        "titulo",
        "Assembleia",
        "itens",
        List.of(
            action("Cadastrar pauta", "/nova", Map.of()),
            action("Listar pautas", "/lista", Map.of("pagina", 0))));
  }

  @PostMapping("/nova")
  public Map<String, Object> newForm() {
    return form(
        "Nova pauta",
        List.of(
            input("INPUT_TEXTO", "titulo", "Título", ""),
            input("INPUT_TEXTO", "descricao", "Descrição", "")),
        action("Cadastrar", "/pautas", Map.of()));
  }

  @PostMapping("/pautas")
  public Map<String, Object> create(@Valid @RequestBody NovaPauta body) {
    return detail(service.create(body).id());
  }

  public record Page(Integer pagina) {}

  @PostMapping("/lista")
  public Map<String, Object> list(@RequestBody(required = false) Page body) {
    int page = body == null || body.pagina() == null ? 0 : body.pagina();
    if (page < 0 || page > 1000000)
      throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "Página inválida.");
    var pautas = service.list(page);
    var items = new ArrayList<Map<String, Object>>();
    for (var p : pautas) items.add(action(p.titulo(), "/pautas/" + p.id(), Map.of()));
    if (pautas.size() == 50)
      items.add(action("Próxima página", "/lista", Map.of("pagina", page + 1)));
    items.add(action("Início", "", Map.of()));
    return Map.of("tipo", "SELECAO", "titulo", "Pautas", "itens", items);
  }

  @PostMapping("/pautas/{id}")
  public Map<String, Object> detail(@PathVariable UUID id) {
    var p = service.find(id);
    return Map.of(
        "tipo",
        "SELECAO",
        "titulo",
        p.titulo(),
        "itens",
        List.of(
            action("Abrir sessão", "/pautas/" + id + "/abertura", Map.of()),
            action("Votar", "/pautas/" + id + "/votar", Map.of()),
            action("Resultado", "/pautas/" + id + "/resultado", Map.of()),
            action("Início", "", Map.of())));
  }

  @PostMapping("/pautas/{id}/abertura")
  public Map<String, Object> opening(@PathVariable UUID id) {
    service.find(id);
    return form(
        "Abrir sessão",
        List.of(input("INPUT_NUMERO", "duracaoMinutos", "Duração em minutos", 1)),
        action("Abrir", "/pautas/" + id + "/sessao", Map.of()));
  }

  @PostMapping("/pautas/{id}/sessao")
  public Map<String, Object> open(@PathVariable UUID id, @Valid @RequestBody Abertura input) {
    service.open(id, input);
    return detail(id);
  }

  @PostMapping("/pautas/{id}/votar")
  public Map<String, Object> voteForm(@PathVariable UUID id) {
    service.session(id);
    return Map.of(
        "tipo",
        "FORMULARIO",
        "titulo",
        "Seu voto",
        "itens",
        List.of(
            input("INPUT_TEXTO", "associadoId", "Identificador do associado", ""),
            input("INPUT_TEXTO", "cpf", "CPF (11 dígitos; exigido se validação ativa)", "")),
        "botaoOk",
        action("Sim", "/pautas/" + id + "/votos", Map.of("voto", "SIM")),
        "botaoCancelar",
        action("Não", "/pautas/" + id + "/votos", Map.of("voto", "NAO")));
  }

  @PostMapping("/pautas/{id}/votos")
  public Map<String, Object> vote(@PathVariable UUID id, @Valid @RequestBody NovoVoto input) {
    service.vote(id, input);
    return form(
        "Voto recebido",
        List.of(text("Seu voto foi registrado.")),
        action("Ver resultado", "/pautas/" + id + "/resultado", Map.of()));
  }

  @PostMapping("/pautas/{id}/resultado")
  public Map<String, Object> result(@PathVariable UUID id) {
    var r = service.result(id);
    return form(
        "Resultado",
        List.of(
            text("Sessão: " + r.status()),
            text("Sim: " + r.sim() + " | Não: " + r.nao() + " | Total: " + r.total()),
            text(
                ("ENCERRADA".equals(r.status()) ? "Resultado final: " : "Resultado parcial: ")
                    + r.resultado())),
        action("Atualizar", "/pautas/" + id + "/resultado", Map.of()));
  }
}
