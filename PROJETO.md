# Wi-Fi Analyzer — informações do projeto

## Objetivo

Ferramenta de linha de comando para analisar a rede Wi-Fi do ambiente: conexão atual,
redes vizinhas, ocupação de canais, auditoria de segurança, latência até o roteador,
resolução de DNS e monitoramento de sinal, com diagnóstico e relatório em Markdown.

Usa apenas a biblioteca padrão do Python e o utilitário `iw` (Linux).

## Requisitos

- Python 3.10 ou superior (testado com 3.13).
- Linux com o pacote `iw` (`sudo apt install iw`). A varredura (`scan`) normalmente precisa de `sudo`.
- Sem `iw`, os comandos que dependem dele avisam o erro; latência, DNS e análise de arquivos continuam funcionando.

## Comandos

```bash
python3 -m wifi_analyzer info                      # conexão atual (SSID, canal, sinal, taxas)
sudo python3 -m wifi_analyzer scan                 # redes próximas
sudo python3 -m wifi_analyzer canais               # ocupação de canais e melhor canal por banda
sudo python3 -m wifi_analyzer seguranca            # auditoria de segurança
python3 -m wifi_analyzer latencia                  # latência, jitter e perda até o roteador
python3 -m wifi_analyzer dns google.com github.com # tempo de resolução de nomes
python3 -m wifi_analyzer monitor --amostras 60 --intervalo 2 --csv sinal.csv
sudo python3 -m wifi_analyzer diagnostico --relatorio relatorio.md
```

Opções úteis:

- `--iface wlan0`: escolhe a interface (padrão: a primeira encontrada em `iw dev`).
- `--arquivo saida.txt`: analisa uma saída salva de `iw dev wlan0 scan`, sem precisar de root nem de Wi-Fi ativo. Ideal para testar e para analisar varreduras em outro momento.
- `--json` e `--relatorio`: salvam os resultados em JSON ou Markdown.

Exemplo offline:

```bash
sudo iw dev wlan0 scan > varredura.txt
python3 -m wifi_analyzer canais --arquivo varredura.txt
python3 -m wifi_analyzer seguranca --arquivo varredura.txt
```

## Recursos e como são calculados

| Recurso | Como funciona |
|---|---|
| Qualidade do sinal | Converte dBm em porcentagem: `2 × (dBm + 100)`, limitado a 0–100%. Classes: excelente (≥ −50), bom (≥ −60), razoável (≥ −70), fraco (≥ −80), muito fraco. |
| Banda e canal | Converte a frequência em canal: 2.4 GHz (canais 1–14), 5 GHz e 6 GHz. |
| Ocupação de canais | Soma a potência linear (10^(dBm/10)) das redes vizinhas. No 2.4 GHz, um canal interfere nos vizinhos a até 4 posições, com peso decrescente. Em 5 e 6 GHz só conta o mesmo canal. |
| Auditoria de segurança | Classifica cada rede (Aberta, WEP, WPA, WPA2, WPA2 com TKIP, WPA2/WPA3 em transição, WPA3, WPA2-Enterprise) e aponta WPS ativo, SSID oculto e nomes iguais com segurança diferente (possível *evil twin*). |
| Latência, jitter e perda | Mede o tempo de conexão TCP até o gateway (`/proc/net/route`). Conexão recusada também conta como resposta. Jitter é a variação média entre respostas seguidas. |
| DNS | Mede o tempo de `getaddrinfo` para cada domínio. |
| Monitoramento | Lê o sinal e a taxa a cada intervalo, mostra no terminal e opcionalmente grava CSV. |
| Diagnóstico | Cruza conexão, canais, latência e DNS e gera sugestões por gravidade. |

### Gravidades

`crítica` > `alta` > `média` > `baixa` > `info`.

## Estrutura

| Arquivo | Conteúdo |
|---|---|
| `wifi_analyzer/cli.py` | Comandos e argumentos |
| `wifi_analyzer/parsers.py` | Leitura da saída do `iw` (scan, link, dev) |
| `wifi_analyzer/radio.py` | Frequência → canal/banda e classificação de segurança |
| `wifi_analyzer/analysis.py` | Qualidade do sinal, ranking de canais, auditoria e diagnóstico |
| `wifi_analyzer/network.py` | Gateway, latência, DNS e amostragem |
| `wifi_analyzer/wifi.py` | Execução do `iw` com mensagens de erro claras |
| `wifi_analyzer/report.py` | Tabelas no terminal e relatório Markdown |
| `tests/test_analyzer.py` | 30 testes com saídas de exemplo em `tests/fixtures/` |

Rodar os testes:

```bash
python3 -m unittest discover -s tests -t .
```

## Limitações

- Funciona só no Linux com `iw`. Windows e macOS não são suportados (a análise de arquivos salvos funciona em qualquer sistema, desde que a saída seja a do `iw`).
- Adaptadores e drivers variam: alguns não listam todas as redes ou não informam o canal de todas.
- A análise de canais é uma estimativa. Ela não mede a ocupação real do ar nem a largura de canal (20/40/80 MHz).
- A latência mede TCP até o gateway, não ICMP. Os valores são uma aproximação.
- Não há teste de velocidade de download/upload nesta versão.

## Uso ético e legal

Use somente em redes que você administra ou para as quais tem autorização. Analisar redes de terceiros sem permissão pode ser ilegal.

## Próximos passos sugeridos

- Teste de velocidade com servidor próprio.
- Histórico em banco local e gráfico do sinal ao longo do tempo.
- Suporte a Windows (`netsh wlan show networks mode=bssid`) e macOS (`airport`).
- Pacote instalável (`pyproject.toml`) com o comando `wifi-analyzer`.

## Status do repositório

- Repositório Git local em `/home/user/wifi-analyzer`, branch `main`.
- Repositório remoto: ainda não criado no GitHub.
