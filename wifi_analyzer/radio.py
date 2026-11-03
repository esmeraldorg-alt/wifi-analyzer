"""Funções de rádio: frequência → canal/banda e classificação de segurança."""
from __future__ import annotations


def banda_de_frequencia(freq_mhz: int | None) -> str | None:
    if freq_mhz is None:
        return None
    if 2400 <= freq_mhz < 2500:
        return "2.4 GHz"
    if 4900 <= freq_mhz < 5900:
        return "5 GHz"
    if 5925 <= freq_mhz <= 7125:
        return "6 GHz"
    return None


def canal_de_frequencia(freq_mhz: int | None) -> int | None:
    if freq_mhz is None:
        return None
    if freq_mhz == 2484:
        return 14
    if 2412 <= freq_mhz <= 2472 and (freq_mhz - 2407) % 5 == 0:
        return (freq_mhz - 2407) // 5
    if 5000 < freq_mhz < 5900 and (freq_mhz - 5000) % 5 == 0:
        return (freq_mhz - 5000) // 5
    if 5955 <= freq_mhz <= 7115 and (freq_mhz - 5950) % 5 == 0:
        return (freq_mhz - 5950) // 5
    return None


def classificar_seguranca(
    *,
    privacy: bool = False,
    wpa: bool = False,
    rsn: bool = False,
    sae: bool = False,
    psk: bool = False,
    eap: bool = False,
    tkip: bool = False,
) -> str:
    """Converte os indicadores do beacon no nome de segurança usado pelo analisador."""
    if rsn and eap:
        return "WPA2-Enterprise"
    if rsn and sae and psk:
        return "WPA2/WPA3 (transição)"
    if rsn and sae:
        return "WPA3"
    if rsn:
        return "WPA2 (TKIP)" if tkip else "WPA2"
    if wpa:
        return "WPA"
    if privacy:
        return "WEP"
    return "Aberta"
