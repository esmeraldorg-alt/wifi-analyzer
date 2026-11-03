"""Interface de linha de comando: `python3 -m wifi_analyzer <comando>`."""
from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

from . import network, report, wifi
from .analysis import auditar_seguranca, diagnosticar, ranking_canais
from .parsers import parse_iw_scan
from .radio import banda_de_frequencia, canal_de_frequencia
from .wifi import ErroFerramenta

DOMINIOS_PADRAO = ["google.com", "cloudflare.com", "wikipedia.org"]


def _iface(args) -> str:
    return args.iface or wifi.interface_padrao()


def _redes(args):
    """Lê a varredura de um arquivo salvo (--arquivo) ou faz uma varredura ao vivo."""
    if getattr(args, "arquivo", None):
        return parse_iw_scan(Path(args.arquivo).read_text(encoding="utf-8"))
    return parse_iw_scan(wifi.escanear(_iface(args)))


def cmd_info(args) -> int:
    dados = wifi.conexao(_iface(args))
    if not dados["conectado"]:
        print("Não conectado a nenhuma rede Wi-Fi.")
        return 0
    from .analysis import classificar_sinal, qualidade_sinal
    print(f"SSID:        {dados['ssid']}")
    print(f"BSSID:       {dados['bssid']}")
    banda = banda_de_frequencia(dados["freq_mhz"])
    canal = canal_de_frequencia(dados["freq_mhz"])
    print(f"Canal:       {canal} ({banda})")
    print(f"Sinal:       {dados['sinal_dbm']} dBm — {classificar_sinal(dados['sinal_dbm'])} "
          f"({qualidade_sinal(dados['sinal_dbm'])}%)")
    print(f"Taxa RX:     {dados['taxa_rx']}")
    print(f"Taxa TX:     {dados['taxa_tx']}")
    return 0


