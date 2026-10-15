# Validação da entrega

Execução: 14/10/2026. OpenJDK 17.0.20, Maven 3.9.11, Linux.

- Comando: `mvn -B -ntp clean verify` (configuração de proxy apenas do ambiente de execução).
- Resultado: BUILD SUCCESS, JAR executável gerado.
- 19 testes executados, 0 falhas, 0 erros, 0 ignorados.
- Persistência: contextos encerrados e reiniciados usando o mesmo arquivo H2; pauta, voto e bloqueio de duplicidade preservados.
- Concorrência: 30 tentativas do mesmo associado resultam em um voto; 100 associados distintos resultam em 100 votos.
- CPF: testes com servidor HTTP local, sem depender do endpoint externo.
- Cobertura JaCoCo: 192 de 228 linhas (84,2%); 28 de 42 ramos (66,7%). Não representa cobertura exaustiva de todos os cenários.
- Código Java formatado com google-java-format 1.24.0.

Não executados neste ambiente: Docker, PostgreSQL real, k6 com 100 mil votos, deploy em nuvem e chamada ao serviço Heroku real. Os arquivos e instruções para essas execuções estão incluídos; nenhum resultado de carga foi presumido.

O pacote ZIP inclui uma cópia das fontes e `sicredi-votacao.git.bundle` com o histórico Git. Para recuperar os commits, na pasta onde extraiu o ZIP:

```sh
git clone votacao-assembleia.git.bundle votacao-assembleia-com-historico
cd votacao-assembleia-com-historico
git remote remove origin
git remote add origin https://github.com/taureliano/votacao-assembleia.git
git push -u origin main
```

Alternativamente use a pasta de fontes com as instruções do README.
