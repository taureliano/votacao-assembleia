package br.com.desafio.votacao;

import static br.com.desafio.votacao.Models.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/pautas")
@Validated
public class VotingController {
  private final VotingService service;

  public VotingController(VotingService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<Pauta> create(@Valid @RequestBody NovaPauta input) {
    var p = service.create(input);
    return ResponseEntity.created(URI.create("/api/v1/pautas/" + p.id())).body(p);
  }

  @GetMapping
  public List<Pauta> list(@RequestParam(defaultValue = "0") @Min(0) int pagina) {
    return service.list(pagina);
  }

  @GetMapping("/{id}")
  public Pauta get(@PathVariable UUID id) {
    return service.find(id);
  }

  @PostMapping("/{id}/sessao")
  public ResponseEntity<Sessao> open(
      @PathVariable UUID id, @Valid @RequestBody(required = false) Abertura input) {
    return ResponseEntity.created(URI.create("/api/v1/pautas/" + id + "/sessao"))
        .body(service.open(id, input));
  }

  @GetMapping("/{id}/sessao")
  public Sessao session(@PathVariable UUID id) {
    return service.session(id);
  }

  @PostMapping("/{id}/votos")
  public ResponseEntity<Void> vote(@PathVariable UUID id, @Valid @RequestBody NovoVoto input) {
    service.vote(id, input);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}/resultado")
  public Resultado result(@PathVariable UUID id) {
    return service.result(id);
  }
}
