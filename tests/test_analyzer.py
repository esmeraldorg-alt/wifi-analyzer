import socket
import unittest
from pathlib import Path

from wifi_analyzer import analysis, cli, network, report, wifi
from wifi_analyzer.parsers import parse_iw_dev, parse_iw_link, parse_iw_scan
from wifi_analyzer.radio import banda_de_frequencia, canal_de_frequencia, classificar_seguranca

FIX = Path(__file__).parent / "fixtures"


def fixture(nome: str) -> str:
    return (FIX / nome).read_text(encoding="utf-8")


class RadioTests(unittest.TestCase):
    def test_canais_2_4_5_e_6_ghz(self):
        self.assertEqual(canal_de_frequencia(2412), 1)
        self.assertEqual(canal_de_frequencia(2437), 6)
        self.assertEqual(canal_de_frequencia(2484), 14)
        self.assertEqual(canal_de_frequencia(5180), 36)
        self.assertEqual(canal_de_frequencia(5745), 149)
        self.assertEqual(canal_de_frequencia(5955), 1)
        self.assertIsNone(canal_de_frequencia(None))

    def test_bandas(self):
        self.assertEqual(banda_de_frequencia(2437), "2.4 GHz")
        self.assertEqual(banda_de_frequencia(5180), "5 GHz")
        self.assertEqual(banda_de_frequencia(6115), "6 GHz")
        self.assertIsNone(banda_de_frequencia(900))

    def test_classificacao_de_seguranca(self):
        self.assertEqual(classificar_seguranca(), "Aberta")
        self.assertEqual(classificar_seguranca(privacy=True), "WEP")
        self.assertEqual(classificar_seguranca(rsn=True, psk=True), "WPA2")
        self.assertEqual(classificar_seguranca(rsn=True, sae=True), "WPA3")
        self.assertEqual(classificar_seguranca(rsn=True, sae=True, psk=True), "WPA2/WPA3 (transição)")
        self.assertEqual(classificar_seguranca(rsn=True, psk=True, tkip=True), "WPA2 (TKIP)")
        self.assertEqual(classificar_seguranca(rsn=True, eap=True), "WPA2-Enterprise")


class ParserTests(unittest.TestCase):
    def setUp(self):
        self.redes = parse_iw_scan(fixture("iw_scan.txt"))
        self.por_bssid = {ap.bssid: ap for ap in self.redes}

    def test_quantidade_de_redes(self):
        self.assertEqual(len(self.redes), 7)

    def test_campos_basicos(self):
        ap = self.por_bssid["00:11:22:33:44:55"]
        self.assertEqual(ap.ssid, "CasaSilva")
        self.assertEqual(ap.canal, 6)
        self.assertEqual(ap.banda, "2.4 GHz")
        self.assertEqual(ap.sinal_dbm, -48.0)
        self.assertTrue(ap.conectada)
        self.assertTrue(ap.wps)

    def test_seguranca_de_cada_rede(self):
        self.assertEqual(self.por_bssid["00:11:22:33:44:55"].seguranca, "WPA2")
        self.assertEqual(self.por_bssid["aa:bb:cc:00:00:01"].seguranca, "WPA2/WPA3 (transição)")
        self.assertEqual(self.por_bssid["aa:bb:cc:00:00:02"].seguranca, "WPA2 (TKIP)")
        self.assertEqual(self.por_bssid["aa:bb:cc:00:00:03"].seguranca, "Aberta")
        self.assertEqual(self.por_bssid["aa:bb:cc:00:00:04"].seguranca, "WEP")
        self.assertEqual(self.por_bssid["aa:bb:cc:00:00:05"].seguranca, "WPA3")

    def test_ssid_oculto_e_canal_pela_frequencia(self):
        oculta = self.por_bssid["aa:bb:cc:00:00:06"]
        self.assertEqual(oculta.ssid, "")
        self.assertEqual(oculta.canal, 149)

    def test_wps_e_tkip(self):
        viz = self.por_bssid["aa:bb:cc:00:00:02"]
        self.assertTrue(viz.wps)
        self.assertTrue(viz.tkip)

    def test_conexao_atual(self):
        info = parse_iw_link(fixture("iw_link.txt"))
        self.assertTrue(info["conectado"])
        self.assertEqual(info["bssid"], "00:11:22:33:44:55")
        self.assertEqual(info["sinal_dbm"], -48.0)
        self.assertEqual(info["freq_mhz"], 2437)
        self.assertEqual(info["taxa_rx"], "72.2 MBit/s MCS 7 short GI")

    def test_sem_conexao(self):
        self.assertFalse(parse_iw_link("Not connected.\n")["conectado"])

    def test_interfaces(self):
        self.assertEqual(parse_iw_dev("phy#0\n\tInterface wlan0\n\t\tifindex 3\n"), ["wlan0"])


