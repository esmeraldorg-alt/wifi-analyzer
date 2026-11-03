"""Análises: qualidade do sinal, interferência entre canais, auditoria de segurança e diagnóstico."""
from __future__ import annotations

import math

from .parsers import AccessPoint
from .radio import banda_de_frequencia, canal_de_frequencia

SINAL_PADRAO_DESCONHECIDO = -90.0
ORDEM_GRAVIDADE = {"crítica": 0, "alta": 1, "média": 2, "baixa": 3, "info": 4}

# Canais sugeridos quando a rede não aparece na varredura (faixas padrão).
CANAIS_5GHZ = [36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128,
               132, 136, 140, 144, 149, 153, 157, 161, 165]


def qualidade_sinal(dbm: float | None) -> int:
    """Converte dBm em porcentagem (0-100), no mesmo critério usado pelo Windows."""
    if dbm is None:
        return 0
    return max(0, min(100, round(2 * (dbm + 100))))


def classificar_sinal(dbm: float | None) -> str:
    if dbm is None:
        return "desconhecido"
    if dbm >= -50:
        return "excelente"
    if dbm >= -60:
        return "bom"
    if dbm >= -70:
        return "razoável"
    if dbm >= -80:
        return "fraco"
    return "muito fraco"


def _fator_sobreposicao(canal_a: int | None, canal_b: int | None, banda: str) -> float:
    """1.0 = mesmo canal. No 2.4 GHz os canais se sobrepõem até 4 posições de distância."""
    if canal_a is None or canal_b is None:
        return 0.0
    distancia = abs(canal_a - canal_b)
    if banda == "2.4 GHz":
        return max(0.0, 1 - distancia / 5)
    return 1.0 if distancia == 0 else 0.0


def _potencia_linear(dbm: float | None) -> float:
    return 10 ** ((SINAL_PADRAO_DESCONHECIDO if dbm is None else dbm) / 10)


def ranking_canais(redes: list[AccessPoint]) -> dict[str, list[dict]]:
    """Para cada banda, ordena os canais do menos ao mais congestionado."""
    resultado: dict[str, list[dict]] = {}
    bandas = sorted({ap.banda for ap in redes if ap.banda})
    for banda in bandas:
        presentes = {ap.canal for ap in redes if ap.banda == banda and ap.canal}
        if banda == "2.4 GHz":
            candidatos = set(range(1, 14))
        elif banda == "5 GHz":
            candidatos = presentes | set(CANAIS_5GHZ)
        else:
            candidatos = presentes | set(range(1, 234, 4))

        linhas = []
        for canal in sorted(candidatos):
            score = 0.0
            vizinhas = 0
            for ap in redes:
                if ap.banda != banda:
                    continue
                fator = _fator_sobreposicao(canal, ap.canal, banda)
                if fator > 0:
                    score += fator * _potencia_linear(ap.sinal_dbm)
                    vizinhas += 1
            linhas.append({
                "canal": canal,
                "redes": vizinhas,
                "interferencia_dbm": round(10 * math.log10(score), 1) if score > 0 else None,
                "score": score,
            })
        linhas.sort(key=lambda linha: (linha["score"], linha["canal"]))
        resultado[banda] = linhas
    return resultado