def cmd_scan(args) -> int:
    redes = _redes(args)
    print(report.tabela_redes(redes))
    print()
    print(f"{len(redes)} rede(s) encontrada(s).")
    if args.json:
        Path(args.json).write_text(
            json.dumps([ap.to_dict() for ap in redes], ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"JSON salvo em {args.json}")
    if args.relatorio:
        md = report.relatorio_markdown(redes, ranking_canais(redes), auditar_seguranca(redes))
        Path(args.relatorio).write_text(md, encoding="utf-8")
        print(f"Relatório salvo em {args.relatorio}")
    return 0


def cmd_canais(args) -> int:
    redes = _redes(args)
    print(report.tabela_canais(ranking_canais(redes)))
    return 0


def cmd_seguranca(args) -> int:
    redes = _redes(args)
    print(report.tabela_seguranca(auditar_seguranca(redes)))
    return 0


def cmd_latencia(args) -> int:
    host = args.host or network.gateway_padrao()
    if not host:
        print("Não foi possível descobrir o gateway. Informe com --host.", file=sys.stderr)
        return 1
    dados = network.medir_latencia(host, amostras=args.amostras, porta=args.porta)
    print(f"Host: {host}:{args.porta}  ({args.amostras} amostras)")
    for chave, rotulo in [("minimo_ms", "Mínimo"), ("media_ms", "Média"), ("maximo_ms", "Máximo"),
                          ("jitter_ms", "Jitter"), ("perda_pct", "Perda (%)")]:
        print(f"{rotulo + ':':<12} {dados[chave]}")
    return 0


def cmd_dns(args) -> int:
    for r in network.testar_dns(args.dominios or DOMINIOS_PADRAO):
        valor = f"{r['ms']} ms" if r["ms"] is not None else f"FALHOU ({r['erro']})"
        print(f"{r['dominio']:<24} {valor}")
    return 0


def cmd_monitor(args) -> int:
    iface = _iface(args)
    arquivo = None
    escritor = None
    if args.csv:
        arquivo = open(args.csv, "w", newline="", encoding="utf-8")
        escritor = csv.DictWriter(arquivo, fieldnames=["momento", "sinal_dbm", "taxa_rx", "erro"])
        escritor.writeheader()

    def mostrar(reg: dict) -> None:
        if "erro" in reg:
            print(f"{reg['momento']}  erro: {reg['erro']}")
        else:
            print(f"{reg['momento']}  sinal {reg.get('sinal_dbm')} dBm  taxa RX {reg.get('taxa_rx')}")
        if escritor:
            escritor.writerow({"momento": reg["momento"], "sinal_dbm": reg.get("sinal_dbm"),
                               "taxa_rx": reg.get("taxa_rx"), "erro": reg.get("erro")})
            arquivo.flush()

    def ler() -> dict:
        d = wifi.conexao(iface)
        if not d["conectado"]:
            return {"erro": "não conectado"}
        return {"sinal_dbm": d["sinal_dbm"], "taxa_rx": d["taxa_rx"]}

    try:
        network.amostrar(ler, amostras=args.amostras, intervalo=args.intervalo, ao_amostrar=mostrar)
    except KeyboardInterrupt:
        print("\nMonitoramento interrompido.")
    finally:
        if arquivo:
            arquivo.close()
    if args.csv:
        print(f"Amostras salvas em {args.csv}")
    return 0


def cmd_diagnostico(args) -> int:
    link = None
    redes = None
    avisos = []
    try:
        link = wifi.conexao(_iface(args))
    except ErroFerramenta as exc:
        avisos.append(f"Conexão: {exc}")
    try:
        redes = _redes(args)
    except ErroFerramenta as exc:
        avisos.append(f"Varredura: {exc}")
    gateway = network.gateway_padrao()
    latencia = network.medir_latencia(gateway, amostras=10) if gateway else None
    dns = network.testar_dns(DOMINIOS_PADRAO)

    dicas = diagnosticar(link, latencia, dns, redes)
    for aviso in avisos:
        print(f"Aviso: {aviso}", file=sys.stderr)
    print(report.tabela_diagnostico(dicas))

    if args.relatorio:
        md = report.relatorio_markdown(redes, ranking_canais(redes) if redes else None,
                                       auditar_seguranca(redes) if redes else None,
                                       link=link, latencia=latencia, dns=dns, dicas=dicas)
        Path(args.relatorio).write_text(md, encoding="utf-8")
        print(f"Relatório salvo em {args.relatorio}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="wifi-analyzer",
                                description="Análise de redes Wi-Fi: conexão, canais, segurança, latência e DNS.")
    sub = p.add_subparsers(dest="comando", required=True)

    def com_iface(parser):
        parser.add_argument("--iface", help="Interface Wi-Fi (ex.: wlan0). Padrão: a primeira encontrada.")

    def com_arquivo(parser):
        parser.add_argument("--arquivo", help="Usa a saída salva de 'iw dev <iface> scan' em vez de escanear.")

    s = sub.add_parser("info", help="Mostra a conexão Wi-Fi atual")
    com_iface(s)
    s.set_defaults(func=cmd_info)

    s = sub.add_parser("scan", help="Lista as redes Wi-Fi próximas")
    com_iface(s); com_arquivo(s)
    s.add_argument("--json", help="Salva as redes em JSON")
    s.add_argument("--relatorio", help="Salva um relatório Markdown")
    s.set_defaults(func=cmd_scan)

    s = sub.add_parser("canais", help="Mostra a ocupação dos canais e a melhor opção")
    com_iface(s); com_arquivo(s)
    s.set_defaults(func=cmd_canais)

    s = sub.add_parser("seguranca", help="Audita a segurança das redes próximas")
    com_iface(s); com_arquivo(s)
    s.set_defaults(func=cmd_seguranca)

    s = sub.add_parser("latencia", help="Latência, jitter e perda até o roteador")
    s.add_argument("--host", help="Host a testar. Padrão: gateway padrão.")
    s.add_argument("--porta", type=int, default=80)
    s.add_argument("--amostras", type=int, default=10)
    s.set_defaults(func=cmd_latencia)

    s = sub.add_parser("dns", help="Tempo de resolução de nomes")
    s.add_argument("dominios", nargs="*", help="Domínios a testar")
    s.set_defaults(func=cmd_dns)

    s = sub.add_parser("monitor", help="Acompanha o sinal ao longo do tempo")
    com_iface(s)
    s.add_argument("--intervalo", type=float, default=2.0, help="Segundos entre amostras")
    s.add_argument("--amostras", type=int, default=30)
    s.add_argument("--csv", help="Salva as amostras em CSV")
    s.set_defaults(func=cmd_monitor)

    s = sub.add_parser("diagnostico", help="Diagnóstico geral com sugestões")
    com_iface(s); com_arquivo(s)
    s.add_argument("--relatorio", help="Salva um relatório Markdown completo")
    s.set_defaults(func=cmd_diagnostico)

    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except ErroFerramenta as exc:
        print(f"Erro: {exc}", file=sys.stderr)
        return 1
    except FileNotFoundError as exc:
        print(f"Erro: arquivo não encontrado: {exc.filename}", file=sys.stderr)
        return 1
