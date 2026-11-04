package br.com.esmeraldorg.wifianalyzer;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.DhcpInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Tela única com botões para cada análise. Os resultados aparecem na área de texto. */
public class MainActivity extends Activity {
    private static final int PEDIDO_PERMISSAO = 1;
    private static final String[] DOMINIOS = {"google.com", "cloudflare.com", "wikipedia.org"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView saida;
    private WifiManager wm;

    /** Ação esperando permissão de localização. */
    private String acaoPendente;
    /** Visão de varredura (redes/canais/segurança) esperando o resultado do scan. */
    private String visaoDeScan;

    private final BroadcastReceiver resultadoScan = new BroadcastReceiver() {
        @Override
        public void onReceive(Context contexto, Intent intent) {
            String visao = visaoDeScan;
            visaoDeScan = null;
            if (visao != null) mostrarVisaoDeScan(visao);
        }
    };

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        int margem = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setPadding(margem, margem, margem, margem);

        String[][] botoes = {
                {"Conexão", "conexao"}, {"Redes", "redes"},
                {"Canais", "canais"}, {"Segurança", "seguranca"},
                {"Latência", "latencia"}, {"DNS", "dns"},
                {"Diagnóstico", "diagnostico"}
        };
        for (int i = 0; i < botoes.length; i += 2) {
            LinearLayout linha = new LinearLayout(this);
            linha.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < Math.min(i + 2, botoes.length); j++) {
                Button b = new Button(this);
                b.setText(botoes[j][0]);
                String id = botoes[j][1];
                b.setOnClickListener(v -> acao(id));
                linha.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            raiz.addView(linha);
        }

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, margem, 0, margem);
        status.setText("Escolha uma análise acima.");
        raiz.addView(status);

