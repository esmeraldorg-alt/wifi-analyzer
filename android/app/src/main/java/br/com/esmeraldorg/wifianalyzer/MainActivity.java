package br.com.esmeraldorg.wifianalyzer;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.DhcpInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Tela com cartões: cada análise aparece em blocos coloridos que ficam fáceis de ler. */
public class MainActivity extends Activity {
    private static final int PEDIDO_PERMISSAO = 1;
    private static final String[] DOMINIOS = {"google.com", "cloudflare.com", "wikipedia.org"};

    // paleta
    private static final int FUNDO = 0xFF0B1220;
    private static final int CARTAO = 0xFF16213A;
    private static final int BORDA = 0xFF26365E;
    private static final int TEXTO = 0xFFE8EEF9;
    private static final int CINZA = 0xFF8FA3C2;
    private static final int AZUL = 0xFF3BA3FF;
    private static final int VERDE = 0xFF2ED47A;
    private static final int AMARELO = 0xFFFFC93C;
    private static final int VERMELHO = 0xFFFF5C5C;
    private static final int LARANJA = 0xFFFF9248;
    private static final int ROXO = 0xFF9B7BFF;
    private static final int CIANO = 0xFF2EE6D6;
    private static final int ROSA = 0xFFFF6FB5;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WifiManager wm;
    private float densidade;
    private TextView status;
    private LinearLayout resultados;

    /** Ação esperando a permissão de localização. */
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
        densidade = getResources().getDisplayMetrics().density;
        getWindow().setStatusBarColor(FUNDO);

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(FUNDO);
        raiz.setPadding(dp(16), dp(20), dp(16), dp(12));

