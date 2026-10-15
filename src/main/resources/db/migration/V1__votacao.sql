CREATE TABLE pauta (
 id UUID PRIMARY KEY,
 titulo VARCHAR(200) NOT NULL,
 descricao VARCHAR(2000) NOT NULL,
 criada_em TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE sessao (
 pauta_id UUID PRIMARY KEY REFERENCES pauta(id),
 aberta_em TIMESTAMP WITH TIME ZONE NOT NULL,
 encerra_em TIMESTAMP WITH TIME ZONE NOT NULL,
 CHECK (encerra_em > aberta_em)
);
CREATE TABLE voto (
 id UUID PRIMARY KEY,
 pauta_id UUID NOT NULL REFERENCES pauta(id),
 associado_id VARCHAR(100) NOT NULL,
 escolha VARCHAR(3) NOT NULL CHECK (escolha IN ('SIM', 'NAO')),
 criado_em TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT voto_unico_por_pauta UNIQUE (pauta_id, associado_id)
);
CREATE INDEX idx_voto_contagem ON voto(pauta_id, escolha);
