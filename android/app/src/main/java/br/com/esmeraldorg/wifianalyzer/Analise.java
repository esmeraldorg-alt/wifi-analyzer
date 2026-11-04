package br.com.esmeraldorg.wifianalyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Análises sem dependência do Android. Mesma lógica do analisador Python (wifi_analyzer). */
public final class Analise {
    private Analise() {}

    public static final int SINAL_DESCONHECIDO = -90;
    private static final int[] CANAIS_5GHZ = {36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120,
            124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165};
    private static final String[] GRAVIDADES = {"crítica", "alta", "média", "baixa", "info"};

    /** Uma rede vizinha (ou a conectada), já interpretada. */
    public static final class RedeWifi {
        public final String ssid;
        public final String bssid;
        public final int freq;
        public final int canal;
        public final String banda;
        public final int nivelDbm;
        public final String seguranca;
        public final boolean wps;
        public final boolean tkip;
        public final boolean conectada;

        public RedeWifi(String ssid, String bssid, int freq, int nivelDbm, String capabilities, boolean conectada) {
            this.ssid = ssid == null ? "" : ssid;
            this.bssid = bssid == null ? "-" : bssid;
            this.freq = freq;
            this.nivelDbm = nivelDbm;
            this.canal = canalDeFrequencia(freq);
            this.banda = bandaDeFrequencia(freq);
            String c = capabilities == null ? "" : capabilities.toUpperCase(Locale.ROOT);
            this.seguranca = classificarSeguranca(c);
            this.wps = c.contains("WPS");
            this.tkip = c.contains("TKIP");
            this.conectada = conectada;
        }

        public String nome() {
            return ssid.isEmpty() ? "(oculta)" : ssid;
        }
    }

    /** Ocupação de um canal: quanto mais alto o score, mais congestionado. */
    public static final class Canal {
        public final int canal;
        public final int redes;
        public final double score;

        Canal(int canal, int redes, double score) {
            this.canal = canal;
            this.redes = redes;
            this.score = score;
        }

        /** Interferência em dBm, ou NaN se o canal está livre. */
        public double interferenciaDbm() {
            return score > 0 ? 10 * Math.log10(score) : Double.NaN;
        }
    }

    /** Achado da auditoria de segurança. */
    public static final class Achado {
        public final String gravidade;
        public final String ssid;
        public final String bssid;
        public final String mensagem;

        Achado(String gravidade, String ssid, String bssid, String mensagem) {
            this.gravidade = gravidade;
            this.ssid = ssid;
            this.bssid = bssid;
            this.mensagem = mensagem;
        }
    }

    // ---------- rádio ----------

    public static int canalDeFrequencia(int f) {
        if (f == 2484) return 14;
        if (f >= 2412 && f <= 2472 && (f - 2407) % 5 == 0) return (f - 2407) / 5;
        if (f > 5000 && f < 5900 && (f - 5000) % 5 == 0) return (f - 5000) / 5;
        if (f >= 5955 && f <= 7115 && (f - 5950) % 5 == 0) return (f - 5950) / 5;
        return 0;
    }

    /** Retorna null se a frequência não pertence a 2.4, 5 ou 6 GHz. */
    public static String bandaDeFrequencia(int f) {
        if (f >= 2400 && f < 2500) return "2.4 GHz";
        if (f >= 4900 && f < 5900) return "5 GHz";
        if (f >= 5925 && f <= 7125) return "6 GHz";
        return null;
    }

    /** Interpreta as "capabilities" do Android (ex.: [WPA2-PSK-CCMP][WPS][ESS]). */
    public static String classificarSeguranca(String capsMaiusculas) {
        String c = capsMaiusculas;
        boolean wpa3 = c.contains("SAE") || c.contains("WPA3");
        boolean wpa2 = c.contains("WPA2") || c.contains("RSN");
        boolean psk = c.contains("PSK");
        boolean eap = c.contains("EAP");
        boolean tkip = c.contains("TKIP");
        if (wpa2 && eap) return "WPA2-Enterprise";
        if (wpa3 && (wpa2 || psk)) return "WPA2/WPA3 (transição)";
        if (wpa3) return "WPA3";
        if (wpa2) return tkip ? "WPA2 (TKIP)" : "WPA2";
        if (c.contains("WPA")) return "WPA";
        if (c.contains("WEP")) return "WEP";
        return "Aberta";
    }

