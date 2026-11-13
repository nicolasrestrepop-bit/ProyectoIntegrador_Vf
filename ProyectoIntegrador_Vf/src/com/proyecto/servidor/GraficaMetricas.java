package com.proyecto.servidor;

import com.proyecto.modelo.Metrica;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/**
 * Gráfica de líneas de CPU y RAM (0-100 %) de los últimos minutos de un nodo.
 *
 * Se dibuja con Java2D, sin librerías externas. El eje X es tiempo real: el
 * borde derecho es "ahora" y la gráfica se desplaza sola aunque no lleguen
 * lecturas nuevas (la ventana la repinta cada segundo). Las franjas de fondo
 * marcan las zonas de advertencia (ámbar) y crítica (rojo) de NivelMetrica.
 *
 * Si entre dos lecturas pasa mucho más tiempo que el intervalo habitual del
 * agente, la línea se corta en lugar de unir los puntos: así no se inventan
 * datos durante una pausa del monitoreo.
 */
public class GraficaMetricas extends JComponent {

    public static final Color COLOR_CPU = new Color(37, 99, 235);
    public static final Color COLOR_RAM = new Color(124, 58, 237);

    private static final Color COLOR_FONDO = Color.WHITE;
    private static final Color COLOR_REJILLA = new Color(226, 232, 240);
    private static final Color COLOR_TEXTO = new Color(100, 116, 139);
    private static final Color ZONA_ADVERTENCIA = new Color(217, 119, 6, 22);
    private static final Color ZONA_CRITICA = new Color(220, 38, 38, 26);

    private static final int MARGEN_IZQ = 46;
    private static final int MARGEN_DER = 18;
    private static final int MARGEN_SUP = 34;
    private static final int MARGEN_INF = 30;

    private final long ventanaMs;
    private List<Metrica> datos = Collections.emptyList();

    /** Reloj de la gráfica; se puede fijar en pruebas. */
    private long ahoraFijoMs = -1;

    public GraficaMetricas(int minutosVisibles) {
        this.ventanaMs = minutosVisibles * 60_000L;
        setPreferredSize(new Dimension(720, 320));
        setOpaque(true);
    }

    /** Reemplaza las lecturas que se dibujan (ordenadas de la más antigua a la más reciente). */
    public void setDatos(List<Metrica> lecturas) {
        this.datos = new ArrayList<>(lecturas);
        repaint();
    }

    void fijarReloj(long ahoraMs) {
        this.ahoraFijoMs = ahoraMs;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g2.setColor(COLOR_FONDO);
        g2.fillRect(0, 0, getWidth(), getHeight());

        int x0 = MARGEN_IZQ;
        int y0 = MARGEN_SUP;
        int ancho = getWidth() - MARGEN_IZQ - MARGEN_DER;
        int alto = getHeight() - MARGEN_SUP - MARGEN_INF;
        if (ancho < 60 || alto < 60) {
            g2.dispose();
            return;
        }

        long ahora = ahoraFijoMs >= 0 ? ahoraFijoMs : System.currentTimeMillis();
        long inicio = ahora - ventanaMs;

        dibujarZonas(g2, x0, y0, ancho, alto);
        dibujarRejillaY(g2, x0, y0, ancho, alto);
        dibujarEjeTiempo(g2, x0, y0, ancho, alto);
        dibujarLeyenda(g2, x0, ancho);

        if (datos.isEmpty()) {
            dibujarMensaje(g2, "Esperando lecturas del agente (llegan cada ~10 s)…", x0, y0, ancho, alto);
        } else {
            long hueco = calcularHueco();
            java.awt.Shape recorteAnterior = g2.getClip();
            g2.clipRect(x0, y0 - 4, ancho + 1, alto + 8);
            dibujarSerie(g2, Metrica::getRam, COLOR_RAM, inicio, hueco, x0, y0, ancho, alto);
            dibujarSerie(g2, Metrica::getCpu, COLOR_CPU, inicio, hueco, x0, y0, ancho, alto);
            g2.setClip(recorteAnterior);
        }

        g2.dispose();
    }

    // ------------------------------------------------------------------
    //  Fondo y ejes
    // ------------------------------------------------------------------