class AnalysisTests(unittest.TestCase):
    def setUp(self):
        self.redes = parse_iw_scan(fixture("iw_scan.txt"))

    def test_qualidade_do_sinal(self):
        self.assertEqual(analysis.qualidade_sinal(-50), 100)
        self.assertEqual(analysis.qualidade_sinal(-100), 0)
        self.assertEqual(analysis.qualidade_sinal(-70), 60)
        self.assertEqual(analysis.qualidade_sinal(None), 0)

    def test_classificacao_do_sinal(self):
        self.assertEqual(analysis.classificar_sinal(-45), "excelente")
        self.assertEqual(analysis.classificar_sinal(-85), "muito fraco")

    def test_ranking_2_4ghz_prefere_canais_nao_sobrepostos(self):
        ranking = analysis.ranking_canais(self.redes)
        melhor = ranking["2.4 GHz"][0]
        # Canais 1 e 11 não se sobrepõem a 6: o canal com menos interferência não deve ser 6.
        self.assertNotEqual(melhor["canal"], 6)
        self.assertIn(melhor["canal"], range(1, 14))

    def test_ranking_5ghz_sem_vizinhos_e_livre(self):
        ranking = analysis.ranking_canais(self.redes)
        livres = [x for x in ranking["5 GHz"] if x["redes"] == 0]
        self.assertTrue(livres)
        self.assertIsNone(livres[0]["interferencia_dbm"])

    def test_auditoria_encontra_problemas_esperados(self):
        achados = analysis.auditar_seguranca(self.redes)
        mensagens = " | ".join(a["mensagem"] for a in achados)
        self.assertIn("Rede aberta", mensagens)
        self.assertIn("WEP", mensagens)
        self.assertIn("WPS ativo", mensagens)
        self.assertIn("possível rede falsa", mensagens)
        self.assertIn("SSID oculto", mensagens)
        self.assertEqual(achados[0]["gravidade"], "crítica")

    def test_diagnostico_sem_conexao(self):
        dicas = analysis.diagnosticar({"conectado": False}, None, None)
        self.assertEqual(dicas[0][0], "alta")

    def test_diagnostico_com_problemas(self):
        link = {"conectado": True, "sinal_dbm": -78, "freq_mhz": 2437}
        latencia = {"media_ms": 45.0, "jitter_ms": 20.0, "perda_pct": 10.0}
        dns = [{"dominio": "x.test", "ms": None, "erro": "falhou"}]
        textos = " ".join(m for _, m in analysis.diagnosticar(link, latencia, dns, self.redes))
        self.assertIn("Sinal fraco", textos)
        self.assertIn("Latência alta", textos)
        self.assertIn("Jitter alto", textos)
        self.assertIn("Perda de pacotes", textos)
        self.assertIn("Falha ao resolver", textos)

    def test_diagnostico_sem_problemas(self):
        link = {"conectado": True, "sinal_dbm": -40, "freq_mhz": 5180}
        latencia = {"media_ms": 3.0, "jitter_ms": 1.0, "perda_pct": 0.0}
        dicas = analysis.diagnosticar(link, latencia, [{"dominio": "a", "ms": 20.0, "erro": None}])
        self.assertEqual(dicas, [("info", "Nenhum problema encontrado.")])


class NetworkTests(unittest.TestCase):
    def test_gateway_padrao_em_ordem_invertida(self):
        self.assertEqual(network.gateway_padrao(fixture("proc_net_route.txt")), "192.168.2.1")

    def test_gateway_ausente(self):
        self.assertIsNone(network.gateway_padrao("Iface\tDestination\n"))

    def test_estatisticas(self):
        s = network.estatisticas([10.0, 20.0, None, 30.0])
        self.assertEqual(s["minimo_ms"], 10.0)
        self.assertEqual(s["maximo_ms"], 30.0)
        self.assertEqual(s["media_ms"], 20.0)
        self.assertEqual(s["jitter_ms"], 10.0)
        self.assertEqual(s["perda_pct"], 25.0)

    def test_estatisticas_sem_respostas(self):
        s = network.estatisticas([None, None])
        self.assertIsNone(s["media_ms"])
        self.assertEqual(s["perda_pct"], 100.0)

    def test_rtt_em_porta_fechada_ainda_responde(self):
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            porta = s.getsockname()[1]
        self.assertIsNotNone(network.medir_rtt("127.0.0.1", porta, timeout=1.0))

    def test_amostragem_registra_erros(self):
        contador = iter([{"sinal_dbm": -50}, RuntimeError("falhou")])

        def ler():
            valor = next(contador)
            if isinstance(valor, Exception):
                raise valor
            return valor

        registros = network.amostrar(ler, amostras=2, intervalo=0, sleep=lambda s: None)
        self.assertEqual(registros[0]["sinal_dbm"], -50)
        self.assertEqual(registros[1]["erro"], "falhou")


class CliTests(unittest.TestCase):
    def test_scan_com_arquivo_gera_tabela(self):
        saida = self._executar(["scan", "--arquivo", str(FIX / "iw_scan.txt")])
        self.assertIn("CasaSilva", saida)
        self.assertIn("7 rede(s) encontrada(s)", saida)

    def test_seguranca_com_arquivo(self):
        saida = self._executar(["seguranca", "--arquivo", str(FIX / "iw_scan.txt")])
        self.assertIn("Rede aberta", saida)

    def test_canais_com_arquivo(self):
        saida = self._executar(["canais", "--arquivo", str(FIX / "iw_scan.txt")])
        self.assertIn("2.4 GHz", saida)

    def test_relatorio_markdown(self):
        redes = parse_iw_scan(fixture("iw_scan.txt"))
        md = report.relatorio_markdown(redes, analysis.ranking_canais(redes), analysis.auditar_seguranca(redes))
        self.assertTrue(md.startswith("# Relatório Wi-Fi"))
        self.assertIn("## Segurança", md)

    def test_sem_ferramenta_mostra_erro_amigavel(self):
        original = wifi.shutil.which
        wifi.shutil.which = lambda nome: None
        try:
            with self.assertRaises(wifi.ErroFerramenta) as ctx:
                wifi.executar(["iw", "dev"])
            self.assertIn("apt install iw", str(ctx.exception))
        finally:
            wifi.shutil.which = original

    def _executar(self, args):
        import contextlib
        import io
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            codigo = cli.main(args)
        self.assertEqual(codigo, 0)
        return buf.getvalue()


if __name__ == "__main__":
    unittest.main()
