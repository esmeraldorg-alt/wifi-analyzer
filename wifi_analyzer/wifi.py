"""Chamadas ao utilitário `iw` (Linux). Só funciona onde `iw` está instalado."""
from __future__ import annotations

import shutil
import subprocess

from .parsers import parse_iw_dev, parse_iw_link


class ErroFerramenta(RuntimeError):
    """Erro com mensagem pronta para o usuário."""


def executar(args: list[str], timeout: float = 30) -> str:
    if shutil.which(args[0]) is None:
        raise ErroFerramenta(f"'{args[0]}' não encontrado. Instale com: sudo apt install iw")
    try:
        proc = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired as exc:
        raise ErroFerramenta(f"'{' '.join(args)}' demorou mais de {timeout:.0f}s") from exc
    if proc.returncode != 0:
        detalhe = proc.stderr.strip() or f"código {proc.returncode}"
        if "Operation not permitted" in detalhe or "Permission denied" in detalhe:
            detalhe += " (tente rodar com sudo)"
        raise ErroFerramenta(f"'{' '.join(args)}' falhou: {detalhe}")
    return proc.stdout


def interfaces() -> list[str]:
    return parse_iw_dev(executar(["iw", "dev"]))


def interface_padrao() -> str:
    lista = interfaces()
    if not lista:
        raise ErroFerramenta("Nenhuma interface Wi-Fi encontrada (saída de 'iw dev' vazia).")
    return lista[0]


def escanear(iface: str) -> str:
    return executar(["iw", "dev", iface, "scan"], timeout=60)


def conexao(iface: str) -> dict:
    return parse_iw_link(executar(["iw", "dev", iface, "link"]))