    public static int qualidade(int dbm) {
        return Math.max(0, Math.min(100, 2 * (dbm + 100)));
    }

    public static String classificarSinal(int dbm) {
        if (dbm >= -50) return "excelente";
        if (dbm >= -60) return "bom";
        if (dbm >= -70) return "razoável";
        if (dbm >= -80) return "fraco";
        return "muito fraco";
    }

    // ---------- canais ----------

    static double fatorSobreposicao(int a, int b, String banda) {
        if (a == 0 || b == 0) return 0;
        int d = Math.abs(a - b);
        if ("2.4 GHz".equals(banda)) return Math.max(0.0, 1 - d / 5.0);
        return d == 0 ? 1.0 : 0.0;
    }

    /** Para cada banda, canais do menos ao mais congestionado (o primeiro é o melhor). */
    public static Map<String, List<Canal>> ranking(List<RedeWifi> redes) {
        Map<String, List<Canal>> resultado = new TreeMap<>();
        Set<String> bandas = new TreeSet<>();
        for (RedeWifi r : redes) {
            if (r.banda != null) bandas.add(r.banda);
        }
        for (String banda : bandas) {
            Set<Integer> candidatos = new TreeSet<>();
            Set<Integer> presentes = new HashSet<>();
            for (RedeWifi r : redes) {
                if (banda.equals(r.banda) && r.canal > 0) presentes.add(r.canal);
            }
            if ("2.4 GHz".equals(banda)) {
                for (int c = 1; c <= 13; c++) candidatos.add(c);
            } else if ("5 GHz".equals(banda)) {
                candidatos.addAll(presentes);
                for (int c : CANAIS_5GHZ) candidatos.add(c);
            } else {
                candidatos.addAll(presentes);
                for (int c = 1; c <= 233; c += 4) candidatos.add(c);
            }

            List<Canal> linhas = new ArrayList<>();
            for (int c : candidatos) {
                double score = 0;
                int vizinhas = 0;
                for (RedeWifi r : redes) {
                    if (!banda.equals(r.banda)) continue;
                    double fator = fatorSobreposicao(c, r.canal, banda);
                    if (fator > 0) {
                        int nivel = r.nivelDbm;
                        score += fator * Math.pow(10, nivel / 10.0);
                        vizinhas++;
                    }
                }
                linhas.add(new Canal(c, vizinhas, score));
            }
            linhas.sort(Comparator.comparingDouble((Canal x) -> x.score).thenComparingInt(x -> x.canal));
            resultado.put(banda, linhas);
        }
        return resultado;
    }

    // ---------- segurança ----------

    private static void add(List<Achado> lista, String gravidade, RedeWifi r, String mensagem) {
        lista.add(new Achado(gravidade, r.nome(), r.bssid, mensagem));
    }

    public static List<Achado> auditar(List<RedeWifi> redes) {
        List<Achado> achados = new ArrayList<>();
        for (RedeWifi r : redes) {
            switch (r.seguranca) {
                case "Aberta":
                    add(achados, "crítica", r, "Rede aberta: o tráfego pode ser interceptado.");
                    break;
                case "WEP":
                    add(achados, "crítica", r, "WEP é quebrado em minutos: troque para WPA2 ou WPA3.");
                    break;
                case "WPA":
                    add(achados, "alta", r, "WPA (TKIP) está obsoleto: atualize para WPA2/WPA3 com AES.");
                    break;
                case "WPA2 (TKIP)":
                    add(achados, "média", r, "WPA2 aceitando TKIP: deixe somente AES (CCMP).");
                    break;
                case "WPA2":
                    add(achados, "baixa", r, "WPA2 é aceitável, mas WPA3 é mais seguro.");
                    break;
                case "WPA2/WPA3 (transição)":
                    add(achados, "média", r, "Modo de transição permite rebaixar a conexão para WPA2.");
                    break;
                case "WPA2-Enterprise":
                    add(achados, "info", r, "Rede corporativa (802.1X).");
                    break;
                default:
                    break;
            }
            if (r.wps) add(achados, "média", r, "WPS ativo: vulnerável a ataques de PIN. Desative.");
            if (r.ssid.isEmpty()) add(achados, "info", r, "SSID oculto: não elimina a rede, só esconde o nome.");
        }

        Set<String> ja = new HashSet<>();
        for (RedeWifi a : redes) {
            if (a.ssid.isEmpty() || ja.contains(a.ssid)) continue;
            for (RedeWifi b : redes) {
                if (a.ssid.equals(b.ssid) && !a.bssid.equals(b.bssid) && !a.seguranca.equals(b.seguranca)) {
                    ja.add(a.ssid);
                    achados.add(new Achado("alta", a.ssid, "-",
                            "Mesmo nome com segurança diferente: possível rede falsa (evil twin) "
                                    + "ou roteador mal configurado."));
                    break;
                }
            }
        }

        achados.sort(Comparator.comparingInt((Achado x) -> indiceGravidade(x.gravidade)).thenComparing(x -> x.ssid));
        return achados;
    }

