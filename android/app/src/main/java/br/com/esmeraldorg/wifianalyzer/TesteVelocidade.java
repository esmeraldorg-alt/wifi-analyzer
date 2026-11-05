package br.com.esmeraldorg.wifianalyzer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Teste de velocidade sem dependência do Android: download e upload por HTTP, em Mbps. */
public final class TesteVelocidade {
    private TesteVelocidade() {}

    /** Servidor público de teste usado pelo app. */
    public static final String HOST = "speed.cloudflare.com";
    public static final String URL_DOWNLOAD = "https://speed.cloudflare.com/__down?bytes=";
    public static final String URL_UPLOAD = "https://speed.cloudflare.com/__up";
    public static final int BYTES_DOWNLOAD = 10_000_000;
    public static final int BYTES_UPLOAD = 5_000_000;

    /** Velocidade de download da URL, em Mbps. NaN se falhar. */
    public static double download(String url, int timeoutMs) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(timeoutMs);
            con.setReadTimeout(timeoutMs);
            con.setUseCaches(false);
            long total = 0;
            long inicio = System.nanoTime();
            try (InputStream in = con.getInputStream()) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) total += n;
            }
            double segundos = (System.nanoTime() - inicio) / 1e9;
            if (total == 0 || segundos <= 0) return Double.NaN;
            return total * 8 / segundos / 1e6;
        } catch (IOException e) {
            return Double.NaN;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** Velocidade de upload de `bytes` bytes para a URL, em Mbps. NaN se falhar. */
    public static double upload(String url, int bytes, int timeoutMs) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(timeoutMs);
            con.setReadTimeout(timeoutMs);
            con.setDoOutput(true);
            con.setRequestMethod("POST");
            con.setUseCaches(false);
            con.setChunkedStreamingMode(65536);
            con.setRequestProperty("Content-Type", "application/octet-stream");
            byte[] buf = new byte[65536];
            long inicio = System.nanoTime();
            try (OutputStream out = con.getOutputStream()) {
                int restante = bytes;
                while (restante > 0) {
                    int n = Math.min(buf.length, restante);
                    out.write(buf, 0, n);
                    restante -= n;
                }
            }
            int codigo = con.getResponseCode();
            double segundos = (System.nanoTime() - inicio) / 1e9;
            if (codigo < 200 || codigo >= 300 || segundos <= 0) return Double.NaN;
            return bytes * 8 / segundos / 1e6;
        } catch (IOException e) {
            return Double.NaN;
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