        saida = new TextView(this);
        saida.setTypeface(Typeface.MONOSPACE);
        saida.setTextSize(13);
        ScrollView rolagem = new ScrollView(this);
        rolagem.addView(saida);
        raiz.addView(rolagem, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(raiz);
        registerReceiver(resultadoScan, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(resultadoScan);
    }

    private void acao(String id) {
        if (!temLocalizacao()) {
            acaoPendente = id;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PEDIDO_PERMISSAO);
            return;
        }
        if (!wm.isWifiEnabled()) {
            mostrarStatus("O Wi-Fi está desligado. Ligue-o e tente de novo.");
            return;
        }
        switch (id) {
            case "conexao":
                mostrarConexao();
                break;
            case "latencia":
                testarLatencia();
                break;
            case "dns":
                testarDns();
                break;
            case "diagnostico":
                diagnostico();
                break;
            default: // redes, canais, seguranca: precisam de uma varredura nova
                visaoDeScan = id;
                mostrarStatus("Procurando redes Wi-Fi...");
                if (!wm.startScan()) {
                    // o Android limita varreduras frequentes: usa o último resultado disponível
                    visaoDeScan = null;
                    mostrarVisaoDeScan(id);
                }
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int codigo, String[] permissoes, int[] resultados) {
        super.onRequestPermissionsResult(codigo, permissoes, resultados);
        if (codigo != PEDIDO_PERMISSAO) return;
        boolean concedida = resultados.length > 0 && resultados[0] == PackageManager.PERMISSION_GRANTED;
        String pendente = acaoPendente;
        acaoPendente = null;
        if (concedida && pendente != null) {
            acao(pendente);
        } else {
            mostrarStatus("Sem permissão de localização não é possível ler as redes Wi-Fi.");
        }
    }

    private boolean temLocalizacao() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    // ---------- leitura do Wi-Fi ----------

    private String bssidConectado() {
        WifiInfo info = wm.getConnectionInfo();
        return info == null ? null : info.getBSSID();
    }

    private List<Analise.RedeWifi> lerRedes() {
        List<Analise.RedeWifi> redes = new ArrayList<>();
        List<ScanResult> resultados = wm.getScanResults();
        String conectado = bssidConectado();
        if (resultados == null) return redes;
        for (ScanResult r : resultados) {
            boolean ligada = conectado != null && conectado.equalsIgnoreCase(r.BSSID);
            redes.add(new Analise.RedeWifi(r.SSID, r.BSSID, r.frequency, r.level, r.capabilities, ligada));
        }
        return redes;
    }

    // ---------- visões ----------

    private void mostrarVisaoDeScan(String visao) {
        List<Analise.RedeWifi> redes = lerRedes();
        if (redes.isEmpty()) {
            mostrarStatus("Nenhuma rede encontrada. Verifique se a localização está ligada.");
            saida.setText("");
            return;
        }
        switch (visao) {
            case "redes":
                saida.setText(textoRedes(redes));
                break;
            case "canais":
                saida.setText(textoCanais(redes));
                break;
            case "seguranca":
                saida.setText(textoSeguranca(redes));
                break;
            default:
                break;
        }
        mostrarStatus(redes.size() + " rede(s) encontrada(s).");
    }

    private void mostrarConexao() {
        WifiInfo info = wm.getConnectionInfo();
        if (info == null || info.getNetworkId() == -1 || info.getBSSID() == null) {
            mostrarStatus("Não conectado a nenhuma rede Wi-Fi.");
            saida.setText("");
            return;
        }
        String ssid = info.getSSID() == null ? "-" : info.getSSID().replace("\"", "");
        int freq = info.getFrequency();
        int canal = Analise.canalDeFrequencia(freq);
        String banda = Analise.bandaDeFrequencia(freq);
        int rssi = info.getRssi();
        StringBuilder sb = new StringBuilder();
        sb.append("SSID:      ").append(ssid).append('\n');
        sb.append("BSSID:     ").append(info.getBSSID()).append('\n');
        sb.append("Canal:     ").append(canal).append(" (").append(banda == null ? "-" : banda).append(")\n");
        sb.append("Sinal:     ").append(rssi).append(" dBm — ").append(Analise.classificarSinal(rssi))
                .append(" (").append(Analise.qualidade(rssi)).append("%)\n");
        sb.append("Velocidade: ").append(info.getLinkSpeed()).append(" Mbps\n");
        saida.setText(sb.toString());
        mostrarStatus("Conexão atual.");
    }

    private String textoRedes(List<Analise.RedeWifi> redes) {
        List<Analise.RedeWifi> ordenadas = new ArrayList<>(redes);
        ordenadas.sort((a, b) -> Integer.compare(b.nivelDbm, a.nivelDbm));
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-20s %-17s %5s %6s %-22s%n", "SSID", "BSSID", "Canal", "Sinal", "Segurança"));
        for (Analise.RedeWifi r : ordenadas) {
            sb.append(String.format("%-20s %-17s %5d %6d %-22s%s%n",
                    cortar(r.nome(), 20), r.bssid, r.canal, r.nivelDbm, r.seguranca,
                    r.conectada ? " *" : ""));
        }
        sb.append("\n* = rede conectada");
        return sb.toString();
    }

    private String textoCanais(List<Analise.RedeWifi> redes) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<Analise.Canal>> e : Analise.ranking(redes).entrySet()) {
            sb.append(e.getKey()).append(" — melhor canal: ").append(e.getValue().get(0).canal).append('\n');
            sb.append(String.format("  %6s %15s %15s%n", "Canal", "Redes próximas", "Interferência"));
            int n = 0;
            for (Analise.Canal c : e.getValue()) {
                if (n++ >= 5) break;
                String inter = Double.isNaN(c.interferenciaDbm())
                        ? "livre" : String.format("%.1f dBm", c.interferenciaDbm());
                sb.append(String.format("  %6d %15d %15s%n", c.canal, c.redes, inter));
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private String textoSeguranca(List<Analise.RedeWifi> redes) {
        List<Analise.Achado> achados = Analise.auditar(redes);
        if (achados.isEmpty()) return "Nenhum problema de segurança encontrado.";
        StringBuilder sb = new StringBuilder();
        for (Analise.Achado a : achados) {
            sb.append('[').append(a.gravidade.toUpperCase()).append("] ")
                    .append(a.ssid).append(" (").append(a.bssid).append(")\n");
            sb.append("    ").append(a.mensagem).append("\n\n");
        }
        return sb.toString().trim();
    }

    // ---------- testes em segundo plano ----------

    private void testarLatencia() {
        String gateway = gatewayAtual();
        if (gateway == null) {
            mostrarStatus("Gateway não encontrado. Conecte-se ao Wi-Fi.");
            return;
        }
        mostrarStatus("Medindo latência até " + gateway + "...");
        new Thread(() -> {
            try {
                TesteRede.Estatisticas s = TesteRede.medirLatencia(gateway, 10, 80, 200);
                ui.post(() -> {
                    saida.setText("Host: " + gateway + ":80 (10 amostras)\n"
                            + String.format("Mínimo:   %.2f ms%n", s.minimo)
                            + String.format("Média:    %.2f ms%n", s.media)
                            + String.format("Máximo:   %.2f ms%n", s.maximo)
                            + String.format("Jitter:   %.2f ms%n", s.jitter)
                            + String.format("Perda:    %.0f%%", s.perda));
                    mostrarStatus("Teste de latência concluído.");
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void testarDns() {
        mostrarStatus("Testando DNS...");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            for (String dominio : DOMINIOS) {
                TesteRede.Dns d = TesteRede.tempoDns(dominio);
                sb.append(String.format("%-22s %s%n", dominio,
                        Double.isNaN(d.ms) ? "FALHOU" : String.format("%.1f ms", d.ms)));
            }
            ui.post(() -> {
                saida.setText(sb.toString().trim());
                mostrarStatus("Teste de DNS concluído.");
            });
        }).start();
    }

    private void diagnostico() {
        mostrarStatus("Executando diagnóstico...");
        new Thread(() -> {
            WifiInfo info = wm.getConnectionInfo();
            boolean conectado = info != null && info.getNetworkId() != -1 && info.getBSSID() != null;
            int rssi = conectado ? info.getRssi() : 0;
            int freq = conectado ? info.getFrequency() : 0;
            // usa o último resultado de varredura: uma varredura nova demoraria demais aqui
            List<Analise.RedeWifi> redes = lerRedes();
            String gateway = gatewayAtual();
            TesteRede.Estatisticas lat = null;
            try {
                if (gateway != null) lat = TesteRede.medirLatencia(gateway, 10, 80, 200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            List<TesteRede.Dns> dns = new ArrayList<>();
            for (String d : DOMINIOS) dns.add(TesteRede.tempoDns(d));

            List<String[]> dicas = Analise.diagnosticar(conectado, rssi, freq, redes, lat, dns);
            StringBuilder sb = new StringBuilder();
            for (String[] d : dicas) {
                sb.append('[').append(d[0].toUpperCase()).append("] ").append(d[1]).append('\n');
            }
            ui.post(() -> {
                saida.setText(sb.toString().trim());
                mostrarStatus("Diagnóstico concluído.");
            });
        }).start();
    }

    private String gatewayAtual() {
        DhcpInfo dhcp = wm.getDhcpInfo();
        if (dhcp == null || dhcp.gateway == 0) return null;
        return TesteRede.ipDeInteiro(dhcp.gateway);
    }

    private void mostrarStatus(String texto) {
        status.setText(texto);
    }

    private static String cortar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
