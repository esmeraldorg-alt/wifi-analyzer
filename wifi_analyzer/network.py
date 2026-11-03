"""Testes de rede: gateway, latência/jitter/perda, tempo de DNS e monitoramento de sinal."""
from __future__ import annotations

import socket
import time
from datetime import datetime
from typing import Callable


def _ler_tabela_rotas() -> str:
    try:
        with open("/proc/net/route", encoding="utf-8") as f:
            return f.read()
    except OSError:
        return ""


def gateway_padrao(texto_rotas: str | None = None) -> str | None:
    """Encontra o gateway padrão em /proc/net/route (Linux). O endereço vem em ordem invertida."""
    texto = _ler_tabela_rotas() if texto_rotas is None else texto_rotas
    for linha in texto.splitlines()[1:]:
        campos = linha.split()
        if len(campos) >= 3 and campos[1] == "00000000" and campos[2] != "00000000":
            h = campos[2]
            return ".".join(str(int(h[i:i + 2], 16)) for i in (6, 4, 2, 0))
    return None


def medir_rtt(host: str, porta: int = 80, timeout: float = 1.0) -> float | None:
    """Tempo de ida e volta em ms via TCP. Conexão recusada também prova que o host respondeu."""
    inicio = time.perf_counter()
    try:
        with socket.create_connection((host, porta), timeout=timeout):
            pass
    except ConnectionRefusedError:
        pass
    except OSError:
        return None
    return round((time.perf_counter() - inicio) * 1000, 2)


def estatisticas(amostras: list[float | None]) -> dict:
    """Mínimo, média, máximo, jitter (variação média entre respostas seguidas) e perda."""
    validas = [a for a in amostras if a is not None]
    total = len(amostras)
    perda = round((total - len(validas)) / total * 100, 1) if total else 100.0
    if not validas:
        return {"amostras": total, "minimo_ms": None, "media_ms": None,
                "maximo_ms": None, "jitter_ms": None, "perda_pct": perda}
    variacoes = [abs(b - a) for a, b in zip(validas, validas[1:])]
    return {
        "amostras": total,
        "minimo_ms": round(min(validas), 2),
        "media_ms": round(sum(validas) / len(validas), 2),
        "maximo_ms": round(max(validas), 2),
        "jitter_ms": round(sum(variacoes) / len(variacoes), 2) if variacoes else 0.0,
        "perda_pct": perda,
    }


def medir_latencia(host: str, amostras: int = 10, porta: int = 80, intervalo: float = 0.2,
                   sleep: Callable[[float], None] = time.sleep) -> dict:
    valores = []
    for i in range(amostras):
        valores.append(medir_rtt(host, porta))
        if i < amostras - 1:
            sleep(intervalo)
    return {"host": host, "porta": porta, **estatisticas(valores)}


def testar_dns(dominios: list[str]) -> list[dict]:
    resultados = []
    for dominio in dominios:
        inicio = time.perf_counter()
        try:
            socket.getaddrinfo(dominio, 443, proto=socket.IPPROTO_TCP)
            ms = round((time.perf_counter() - inicio) * 1000, 1)
            resultados.append({"dominio": dominio, "ms": ms, "erro": None})
        except OSError as exc:
            resultados.append({"dominio": dominio, "ms": None, "erro": str(exc)})
    return resultados


def amostrar(ler: Callable[[], dict], amostras: int, intervalo: float = 2.0,
             sleep: Callable[[float], None] = time.sleep,
             ao_amostrar: Callable[[dict], None] | None = None) -> list[dict]:
    """Lê o estado do Wi-Fi várias vezes. Erros de leitura viram uma linha com o campo 'erro'."""
    registros = []
    for i in range(amostras):
        inicio = time.monotonic()
        try:
            dados = ler()
        except Exception as exc:  # leitura de monitoramento não deve interromper a coleta
            dados = {"erro": str(exc)}
        registro = {"momento": datetime.now().isoformat(timespec="seconds"), **dados}
        registros.append(registro)
        if ao_amostrar:
            ao_amostrar(registro)
        if i < amostras - 1:
            sleep(max(0.0, intervalo - (time.monotonic() - inicio)))
    return registros
