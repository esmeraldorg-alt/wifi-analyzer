"""Formatação: tabelas no terminal e relatório em Markdown."""
from __future__ import annotations

from datetime import datetime

from .analysis import classificar_sinal, qualidade_sinal
from .parsers import AccessPoint


def _nome(ap: AccessPoint) -> str:
    return ap.ssid or "(oculta)"


def tabela_redes(redes: list[AccessPoint]) -> str:
    if not redes:
        return "Nenhuma rede encontrada."
    ordenadas = sorted(redes, key=lambda ap: ap.sinal_dbm if ap.sinal_dbm is not None else -999, reverse=True)
    cab = f"{'SSID':<24}{'BSSID':<19}{'Canal':>6} {'Banda':<8}{'Sinal':>8} {'Qual.':>6}  {'Segurança':<22}WPS"
    linhas = [cab, "-" * len(cab)]
    for ap in ordenadas:
        sinal = f"{ap.sinal_dbm:.0f}" if ap.sinal_dbm is not None else "-"
        marca = " *" if ap.conectada else ""
        linhas.append(
            f"{_nome(ap)[:23]:<24}{ap.bssid:<19}{str(ap.canal or '-'):>6} {ap.banda or '-':<8}"
            f"{sinal:>8} {qualidade_sinal(ap.sinal_dbm):>5}%  {ap.seguranca:<22}"
            f"{'sim' if ap.wps else 'não'}{marca}"
        )
    linhas.append("(* = rede à qual você está conectado)")
    return "\n".join(linhas)


def tabela_canais(ranking: dict[str, list[dict]]) -> str:
    if not ranking:
        return "Sem redes suficientes para analisar canais."
    saida = []
    for banda, linhas in ranking.items():
        saida.append(f"{banda} — melhores canais (menos congestionados):")
        saida.append(f"  {'Canal':>6} {'Redes próximas':>15} {'Interferência':>15}")
        for linha in linhas[:5]:
            inter = f"{linha['interferencia_dbm']} dBm" if linha["interferencia_dbm"] is not None else "livre"
            saida.append(f"  {linha['canal']:>6} {linha['redes']:>15} {inter:>15}")
        saida.append("")
    return "\n".join(saida).rstrip()


def tabela_seguranca(achados: list[dict]) -> str:
    if not achados:
        return "Nenhum problema de segurança encontrado."
    linhas = []
    for a in achados:
        linhas.append(f"[{a['gravidade'].upper():^8}] {a['ssid']} ({a['bssid']})")
        linhas.append(f"           {a['mensagem']}")
    return "\n".join(linhas)


def tabela_diagnostico(dicas: list[tuple[str, str]]) -> str:
    return "\n".join(f"[{g.upper():^8}] {m}" for g, m in dicas)


def relatorio_markdown(
    redes: list[AccessPoint] | None,
    ranking: dict[str, list[dict]] | None,
    achados: list[dict] | None,
    link: dict | None = None,
    latencia: dict | None = None,
    dns: list[dict] | None = None,
    dicas: list[tuple[str, str]] | None = None,
) -> str:
    linhas = [f"# Relatório Wi-Fi — {datetime.now().strftime('%d/%m/%Y %H:%M')}", ""]

    if link:
        linhas += ["## Conexão atual", ""]
        if link.get("conectado"):
            linhas += [
                f"- SSID: {link.get('ssid') or '-'}",
                f"- BSSID: {link.get('bssid') or '-'}",
                f"- Sinal: {link.get('sinal_dbm')} dBm ({classificar_sinal(link.get('sinal_dbm'))})",
                f"- Taxa RX: {link.get('taxa_rx') or '-'}",
                f"- Taxa TX: {link.get('taxa_tx') or '-'}",
            ]
        else:
            linhas.append("- Não conectado.")
        linhas.append("")

    if redes:
        linhas += ["## Redes próximas", "", "| SSID | BSSID | Canal | Banda | Sinal (dBm) | Segurança | WPS |",
                   "|---|---|---|---|---|---|---|"]
        for ap in sorted(redes, key=lambda a: a.sinal_dbm if a.sinal_dbm is not None else -999, reverse=True):
            linhas.append(f"| {_nome(ap)} | {ap.bssid} | {ap.canal or '-'} | {ap.banda or '-'} | "
                          f"{ap.sinal_dbm if ap.sinal_dbm is not None else '-'} | {ap.seguranca} | "
                          f"{'sim' if ap.wps else 'não'} |")
        linhas.append("")

    if ranking:
        linhas += ["## Canais", ""]
        for banda, lst in ranking.items():
            linhas.append(f"**{banda}** — melhor canal: **{lst[0]['canal']}**")
            linhas.append("")
            linhas += ["| Canal | Redes próximas | Interferência |", "|---|---|---|"]
            for x in lst[:5]:
                inter = f"{x['interferencia_dbm']} dBm" if x["interferencia_dbm"] is not None else "livre"
                linhas.append(f"| {x['canal']} | {x['redes']} | {inter} |")
            linhas.append("")

    if achados is not None:
        linhas += ["## Segurança", ""]
        if not achados:
            linhas.append("Nenhum problema encontrado.")
        for a in achados:
            linhas.append(f"- **{a['gravidade'].upper()}** — {a['ssid']} ({a['bssid']}): {a['mensagem']}")
        linhas.append("")

    if latencia:
        linhas += ["## Latência até o roteador", "",
                   f"- Host: {latencia.get('host')}",
                   f"- Mínimo / média / máximo: {latencia.get('minimo_ms')} / {latencia.get('media_ms')} / "
                   f"{latencia.get('maximo_ms')} ms",
                   f"- Jitter: {latencia.get('jitter_ms')} ms",
                   f"- Perda: {latencia.get('perda_pct')}%", ""]

    if dns:
        linhas += ["## DNS", ""]
        for d in dns:
            valor = f"{d['ms']} ms" if d["ms"] is not None else f"falhou ({d['erro']})"
            linhas.append(f"- {d['dominio']}: {valor}")
        linhas.append("")

    if dicas:
        linhas += ["## Diagnóstico", ""]
        linhas += [f"- **{g.upper()}** — {m}" for g, m in dicas]
        linhas.append("")

    return "\n".join(linhas)
