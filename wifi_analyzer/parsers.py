"""Leitura da saída do utilitário `iw` (Linux) — ao vivo ou a partir de arquivos salvos."""
from __future__ import annotations

import re
from dataclasses import asdict, dataclass

from .radio import banda_de_frequencia, canal_de_frequencia, classificar_seguranca


@dataclass
class AccessPoint:
    bssid: str
    ssid: str
    freq_mhz: int | None
    sinal_dbm: float | None
    canal: int | None
    banda: str | None
    seguranca: str
    wps: bool = False
    tkip: bool = False
    conectada: bool = False

    def to_dict(self) -> dict:
        return asdict(self)


_CABECALHO_RE = re.compile(r"^(RSN|WPA|WPS):\s*(.*)$")


def _novo_bss(linha: str) -> dict:
    return {
        "bssid": linha[4:21].lower(),
        "conectada": "-- associated" in linha,
        "ssid": "",
        "freq": None,
        "sinal": None,
        "canal": None,
        "privacy": False,
        "rsn": False,
        "wpa": False,
        "wps": False,
        "sae": False,
        "psk": False,
        "eap": False,
        "tkip": False,
    }


def _montar(bss: dict) -> AccessPoint:
    freq = bss["freq"]
    return AccessPoint(
        bssid=bss["bssid"],
        ssid=bss["ssid"],
        freq_mhz=freq,
        sinal_dbm=bss["sinal"],
        canal=bss["canal"] or canal_de_frequencia(freq),
        banda=banda_de_frequencia(freq),
        seguranca=classificar_seguranca(
            privacy=bss["privacy"], wpa=bss["wpa"], rsn=bss["rsn"], sae=bss["sae"],
            psk=bss["psk"], eap=bss["eap"], tkip=bss["tkip"],
        ),
        wps=bss["wps"],
        tkip=bss["tkip"],
        conectada=bss["conectada"],
    )


def parse_iw_scan(texto: str) -> list[AccessPoint]:
    """Converte a saída de `iw dev <iface> scan` em uma lista de redes."""
    brutos: list[dict] = []
    atual: dict | None = None
    bloco: str | None = None

    for linha in texto.splitlines():
        if linha.startswith("BSS "):
            atual = _novo_bss(linha)
            brutos.append(atual)
            bloco = None
            continue
        if atual is None:
            continue
        s = linha.strip()
        if not s:
            continue

        m = _CABECALHO_RE.match(s)
        if m:
            bloco = m.group(1)
            chave = {"RSN": "rsn", "WPA": "wpa", "WPS": "wps"}[bloco]
            atual[chave] = True
            s = m.group(2).strip()
            if not s:
                continue

        if s.startswith("*") and bloco:
            if bloco in ("RSN", "WPA"):
                if s.startswith("* Authentication suites:"):
                    suites = s.split(":", 1)[1]
                    atual["sae"] = atual["sae"] or "SAE" in suites
                    atual["psk"] = atual["psk"] or "PSK" in suites
                    atual["eap"] = atual["eap"] or "802.1X" in suites
                elif s.startswith("* Pairwise ciphers:") and "TKIP" in s:
                    atual["tkip"] = True
            continue

        bloco = None
        if s.startswith("freq:"):
            atual["freq"] = int(float(s.split(":", 1)[1]))
        elif s.startswith("signal:"):
            atual["sinal"] = float(s.split(":", 1)[1].split()[0])
        elif s.startswith("SSID:"):
            atual["ssid"] = s[5:].strip()
        elif s.startswith("capability:"):
            atual["privacy"] = "Privacy" in s
        elif s.startswith("DS Parameter set:"):
            m_canal = re.search(r"channel (\d+)", s)
            if m_canal:
                atual["canal"] = int(m_canal.group(1))

    return [_montar(bss) for bss in brutos]


def parse_iw_link(texto: str) -> dict:
    """Converte a saída de `iw dev <iface> link` (conexão atual)."""
    info = {
        "conectado": False,
        "bssid": None,
        "ssid": None,
        "freq_mhz": None,
        "sinal_dbm": None,
        "taxa_rx": None,
        "taxa_tx": None,
    }
    for linha in texto.splitlines():
        s = linha.strip()
        if s.startswith("Connected to"):
            partes = s.split()
            info["conectado"] = True
            info["bssid"] = partes[2] if len(partes) > 2 else None
        elif s.startswith("SSID:"):
            info["ssid"] = s[5:].strip()
        elif s.startswith("freq:"):
            info["freq_mhz"] = int(float(s.split(":", 1)[1]))
        elif s.startswith("signal:"):
            info["sinal_dbm"] = float(s.split(":", 1)[1].split()[0])
        elif s.startswith("rx bitrate:"):
            info["taxa_rx"] = s.split(":", 1)[1].strip()
        elif s.startswith("tx bitrate:"):
            info["taxa_tx"] = s.split(":", 1)[1].strip()
    return info


def parse_iw_dev(texto: str) -> list[str]:
    """Lista os nomes das interfaces em `iw dev`."""
    return re.findall(r"^\s*Interface (\S+)", texto, flags=re.MULTILINE)
