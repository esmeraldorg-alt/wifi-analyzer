package br.com.esmeraldorg.wifianalyzer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Testes de rede sem dependência do Android: latência por TCP, jitter, perda e DNS. */
public final class TesteRede {
    private TesteRede() {}

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

    /** Converte o IP do DhcpInfo (ordem invertida, little-endian) em texto. */
    public static String ipDeInteiro(int ip) {
        return String.format(Locale.ROOT, "%d.%d.%d.%d",
                ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >>> 24) & 0xff);
    }

    /**
     * Tempo de ida e volta por TCP, em ms. Conexão recusada também conta como resposta
     * (o host existe). Retorna NaN se não houver resposta.
     */
    public static double rtt(String host, int porta, int timeoutMs) {
        long inicio = System.nanoTime();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, porta), timeoutMs);
        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg == null || !msg.toLowerCase(Locale.ROOT).contains("refused")) {
                return Double.NaN;
            }
        }
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
}
