package br.com.esmeraldorg.wifianalyzer;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Abertura animada: ponto de luz, arcos de Wi-Fi se desenhando, encaixe com overshoot,
 * brilho e título surgindo letra por letra. Depois abre a tela principal. Toque pula a animação.
 */
public class SplashActivity extends Activity {
    private static final long DURACAO_MS = 6000;

    private boolean abriu = false;
    private ValueAnimator animacao;

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        getWindow().setStatusBarColor(LogoView.FUNDO);

        LogoView logo = new LogoView(this);
        setContentView(logo);
        logo.setOnClickListener(v -> abrirPrincipal());

        animacao = ValueAnimator.ofFloat(0f, 1f);
        animacao.setDuration(DURACAO_MS);
        animacao.setInterpolator(new LinearInterpolator());
        animacao.addUpdateListener(a -> logo.definirProgresso((Float) a.getAnimatedValue()));
        animacao.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                abrirPrincipal();
            }
        });
        animacao.start();
    }

    @Override
    protected void onDestroy() {
        if (animacao != null) animacao.cancel();
        super.onDestroy();
    }

    private void abrirPrincipal() {
        if (abriu) return;
        abriu = true;
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    /** Desenha o logo de acordo com o progresso (0 a 1) da animação. */
    static final class LogoView extends View {
        static final int FUNDO = 0xFF0B1220;
        private static final int AZUL = 0xFF3BA3FF;
        private static final int CLARO = 0xFF7FC6FF;
        private static final int BRANCO = 0xFFE8F4FF;
        private static final int VERDE = 0xFF2ED47A;
        private static final int CINZA = 0xFF8FA3C2;
        private static final String TITULO = "Analisador Wi-Fi";
        private static final String SUBTITULO = "Veja o que está em volta e quem está na sua rede";

        private float progresso = 0f;
        private final Paint arco = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ponto = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint titulo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subtitulo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();

        LogoView(Context contexto) {
            super(contexto);
            arco.setStyle(Paint.Style.STROKE);
            arco.setStrokeCap(Paint.Cap.ROUND);
            ponto.setStyle(Paint.Style.FILL);
            titulo.setColor(BRANCO);
            titulo.setTextAlign(Paint.Align.CENTER);
            titulo.setTypeface(Typeface.DEFAULT_BOLD);
            subtitulo.setColor(CINZA);
            subtitulo.setTextAlign(Paint.Align.CENTER);
        }

        void definirProgresso(float valor) {
            progresso = valor;
            invalidate();
        }

        /** Vai de 0 a 1 entre dois instantes da animação. */
        private static float faixa(float valor, float inicio, float fim) {
            float t = (valor - inicio) / (fim - inicio);
            return Math.max(0f, Math.min(1f, t));
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(FUNDO);
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h * 0.40f;
            float raio = Math.min(w, h) * 0.20f;
            float espessura = raio * 0.18f;
            float p = progresso;

            // 1. ponto de luz que aparece no centro
            float luz = faixa(p, 0f, 0.15f);
            ponto.setColor(VERDE);
            ponto.setAlpha((int) (255 * luz));

            // 2. arcos se desenhando de dentro para fora, com encaixe (overshoot) no fim
            float encaixe = faixa(p, 0.40f, 0.60f);
            float escala = 1f + 0.12f * (float) (Math.sin(encaixe * Math.PI) * (1f - encaixe));
            float[] fracoes = {0.37f, 0.68f, 1.0f};
            int[] cores = {BRANCO, CLARO, AZUL};

            c.save();
            c.scale(escala, escala, cx, cy);
            for (int i = 0; i < 3; i++) {
                float t = faixa(p, 0.15f + i * 0.08f, 0.35f + i * 0.08f);
                if (t <= 0f) continue;
                float sweep = 90f * t;
                float r = raio * fracoes[i];
                arco.setStrokeWidth(espessura);
                arco.setColor(cores[i]);
                arco.setAlpha(255);
                oval.set(cx - r, cy - r, cx + r, cy + r);
                c.drawArc(oval, 270f - sweep / 2f, sweep, false, arco);
            }

            // 3. brilho branco que passa pelos arcos
            float brilho = faixa(p, 0.62f, 0.80f);
            if (brilho > 0f) {
                int alfa = (int) (200 * Math.sin(brilho * Math.PI));
                for (int i = 0; i < 3; i++) {
                    float r = raio * fracoes[i];
                    arco.setStrokeWidth(espessura);
                    arco.setColor(BRANCO);
                    arco.setAlpha(alfa);
                    oval.set(cx - r, cy - r, cx + r, cy + r);
                    c.drawArc(oval, 225f, 90f, false, arco);
                }
            }
            c.restore();

            c.drawCircle(cx, cy, raio * 0.16f * luz, ponto);

            // 4. título letra por letra e subtítulo que aparece no fim
            int letras = Math.round(TITULO.length() * faixa(p, 0.72f, 0.90f));
            titulo.setTextSize(raio * 0.30f);
            c.drawText(TITULO.substring(0, letras), cx, cy + raio * 1.45f, titulo);

            float sub = faixa(p, 0.90f, 1.0f);
            if (sub > 0f) {
                subtitulo.setTextSize(raio * 0.13f);
                subtitulo.setAlpha((int) (255 * sub));
                c.drawText(SUBTITULO, cx, cy + raio * 1.95f, subtitulo);
            }
        }
    }
}
