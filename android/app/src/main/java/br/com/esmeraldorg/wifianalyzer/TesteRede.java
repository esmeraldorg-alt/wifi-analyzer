package br.com.esmeraldorg.wifianalyzer;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Testes de rede sem dependência do Android: latência, jitter, perda, DNS e dispositivos da rede local. */
public final class TesteRede {
    private TesteRede() {}

    /** Portas usadas só para saber se o host existe (qualquer resposta conta). */
    private static final int[] PORTAS_DESCOBERTA = {80, 443, 22, 445, 53, 8080, 62078};
    /** Portas verificadas em cada dispositivo encontrado. */
    private static final int[] PORTAS_DISPOSITIVO = {22, 53, 80, 139, 443, 445, 8080, 9100, 62078};
    private static final int THREADS = 64;

    public static final class Estatisticas {
        public final int amostras;
        public final double minimo;
        public final double media;
        public final double maximo;
        public final double jitter;
        public final double perda;

        Estatisticas(int amostras, double minimo, double media, double maximo, double jitter, double perda) {
            this.amostras = amostras;
            this.minimo = minimo;
            this.media = media;
            this.maximo = maximo;
            this.jitter = jitter;
            this.perda = perda;
        }
    }

    public static final class Dns {
        public final String dominio;
        public final double ms;

        Dns(String dominio, double ms) {
            this.dominio = dominio;
            this.ms = ms;
        }
    }

    /** Um aparelho encontrado na rede local. */
    public static final class Dispositivo {
        public final String ip;
        public final String nome;
        public final double latenciaMs;
        public final List<String> portas;
        public String mac;

        Dispositivo(String ip, String nome, double latenciaMs, List<String> portas) {
            this.ip = ip;
            this.nome = nome;
            this.latenciaMs = latenciaMs;
            this.portas = portas;
        }
    }