def auditar_seguranca(redes: list[AccessPoint]) -> list[dict]:
    achados: list[dict] = []

    def registrar(gravidade: str, ap_ssid: str, bssid: str, mensagem: str) -> None:
        achados.append({"gravidade": gravidade, "ssid": ap_ssid, "bssid": bssid, "mensagem": mensagem})

    por_ssid: dict[str, list[AccessPoint]] = {}
    for ap in redes:
        nome = ap.ssid or "(oculta)"
        seg = ap.seguranca
        if seg == "Aberta":
            registrar("crítica", nome, ap.bssid, "Rede aberta: o tráfego pode ser interceptado.")
        elif seg == "WEP":
            registrar("crítica", nome, ap.bssid, "WEP é quebrado em minutos: troque para WPA2 ou WPA3.")
        elif seg == "WPA":
            registrar("alta", nome, ap.bssid, "WPA (TKIP) está obsoleto: atualize para WPA2/WPA3 com AES.")
        elif seg == "WPA2 (TKIP)":
            registrar("média", nome, ap.bssid, "WPA2 aceitando TKIP: deixe somente AES (CCMP).")
        elif seg == "WPA2":
            registrar("baixa", nome, ap.bssid, "WPA2 é aceitável, mas WPA3 é mais seguro.")
        elif seg == "WPA2/WPA3 (transição)":
            registrar("média", nome, ap.bssid, "Modo de transição permite rebaixar a conexão para WPA2.")
        elif seg == "WPA2-Enterprise":
            registrar("info", nome, ap.bssid, "Rede corporativa (802.1X).")

        if ap.wps:
            registrar("média", nome, ap.bssid, "WPS ativo: vulnerável a ataques de PIN. Desative.")
        if not ap.ssid:
            registrar("info", nome, ap.bssid, "SSID oculto: não elimina a rede, só esconde o nome.")
        por_ssid.setdefault(ap.ssid, []).append(ap)

    for ssid, grupo in por_ssid.items():
        if not ssid:
            continue
        bssids = {ap.bssid for ap in grupo}
        segurancas = {ap.seguranca for ap in grupo}
        if len(bssids) > 1 and len(segurancas) > 1:
            registrar("alta", ssid, "-",
                      "Mesmo nome com segurança diferente: possível rede falsa (evil twin) "
                      "ou roteador mal configurado.")

    achados.sort(key=lambda a: (ORDEM_GRAVIDADE[a["gravidade"]], a["ssid"]))
    return achados


def diagnosticar(
    link: dict | None,
    latencia: dict | None,
    dns: list[dict] | None,
    redes: list[AccessPoint] | None = None,
) -> list[tuple[str, str]]:
    """Gera (gravidade, mensagem) a partir da conexão, latência, DNS e canais."""
    dicas: list[tuple[str, str]] = []

    if not link or not link.get("conectado"):
        dicas.append(("alta", "Não há conexão Wi-Fi ativa nesta interface."))
    else:
        sinal = link.get("sinal_dbm")
        if sinal is not None and sinal < -70:
            dicas.append(("média", f"Sinal fraco ({sinal:.0f} dBm): aproxime-se do roteador ou use um repetidor."))
        freq = link.get("freq_mhz")
        if redes and freq:
            banda = banda_de_frequencia(freq)
            canal_atual = canal_de_frequencia(freq)
            ranking = ranking_canais(redes).get(banda, [])
            if ranking and canal_atual is not None:
                melhor = ranking[0]
                atual = next((x for x in ranking if x["canal"] == canal_atual), None)
                if atual and melhor["canal"] != canal_atual and atual["score"] >= 2 * melhor["score"] > 0:
                    dicas.append(("média", f"Canal {canal_atual} ({banda}) está congestionado. "
                                           f"Considere o canal {melhor['canal']}, menos ocupado."))

    if latencia and latencia.get("media_ms") is not None:
        if latencia["media_ms"] > 30:
            dicas.append(("média", f"Latência alta até o roteador: {latencia['media_ms']:.1f} ms."))
        if latencia.get("jitter_ms", 0) > 15:
            dicas.append(("média", f"Jitter alto ({latencia['jitter_ms']:.1f} ms): instabilidade na rede local."))
    if latencia and latencia.get("perda_pct", 0) > 0:
        gravidade = "alta" if latencia["perda_pct"] >= 2 else "média"
        dicas.append((gravidade, f"Perda de pacotes até o roteador: {latencia['perda_pct']:.0f}%."))

    if dns:
        tempos = [d["ms"] for d in dns if d.get("ms") is not None]
        falhas = [d["dominio"] for d in dns if d.get("ms") is None]
        if falhas:
            dicas.append(("alta", "Falha ao resolver: " + ", ".join(falhas) + "."))
        if tempos and sum(tempos) / len(tempos) > 150:
            dicas.append(("média", f"DNS lento: média de {sum(tempos) / len(tempos):.0f} ms."))

    if not dicas:
        dicas.append(("info", "Nenhum problema encontrado."))
    dicas.sort(key=lambda d: ORDEM_GRAVIDADE[d[0]])
    return dicas