    private void dibujarZonas(Graphics2D g2, int x0, int y0, int ancho, int alto) {
        int yCritico = yDe(NivelMetrica.UMBRAL_CRITICO, y0, alto);
        int yAdvertencia = yDe(NivelMetrica.UMBRAL_ADVERTENCIA, y0, alto);
        g2.setColor(ZONA_CRITICA);
        g2.fillRect(x0, y0, ancho, yCritico - y0);
        g2.setColor(ZONA_ADVERTENCIA);
        g2.fillRect(x0, yCritico, ancho, yAdvertencia - yCritico);

        Stroke anterior = g2.getStroke();
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{5f, 4f}, 0f));
        g2.setColor(NivelMetrica.CRITICO.getColor());
        g2.drawLine(x0, yCritico, x0 + ancho, yCritico);
        g2.setColor(NivelMetrica.ADVERTENCIA.getColor());
        g2.drawLine(x0, yAdvertencia, x0 + ancho, yAdvertencia);
        g2.setStroke(anterior);
    }

    private void dibujarRejillaY(Graphics2D g2, int x0, int y0, int ancho, int alto) {
        g2.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        FontMetrics fm = g2.getFontMetrics();
        for (int pct = 0; pct <= 100; pct += 25) {
            int y = yDe(pct, y0, alto);
            g2.setColor(COLOR_REJILLA);
            g2.drawLine(x0, y, x0 + ancho, y);
            String etiqueta = pct + " %";
            g2.setColor(COLOR_TEXTO);
            g2.drawString(etiqueta, x0 - 8 - fm.stringWidth(etiqueta), y + fm.getAscent() / 2 - 1);
        }
    }

    private void dibujarEjeTiempo(Graphics2D g2, int x0, int y0, int ancho, int alto) {
        g2.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        FontMetrics fm = g2.getFontMetrics();
        int minutos = (int) (ventanaMs / 60_000L);
        int paso = minutos <= 5 ? 1 : 2;
        int yBase = y0 + alto;
        for (int m = minutos; m >= 0; m -= paso) {
            int x = x0 + (int) Math.round(ancho * (1.0 - (double) m / minutos));
            g2.setColor(COLOR_REJILLA);
            g2.drawLine(x, yBase, x, yBase + 4);
            String etiqueta = m == 0 ? "ahora" : "-" + m + " min";
            int xTexto = x - fm.stringWidth(etiqueta) / 2;
            xTexto = Math.max(x0 - 6, Math.min(xTexto, x0 + ancho - fm.stringWidth(etiqueta)));
            g2.setColor(COLOR_TEXTO);
            g2.drawString(etiqueta, xTexto, yBase + 6 + fm.getAscent());
        }
    }

    private void dibujarLeyenda(Graphics2D g2, int x0, int ancho) {
        Metrica ultima = datos.isEmpty() ? null : datos.get(datos.size() - 1);
        g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
        FontMetrics fm = g2.getFontMetrics();

        String cpu = "CPU " + (ultima == null ? "—" : String.format(Locale.ROOT, "%.1f %%", ultima.getCpu()));
        String ram = "RAM " + (ultima == null ? "—" : String.format(Locale.ROOT, "%.1f %%", ultima.getRam()));

        int y = 20;
        int x = x0 + ancho - fm.stringWidth(ram) - 22;
        dibujarItemLeyenda(g2, ram, COLOR_RAM, x, y);
        x -= fm.stringWidth(cpu) + 22 + 18;
        dibujarItemLeyenda(g2, cpu, COLOR_CPU, x, y);

        g2.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        g2.setColor(COLOR_TEXTO);
        g2.drawString("Ámbar ≥ " + (int) NivelMetrica.UMBRAL_ADVERTENCIA + " %   ·   Rojo ≥ "
                + (int) NivelMetrica.UMBRAL_CRITICO + " %", x0, y);
    }

    private void dibujarItemLeyenda(Graphics2D g2, String texto, Color color, int x, int y) {
        g2.setColor(color);
        g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawLine(x, y - 4, x + 14, y - 4);
        g2.setStroke(new BasicStroke(1f));
        g2.drawString(texto, x + 20, y);
    }

    private void dibujarMensaje(Graphics2D g2, String texto, int x0, int y0, int ancho, int alto) {
        g2.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(COLOR_TEXTO);
        g2.drawString(texto, x0 + (ancho - fm.stringWidth(texto)) / 2, y0 + alto / 2);
    }

    // ------------------------------------------------------------------
    //  Series
    // ------------------------------------------------------------------

    private void dibujarSerie(Graphics2D g2, ToDoubleFunction<Metrica> valor, Color color,
                              long inicio, long hueco, int x0, int y0, int ancho, int alto) {
        Path2D.Double linea = new Path2D.Double();
        boolean trazando = false;
        long tAnterior = Long.MIN_VALUE;
        double xUltimo = 0;
        double yUltimo = 0;

        for (Metrica m : datos) {
            long t = m.getInstanteMs();
            double x = x0 + ancho * (double) (t - inicio) / ventanaMs;
            double y = y0 + alto * (1.0 - valor.applyAsDouble(m) / 100.0);

            if (!trazando || t - tAnterior > hueco) {
                linea.moveTo(x, y);
            } else {
                linea.lineTo(x, y);
            }
            trazando = true;
            tAnterior = t;
            xUltimo = x;
            yUltimo = y;
        }

        g2.setColor(color);
        g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(linea);

        // Punto en la lectura más reciente, para que se note dónde termina la serie.
        g2.setStroke(new BasicStroke(2f));
        g2.setColor(Color.WHITE);
        g2.fill(new java.awt.geom.Ellipse2D.Double(xUltimo - 4.5, yUltimo - 4.5, 9, 9));
        g2.setColor(color);
        g2.draw(new java.awt.geom.Ellipse2D.Double(xUltimo - 4.5, yUltimo - 4.5, 9, 9));
    }

    /**
     * Tiempo a partir del cual dos lecturas seguidas ya no se unen: tres veces
     * la mediana de los intervalos observados (mínimo 30 s).
     */
    private long calcularHueco() {
        if (datos.size() < 3) {
            return 30_000L;
        }
        List<Long> deltas = new ArrayList<>();
        for (int i = 1; i < datos.size(); i++) {
            deltas.add(datos.get(i).getInstanteMs() - datos.get(i - 1).getInstanteMs());
        }
        Collections.sort(deltas);
        long mediana = deltas.get(deltas.size() / 2);
        return Math.max(30_000L, mediana * 3);
    }

    private static int yDe(double porcentaje, int y0, int alto) {
        return y0 + (int) Math.round(alto * (1.0 - porcentaje / 100.0));
    }
}