    /** Converte o IP do DhcpInfo (ordem invertida, little-endian) em texto. */
    public static String ipDeInteiro(int ip) {
        return String.format(Locale.ROOT, "%d.%d.%d.%d",
                ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >>> 24) & 0xff);
    }

    /** 1 = aberta, 0 = recusada (o host existe), -1 = sem resposta. */
    public static int estadoPorta(String ip, int porta, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, porta), timeoutMs);
            return 1;
        } catch (IOException e) {
            String msg = e.getMessage();
            return msg != null && msg.toLowerCase(Locale.ROOT).contains("refused") ? 0 : -1;
        }
    }

    /**
     * Tempo de ida e volta por TCP, em ms. Conexão recusada também conta como resposta.
     * Retorna NaN se não houver resposta.
     */
    public static double rtt(String host, int porta, int timeoutMs) {
        long inicio = System.nanoTime();
        int estado = estadoPorta(host, porta, timeoutMs);
        if (estado == -1) return Double.NaN;
        return (System.nanoTime() - inicio) / 1_000_000.0;
    }

    /** Estatísticas de uma série de amostras. NaN significa resposta perdida. */
    public static Estatisticas estatisticas(double[] amostras) {
        List<Double> validas = new ArrayList<>();
        for (double a : amostras) {
            if (!Double.isNaN(a)) validas.add(a);
        }
        int total = amostras.length;
        double perda = total == 0 ? 100.0 : (total - validas.size()) * 100.0 / total;
        if (validas.isEmpty()) {
            return new Estatisticas(total, Double.NaN, Double.NaN, Double.NaN, Double.NaN, perda);
        }
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double soma = 0;
        for (double v : validas) {
            min = Math.min(min, v);
            max = Math.max(max, v);
            soma += v;
        }
        double somaVariacoes = 0;
        int numVariacoes = 0;
        for (int i = 1; i < validas.size(); i++) {
            somaVariacoes += Math.abs(validas.get(i) - validas.get(i - 1));
            numVariacoes++;
        }
        double jitter = numVariacoes > 0 ? somaVariacoes / numVariacoes : 0.0;
        return new Estatisticas(total, min, soma / validas.size(), max, jitter, perda);
    }

    /** Faz várias medições seguidas até o host, com pausa entre elas. */
    public static Estatisticas medirLatencia(String host, int amostras, int porta, long intervaloMs)
            throws InterruptedException {
        double[] valores = new double[amostras];
        for (int i = 0; i < amostras; i++) {
            valores[i] = rtt(host, porta, 1000);
            if (i < amostras - 1 && intervaloMs > 0) Thread.sleep(intervaloMs);
        }
        return estatisticas(valores);
    }

    /** Tempo de resolução de nome em ms, ou NaN se falhar. */
    public static Dns tempoDns(String dominio) {
        long inicio = System.nanoTime();
        try {
            InetAddress.getByName(dominio);
            return new Dns(dominio, (System.nanoTime() - inicio) / 1_000_000.0);
        } catch (IOException e) {
            return new Dns(dominio, Double.NaN);
        }
    }

    public static String nomePorta(int porta) {
        switch (porta) {
            case 22: return "ssh";
            case 53: return "dns";
            case 80: return "http";
            case 139: return "netbios";
            case 443: return "https";
            case 445: return "smb";
            case 8080: return "http-alt";
            case 9100: return "impressora";
            case 62078: return "iphone-sync";
            default: return String.valueOf(porta);
        }
    }

    /** Interpreta o conteúdo de /proc/net/arp: IP -> MAC (ignora entradas incompletas). */
    public static Map<String, String> analisarArp(String conteudo) {
        Map<String, String> mapa = new HashMap<>();
        String[] linhas = conteudo.split("\n");
        for (int i = 1; i < linhas.length; i++) {
            String[] p = linhas[i].trim().split("\\s+");
            if (p.length >= 4 && !p[3].equals("00:00:00:00:00:00")) {
                mapa.put(p[0], p[3].toLowerCase(Locale.ROOT));
            }
        }
        return mapa;
    }

    /** Lê a tabela ARP do aparelho. Em versões novas do Android pode vir vazia. */
    public static Map<String, String> lerArp() {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String linha;
            while ((linha = r.readLine()) != null) sb.append(linha).append('\n');
        } catch (IOException ignorado) {
            return new HashMap<>();
        }
        return analisarArp(sb.toString());
    }

    /**
     * Procura aparelhos nos 254 endereços do prefixo (ex.: "192.168.1").
     * Pode levar alguns segundos; chamar fora da thread principal.
     */
    public static List<Dispositivo> descobrirDispositivos(String prefixo, int timeoutMs) {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<Dispositivo>> futuros = new ArrayList<>();
        for (int i = 1; i <= 254; i++) {
            String ip = prefixo + "." + i;
            futuros.add(pool.submit(() -> inspecionar(ip, timeoutMs)));
        }

        List<Dispositivo> achados = new ArrayList<>();
        for (Future<Dispositivo> f : futuros) {
            try {
                Dispositivo d = f.get();
                if (d != null) achados.add(d);
            } catch (Exception ignorado) {
                // um endereço com erro não interrompe a varredura
            }
        }
        pool.shutdown();

        Map<String, String> arp = lerArp();
        for (Dispositivo d : achados) {
            d.mac = arp.get(d.ip);
        }
        achados.sort((a, b) -> Integer.compare(ultimoOcteto(a.ip), ultimoOcteto(b.ip)));
        return achados;
    }

    static Dispositivo inspecionar(String ip, int timeoutMs) {
        long inicio = System.nanoTime();
        boolean vivo = false;
        for (int porta : PORTAS_DESCOBERTA) {
            if (estadoPorta(ip, porta, timeoutMs) != -1) {
                vivo = true;
                break;
            }
        }
        if (!vivo) return null;
        double latencia = (System.nanoTime() - inicio) / 1_000_000.0;

        List<String> abertas = new ArrayList<>();
        for (int porta : PORTAS_DISPOSITIVO) {
            if (estadoPorta(ip, porta, timeoutMs) == 1) abertas.add(porta + "/" + nomePorta(porta));
        }
        return new Dispositivo(ip, nomeDoHost(ip), latencia, abertas);
    }

    static String nomeDoHost(String ip) {
        try {
            String nome = InetAddress.getByName(ip).getCanonicalHostName();
            return nome.equals(ip) ? null : nome;
        } catch (IOException e) {
            return null;
        }
    }

    private static int ultimoOcteto(String ip) {
        return Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1));
    }
}