        TextView titulo = texto("Analisador Wi-Fi", 28, TEXTO, true);
        raiz.addView(titulo);
        TextView sub = texto("Veja o que está em volta e quem está na sua rede", 14, CINZA, false);
        raiz.addView(sub, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, dp(14)));

        String[] nomes = {"Conexão", "Redes", "Canais", "Segurança", "Dispositivos", "Latência", "DNS", "Diagnóstico"};
        String[] ids = {"conexao", "redes", "canais", "seguranca", "dispositivos", "latencia", "dns", "diagnostico"};
        int[] cores = {AZUL, CIANO, VERDE, VERMELHO, ROSA, AMARELO, ROXO, LARANJA};
        for (int i = 0; i < nomes.length; i += 2) {
            LinearLayout linha = new LinearLayout(this);
            linha.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < i + 2; j++) {
                TextView botao = texto(nomes[j], 16, cores[j], true);
                botao.setGravity(Gravity.CENTER);
                botao.setPadding(dp(8), dp(16), dp(8), dp(16));
                botao.setBackground(forma(CARTAO, dp(14), cores[j], 1));
                String id = ids[j];
                botao.setOnClickListener(v -> acao(id));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                p.setMargins(dp(4), dp(4), dp(4), dp(4));
                linha.addView(botao, p);
            }
            raiz.addView(linha);
        }

        status = texto("Escolha uma análise acima.", 14, CINZA, false);
        status.setPadding(dp(4), dp(12), dp(4), dp(8));
        raiz.addView(status);

        resultados = new LinearLayout(this);
        resultados.setOrientation(LinearLayout.VERTICAL);
        ScrollView rolagem = new ScrollView(this);
        rolagem.addView(resultados);
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
        boolean precisaLocalizacao = id.equals("conexao") || id.equals("redes") || id.equals("canais")
                || id.equals("seguranca") || id.equals("diagnostico");
        if (precisaLocalizacao && !temLocalizacao()) {
            acaoPendente = id;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PEDIDO_PERMISSAO);
            return;
        }
        boolean precisaWifi = !id.equals("latencia") && !id.equals("dns");
        if (precisaWifi && !wm.isWifiEnabled()) {
            mostrarStatus("O Wi-Fi está desligado. Ligue-o e tente de novo.");
            return;
        }
        switch (id) {
            case "conexao":
                mostrarConexao();
                break;
            case "dispositivos":
                mostrarDispositivos();
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
                limpar();
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
    public void onRequestPermissionsResult(int codigo, String[] permissoes, int[] resultadosPermissao) {
        super.onRequestPermissionsResult(codigo, permissoes, resultadosPermissao);
        if (codigo != PEDIDO_PERMISSAO) return;
        boolean concedida = resultadosPermissao.length > 0
                && resultadosPermissao[0] == PackageManager.PERMISSION_GRANTED;
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

    private List<Analise.RedeWifi> lerRedes() {
        List<Analise.RedeWifi> redes = new ArrayList<>();
        List<ScanResult> resultadosScan = wm.getScanResults();
        WifiInfo info = wm.getConnectionInfo();
        String conectado = info == null ? null : info.getBSSID();
        if (resultadosScan == null) return redes;
        for (ScanResult r : resultadosScan) {
            boolean ligada = conectado != null && conectado.equalsIgnoreCase(r.BSSID);
            redes.add(new Analise.RedeWifi(r.SSID, r.BSSID, r.frequency, r.level, r.capabilities, ligada));
        }
        return redes;
    }

    private String ipLocal() {
        DhcpInfo d = wm.getDhcpInfo();
        return d == null || d.ipAddress == 0 ? null : TesteRede.ipDeInteiro(d.ipAddress);
    }

    private String gatewayAtual() {
        DhcpInfo d = wm.getDhcpInfo();
        return d == null || d.gateway == 0 ? null : TesteRede.ipDeInteiro(d.gateway);
    }

    private boolean conectado() {
        WifiInfo info = wm.getConnectionInfo();
        return info != null && info.getNetworkId() != -1 && info.getBSSID() != null;
    }

    // ---------- visões ----------

    private void mostrarVisaoDeScan(String visao) {
        List<Analise.RedeWifi> redes = lerRedes();
        limpar();
        if (redes.isEmpty()) {
            cartaoMensagem("Nenhuma rede encontrada",
                    "Verifique se a localização está ligada e se o Wi-Fi está ativo.", AMARELO);
            mostrarStatus("Nenhuma rede encontrada.");
            return;
        }
        switch (visao) {
            case "redes":
                mostrarRedes(redes);
                break;
            case "canais":
                mostrarCanais(redes);
                break;
            case "seguranca":
                mostrarSeguranca(redes);
                break;
            default:
                break;
        }
        mostrarStatus(redes.size() + " rede(s) encontrada(s).");
    }

    private void mostrarConexao() {
        limpar();
        if (!conectado()) {
            cartaoMensagem("Não conectado", "Conecte-se a uma rede Wi-Fi para ver os detalhes.", CINZA);
            mostrarStatus("Sem conexão Wi-Fi.");
            return;
        }
        WifiInfo info = wm.getConnectionInfo();
        String ssid = info.getSSID() == null ? "-" : info.getSSID().replace("\"", "");
        int freq = info.getFrequency();
        int rssi = info.getRssi();
        String banda = Analise.bandaDeFrequencia(freq);

        LinearLayout topo = cartao(AZUL);
        topo.addView(texto(ssid, 22, AZUL, true));
        topo.addView(texto(info.getBSSID(), 13, CINZA, false));

        secao("Detalhes");
        LinearLayout c = cartao(BORDA);
        linhaKV(c, "Canal", Analise.canalDeFrequencia(freq) + " · " + (banda == null ? "-" : banda), 0);
        linhaKV(c, "Frequência", freq + " MHz", 0);
        linhaKV(c, "Sinal", rssi + " dBm · " + Analise.classificarSinal(rssi)
                + " (" + Analise.qualidade(rssi) + "%)", corSinal(rssi));
        linhaKV(c, "Velocidade", info.getLinkSpeed() + " Mbps", 0);
        linhaKV(c, "IP do aparelho", ipLocal(), 0);
        linhaKV(c, "Gateway", gatewayAtual(), 0);
        mostrarStatus("Conexão atual.");
    }

    private void mostrarRedes(List<Analise.RedeWifi> redes) {
        List<Analise.RedeWifi> ordenadas = new ArrayList<>(redes);
        ordenadas.sort((a, b) -> Integer.compare(b.nivelDbm, a.nivelDbm));
        secao("Redes próximas (" + redes.size() + ")");
        for (Analise.RedeWifi r : ordenadas) {
            int cor = r.conectada ? AZUL : corSeguranca(r.seguranca);
            LinearLayout c = cartao(cor);

            LinearLayout linha = new LinearLayout(this);
            linha.setOrientation(LinearLayout.HORIZONTAL);
            linha.addView(texto(r.nome(), 17, TEXTO, true),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (r.conectada) {
                linha.addView(texto("CONECTADA", 11, AZUL, true));
            }
            c.addView(linha);

            c.addView(texto(String.format(Locale.ROOT, "Canal %d · %s · %d dBm · sinal %d%%",
                    r.canal, r.banda == null ? "-" : r.banda, r.nivelDbm, Analise.qualidade(r.nivelDbm)),
                    13, CINZA, false));
            c.addView(texto(r.seguranca + (r.wps ? " · WPS ativo" : "") + " · " + r.bssid,
                    13, corSeguranca(r.seguranca), false));
        }
    }

    private void mostrarCanais(List<Analise.RedeWifi> redes) {
        secao("Ocupação dos canais");
        for (java.util.Map.Entry<String, List<Analise.Canal>> e : Analise.ranking(redes).entrySet()) {
            List<Analise.Canal> lista = e.getValue();
            Analise.Canal melhor = lista.get(0);
            LinearLayout c = cartao(AZUL);
            c.addView(texto(e.getKey(), 17, TEXTO, true));
            c.addView(texto("Melhor canal: " + melhor.canal, 15, VERDE, true));
            int n = 0;
            for (Analise.Canal canal : lista) {
                if (n++ >= 5) break;
                boolean livre = Double.isNaN(canal.interferenciaDbm());
                String valor = livre ? "livre"
                        : canal.redes + " rede(s) · " + String.format(Locale.ROOT, "%.0f dBm", canal.interferenciaDbm());
                int cor;
                if (livre || canal.canal == melhor.canal) cor = VERDE;
                else if (canal.redes >= 3) cor = VERMELHO;
                else cor = AMARELO;
                linhaKV(c, "Canal " + canal.canal, valor, cor);
            }
        }
    }

    private void mostrarSeguranca(List<Analise.RedeWifi> redes) {
        List<Analise.Achado> achados = Analise.auditar(redes);
        secao("Segurança");
        if (achados.isEmpty()) {
            cartaoMensagem("Nenhum problema encontrado", "As redes próximas estão bem configuradas.", VERDE);
            return;
        }
        for (Analise.Achado a : achados) {
            int cor = corGravidade(a.gravidade);
            LinearLayout c = cartao(cor);
            c.addView(texto(a.gravidade.toUpperCase(Locale.ROOT) + " · " + a.ssid, 15, cor, true));
            c.addView(texto(a.mensagem, 13, TEXTO, false));
            c.addView(texto(a.bssid, 12, CINZA, false));
        }
    }

    private void mostrarDispositivos() {
        String ip = ipLocal();
        if (ip == null) {
            limpar();
            cartaoMensagem("Sem rede", "Conecte-se ao Wi-Fi para ver os aparelhos da rede.", CINZA);
            mostrarStatus("Conecte-se ao Wi-Fi para ver os aparelhos.");
            return;
        }
        String gateway = gatewayAtual();
        String prefixo = ip.substring(0, ip.lastIndexOf('.'));
        limpar();
        mostrarStatus("Procurando aparelhos em " + prefixo + ".0/24... pode levar cerca de 20 segundos.");
        new Thread(() -> {
            List<TesteRede.Dispositivo> lista = TesteRede.descobrirDispositivos(prefixo, 400);
            ui.post(() -> {
                limpar();
                secao("Aparelhos na rede (" + lista.size() + ")");
                for (TesteRede.Dispositivo d : lista) {
                    boolean eEste = d.ip.equals(ip);
                    boolean eRoteador = d.ip.equals(gateway);
                    int cor = eEste ? AZUL : eRoteador ? ROXO : VERDE;
                    LinearLayout c = cartao(cor);
                    String tags = (eEste ? "  · ESTE APARELHO" : "") + (eRoteador ? "  · ROTEADOR" : "");
                    c.addView(texto(d.ip + tags, 16, TEXTO, true));
                    c.addView(texto(d.nome == null ? "Nome não identificado" : d.nome, 13, CINZA, false));
                    c.addView(texto("MAC: " + (d.mac == null ? "não disponível" : d.mac), 13, CINZA, false));
                    String portas = d.portas.isEmpty() ? "nenhuma aberta" : String.join(", ", d.portas);
                    c.addView(texto("Portas: " + portas
                            + String.format(Locale.ROOT, "  ·  %.0f ms", d.latenciaMs), 13, CINZA, false));
                }
                mostrarStatus(lista.size() + " aparelho(s) encontrado(s).");
            });
        }).start();
    }

    // ---------- testes em segundo plano ----------

    private void testarLatencia() {
        String gateway = gatewayAtual();
        if (gateway == null) {
            limpar();
            cartaoMensagem("Gateway não encontrado", "Conecte-se ao Wi-Fi para medir o roteador.", AMARELO);
            mostrarStatus("Gateway não encontrado.");
            return;
        }
        limpar();
        mostrarStatus("Medindo latência até " + gateway + "...");
        new Thread(() -> {
            try {
                TesteRede.Estatisticas s = TesteRede.medirLatencia(gateway, 10, 80, 200);
                ui.post(() -> {
                    limpar();
                    secao("Latência até o roteador");
                    LinearLayout c = cartao(AMARELO);
                    linhaKV(c, "Host", gateway + ":80", 0);
                    linhaKV(c, "Mínimo", fmt(s.minimo), 0);
                    linhaKV(c, "Média", fmt(s.media), s.media > 30 ? AMARELO : VERDE);
                    linhaKV(c, "Máximo", fmt(s.maximo), 0);
                    linhaKV(c, "Jitter", fmt(s.jitter), s.jitter > 15 ? AMARELO : VERDE);
                    linhaKV(c, "Perda", String.format(Locale.ROOT, "%.0f%%", s.perda), s.perda > 0 ? VERMELHO : VERDE);
                    mostrarStatus("Teste de latência concluído.");
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void testarDns() {
        limpar();
        mostrarStatus("Testando DNS...");
        new Thread(() -> {
            List<TesteRede.Dns> resultadosDns = new ArrayList<>();
            for (String dominio : DOMINIOS) resultadosDns.add(TesteRede.tempoDns(dominio));
            ui.post(() -> {
                limpar();
                secao("Resolução de nomes (DNS)");
                LinearLayout c = cartao(ROXO);
                for (TesteRede.Dns d : resultadosDns) {
                    boolean falhou = Double.isNaN(d.ms);
                    linhaKV(c, d.dominio, falhou ? "FALHOU" : fmt(d.ms) + " ms", falhou ? VERMELHO : VERDE);
                }
                mostrarStatus("Teste de DNS concluído.");
            });
        }).start();
    }

    private void diagnostico() {
        limpar();
        mostrarStatus("Executando diagnóstico...");
        new Thread(() -> {
            boolean conectadoAgora = conectado();
            WifiInfo info = wm.getConnectionInfo();
            int rssi = conectadoAgora ? info.getRssi() : 0;
            int freq = conectadoAgora ? info.getFrequency() : 0;
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

            List<String[]> dicas = Analise.diagnosticar(conectadoAgora, rssi, freq, redes, lat, dns);
            ui.post(() -> {
                limpar();
                secao("Diagnóstico");
                for (String[] d : dicas) {
                    int cor = corGravidade(d[0]);
                    LinearLayout c = cartao(cor);
                    c.addView(texto(d[0].toUpperCase(Locale.ROOT), 12, cor, true));
                    c.addView(texto(d[1], 15, TEXTO, false));
                }
                mostrarStatus("Diagnóstico concluído.");
            });
        }).start();
    }

    // ---------- blocos visuais ----------

    private void limpar() {
        resultados.removeAllViews();
    }

    private void mostrarStatus(String texto) {
        status.setText(texto);
    }

    private void secao(String titulo) {
        TextView t = texto(titulo.toUpperCase(Locale.ROOT), 12, CINZA, true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(4), dp(14), dp(4), dp(6));
        resultados.addView(t, p);
    }

    /** Cria um cartão com borda colorida e o adiciona à lista de resultados. */
    private LinearLayout cartao(int cor) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(forma(CARTAO, dp(14), cor, 2));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(2), 0, dp(2), dp(10));
        resultados.addView(c, p);
        return c;
    }

    private void cartaoMensagem(String titulo, String mensagem, int cor) {
        LinearLayout c = cartao(cor);
        c.addView(texto(titulo, 17, cor, true));
        c.addView(texto(mensagem, 14, CINZA, false));
    }

    /** Linha "chave ..... valor". Cor 0 usa o texto padrão. */
    private void linhaKV(LinearLayout cartaoDestino, String chave, String valor, int cor) {
        LinearLayout linha = new LinearLayout(this);
        linha.setOrientation(LinearLayout.HORIZONTAL);
        linha.setPadding(0, dp(4), 0, dp(4));
        TextView k = texto(chave, 14, CINZA, false);
        TextView v = texto(valor == null ? "-" : valor, 14, cor == 0 ? TEXTO : cor, true);
        v.setGravity(Gravity.END);
        linha.addView(k, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        linha.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
        cartaoDestino.addView(linha);
    }

    private TextView texto(String s, int sp, int cor, boolean negrito) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(cor);
        if (negrito) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable forma(int fundo, int raio, int borda, int espessuraDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fundo);
        g.setCornerRadius(raio);
        g.setStroke(dp(espessuraDp), borda);
        return g;
    }

    private LinearLayout.LayoutParams lp(int largura, int altura, int peso, int margemInferior) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(largura, altura);
        p.setMargins(0, 0, 0, margemInferior);
        return p;
    }

    private int dp(int valor) {
        return Math.round(valor * densidade);
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.1f ms", v);
    }

    private static int corSinal(int dbm) {
        if (dbm >= -60) return VERDE;
        if (dbm >= -70) return AMARELO;
        return VERMELHO;
    }

    private static int corSeguranca(String seg) {
        switch (seg) {
            case "WPA3":
            case "WPA2":
                return VERDE;
            case "WPA2-Enterprise":
                return AZUL;
            case "Aberta":
            case "WEP":
                return VERMELHO;
            case "WPA":
                return LARANJA;
            default:
                return AMARELO;
        }
    }

    private static int corGravidade(String g) {
        switch (g) {
            case "crítica":
                return VERMELHO;
            case "alta":
                return LARANJA;
            case "média":
                return AMARELO;
            case "baixa":
                return AZUL;
            default:
                return CINZA;
        }
    }
}