    static int indiceGravidade(String g) {
        for (int i = 0; i < GRAVIDADES.length; i++) {
            if (GRAVIDADES[i].equals(g)) return i;
        }
        return GRAVIDADES.length;
    }

    // ---------- diagnóstico ----------

    /** Retorna pares [gravidade, mensagem], do mais grave para o menos grave. */
    public static List<String[]> diagnosticar(boolean conectado, int sinalDbm, int freq, List<RedeWifi> redes,
                                              TesteRede.Estatisticas latencia, List<TesteRede.Dns> dns) {
        List<String[]> dicas = new ArrayList<>();
        if (!conectado) {
            dicas.add(new String[]{"alta", "Não há conexão Wi-Fi ativa."});
        } else {
            if (sinalDbm < -70) {
                dicas.add(new String[]{"média", "Sinal fraco (" + sinalDbm + " dBm): aproxime-se do roteador "
                        + "ou use um repetidor."});
            }
            if (freq > 0 && redes != null && !redes.isEmpty()) {
                String banda = bandaDeFrequencia(freq);
                int canalAtual = canalDeFrequencia(freq);
                List<Canal> ranking = banda == null ? null : ranking(redes).get(banda);
                if (ranking != null && !ranking.isEmpty() && canalAtual > 0) {
                    Canal melhor = ranking.get(0);
                    Canal atual = null;
                    for (Canal c : ranking) {
                        if (c.canal == canalAtual) atual = c;
                    }
                    if (atual != null && melhor.canal != canalAtual && melhor.score > 0
                            && atual.score >= 2 * melhor.score) {
                        dicas.add(new String[]{"média", "Canal " + canalAtual + " (" + banda + ") está "
                                + "congestionado. Considere o canal " + melhor.canal + ", menos ocupado."});
                    }
                }
            }
        }

        if (latencia != null && !Double.isNaN(latencia.media)) {
            if (latencia.media > 30) {
                dicas.add(new String[]{"média", String.format(Locale.ROOT,
                        "Latência alta até o roteador: %.1f ms.", latencia.media)});
            }
            if (latencia.jitter > 15) {
                dicas.add(new String[]{"média", String.format(Locale.ROOT,
                        "Jitter alto (%.1f ms): instabilidade na rede local.", latencia.jitter)});
            }
        }
        if (latencia != null && latencia.perda > 0) {
            String grav = latencia.perda >= 2 ? "alta" : "média";
            dicas.add(new String[]{grav, String.format(Locale.ROOT,
                    "Perda de pacotes até o roteador: %.0f%%.", latencia.perda)});
        }

        if (dns != null && !dns.isEmpty()) {
            StringBuilder falhas = new StringBuilder();
            double soma = 0;
            int validos = 0;
            for (TesteRede.Dns d : dns) {
                if (Double.isNaN(d.ms)) {
                    falhas.append(falhas.length() > 0 ? ", " : "").append(d.dominio);
                } else {
                    soma += d.ms;
                    validos++;
                }
            }
            if (falhas.length() > 0) dicas.add(new String[]{"alta", "Falha ao resolver: " + falhas + "."});
            if (validos > 0 && soma / validos > 150) {
                dicas.add(new String[]{"média", String.format(Locale.ROOT,
                        "DNS lento: média de %.0f ms.", soma / validos)});
            }
        }

        if (dicas.isEmpty()) dicas.add(new String[]{"info", "Nenhum problema encontrado."});
        dicas.sort(Comparator.comparingInt(d -> indiceGravidade(d[0])));
        return dicas;
    }
}
