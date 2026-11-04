package br.com.esmeraldorg.wifianalyzer;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Testes da lógica Java (sem Android). Não faz parte do APK.
 * Rodar: javac -d /tmp/x app/src/main/java/br/com/esmeraldorg/wifianalyzer/{Analise,TesteRede}.java testes/LogicaTeste.java
 *        java -cp /tmp/x br.com.esmeraldorg.wifianalyzer.LogicaTeste
 */
public final class LogicaTeste {
    private static int falhas = 0;
    private static int total = 0;

    private static void verifica(boolean ok, String nome) {
        total++;
        if (!ok) {
            falhas++;
            System.out.println("FALHOU: " + nome);
        }
    }

    private static Analise.RedeWifi rede(String ssid, String bssid, int freq, int nivel, String caps) {
        return new Analise.RedeWifi(ssid, bssid, freq, nivel, caps, false);
    }

    public static void main(String[] args) throws Exception {
        // rádio
        verifica(Analise.canalDeFrequencia(2437) == 6, "canal 2437 = 6");
        verifica(Analise.canalDeFrequencia(2484) == 14, "canal 2484 = 14");
        verifica(Analise.canalDeFrequencia(5180) == 36, "canal 5180 = 36");
        verifica(Analise.canalDeFrequencia(5955) == 1, "canal 6GHz 5955 = 1");
        verifica(Analise.bandaDeFrequencia(5180).equals("5 GHz"), "banda 5 GHz");
        verifica(Analise.bandaDeFrequencia(900) == null, "frequência fora das bandas");

        // segurança a partir das capabilities do Android
        verifica(Analise.classificarSeguranca("[ESS]").equals("Aberta"), "aberta");
        verifica(Analise.classificarSeguranca("[WEP][ESS]").equals("WEP"), "WEP");
        verifica(Analise.classificarSeguranca("[WPA-PSK-TKIP][ESS]").equals("WPA"), "WPA1");
        verifica(Analise.classificarSeguranca("[WPA2-PSK-CCMP][ESS]").equals("WPA2"), "WPA2");
        verifica(Analise.classificarSeguranca("[WPA2-PSK-CCMP+TKIP][ESS]").equals("WPA2 (TKIP)"), "WPA2 TKIP");
        verifica(Analise.classificarSeguranca("[WPA3-SAE-CCMP][ESS]").equals("WPA3"), "WPA3");
        verifica(Analise.classificarSeguranca("[WPA2-PSK-CCMP][WPA3-SAE-CCMP][ESS]")
                .equals("WPA2/WPA3 (transição)"), "transição");
        verifica(Analise.classificarSeguranca("[WPA2-EAP-CCMP][ESS]").equals("WPA2-Enterprise"), "enterprise");

        // qualidade e classes
        verifica(Analise.qualidade(-50) == 100, "qualidade -50 = 100");
        verifica(Analise.qualidade(-100) == 0, "qualidade -100 = 0");
        verifica(Analise.classificarSinal(-45).equals("excelente"), "sinal excelente");
        verifica(Analise.classificarSinal(-85).equals("muito fraco"), "sinal muito fraco");

        // ranking: 2.4 GHz não deve escolher o canal 6 com vizinhos nele
        List<Analise.RedeWifi> redes = new ArrayList<>();
        redes.add(rede("A", "00:00:00:00:00:01", 2437, -50, "[WPA2-PSK-CCMP][ESS]"));
        redes.add(rede("B", "00:00:00:00:00:02", 2412, -70, "[WPA2-PSK-CCMP][ESS]"));
        redes.add(rede("C", "00:00:00:00:00:03", 2462, -80, "[ESS]"));
        Map<String, List<Analise.Canal>> ranking = Analise.ranking(redes);
        verifica(!ranking.get("2.4 GHz").get(0).equals(null), "ranking 2.4 existe");
        verifica(ranking.get("2.4 GHz").get(0).canal != 6, "melhor canal 2.4 não é 6");
        Analise.Canal livre = null;
        for (Analise.Canal c : ranking.get("2.4 GHz")) {
            if (c.canal == 13) livre = c;
        }
        verifica(livre != null && livre.redes == 1, "canal 13 vizinho só do canal 11 (rede C)");

        // auditoria
        List<Analise.RedeWifi> seg = new ArrayList<>();
        seg.add(rede("Cafe", "a1", 2412, -60, "[ESS]"));
        seg.add(rede("Casa", "b1", 2437, -60, "[WPA2-PSK-CCMP][WPS][ESS]"));
        seg.add(rede("Casa", "b2", 5180, -60, "[WPA3-SAE-CCMP][ESS]"));
        seg.add(rede("", "c1", 5745, -60, "[WPA2-PSK-CCMP][ESS]"));
        List<Analise.Achado> achados = Analise.auditar(seg);
        StringBuilder textos = new StringBuilder();
        for (Analise.Achado a : achados) textos.append(a.mensagem).append('|');
        verifica(textos.toString().contains("Rede aberta"), "auditoria: aberta");
        verifica(textos.toString().contains("WPS ativo"), "auditoria: WPS");
        verifica(textos.toString().contains("evil twin"), "auditoria: evil twin");
        verifica(textos.toString().contains("SSID oculto"), "auditoria: oculto");
        verifica(achados.get(0).gravidade.equals("crítica"), "crítica primeiro");

        // estatísticas de rede
        TesteRede.Estatisticas e = TesteRede.estatisticas(new double[]{10, 20, Double.NaN, 30});
        verifica(e.minimo == 10 && e.maximo == 30, "min/max");
        verifica(e.media == 20, "média 20");
        verifica(e.jitter == 10, "jitter 10");
        verifica(e.perda == 25, "perda 25%");
        verifica(Double.isNaN(TesteRede.estatisticas(new double[]{Double.NaN}).media), "sem respostas");

        // IP do DhcpInfo (little-endian): 0x0102A8C0 -> 192.168.2.1
        verifica(TesteRede.ipDeInteiro(0x0102A8C0).equals("192.168.2.1"), "ip little-endian");

        // RTT em porta fechada conta como resposta; em porta com servidor também
        try (ServerSocket ss = new ServerSocket(0)) {
            double rtt = TesteRede.rtt("127.0.0.1", ss.getLocalPort(), 1000);
            verifica(!Double.isNaN(rtt), "rtt com servidor");
        }
        int portaLivre;
        try (ServerSocket ss = new ServerSocket(0)) {
            portaLivre = ss.getLocalPort();
        }
        {
            int porta = portaLivre;
            double rtt = TesteRede.rtt("127.0.0.1", porta, 1000);
            verifica(!Double.isNaN(rtt), "rtt com porta recusada");
        }

        // diagnóstico
        List<String[]> sem = Analise.diagnosticar(false, 0, 0, null, null, null);
        verifica(sem.get(0)[0].equals("alta"), "diagnóstico sem conexão é alta");
        TesteRede.Estatisticas ruim = TesteRede.estatisticas(new double[]{50, 60, Double.NaN, 90});
        List<TesteRede.Dns> dns = new ArrayList<>();
        dns.add(new TesteRede.Dns("x.test", Double.NaN));
        String textosDiag = "";
        for (String[] d : Analise.diagnosticar(true, -78, 2437, seg, ruim, dns)) textosDiag += d[1] + "|";
        verifica(textosDiag.contains("Sinal fraco"), "diagnóstico: sinal fraco");
        verifica(textosDiag.contains("Latência alta"), "diagnóstico: latência");
        verifica(textosDiag.contains("Perda de pacotes"), "diagnóstico: perda");
        verifica(textosDiag.contains("Falha ao resolver"), "diagnóstico: DNS");

        // tabela ARP: entradas incompletas são ignoradas
        String arp = "IP address HW type Flags HW address Mask Device\n"
                + "192.168.1.1 0x1 0x2 AA:BB:CC:DD:EE:FF * wlan0\n"
                + "192.168.1.9 0x1 0x0 00:00:00:00:00:00 * wlan0\n";
        Map<String, String> mapaArp = TesteRede.analisarArp(arp);
        verifica(mapaArp.size() == 1 && "aa:bb:cc:dd:ee:ff".equals(mapaArp.get("192.168.1.1")), "arp parse");

        // busca de aparelhos: no loopback todos os endereços 127.0.0.x respondem (recusa conta)
        List<TesteRede.Dispositivo> aparelhos = TesteRede.descobrirDispositivos("127.0.0", 300);
        boolean achouLocal = false;
        for (TesteRede.Dispositivo d : aparelhos) if (d.ip.equals("127.0.0.1")) achouLocal = true;
        verifica(achouLocal, "descoberta encontra 127.0.0.1");
        verifica(aparelhos.size() >= 2, "descoberta encontra vários endereços");

        System.out.println(total + " verificações, " + falhas + " falhas.");
        if (falhas > 0) System.exit(1);
    }
}
