package com.proyecto.servidor;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * Componentes visuales propios del Servidor Central: degradados, tarjetas
 * redondeadas, botones con efecto hover y barras de desplazamiento finas.
 * Paleta principal: negro, azul y blanco.
 */
public final class EstilosUI {

    public static final Color NEGRO = new Color(8, 12, 24);
    public static final Color MARINO = new Color(15, 23, 42);
    public static final Color AZUL_PROFUNDO = new Color(23, 37, 84);
    public static final Color AZUL_OSCURO = new Color(30, 64, 175);
    public static final Color AZUL = new Color(37, 99, 235);
    public static final Color AZUL_VIVO = new Color(59, 130, 246);
    public static final Color AZUL_CIELO = new Color(2, 132, 199);
    public static final Color AZUL_CLARO = new Color(191, 219, 254);

    private EstilosUI() { }

    /** Se llama una vez antes de crear la ventana. */
    public static void instalarTema() {
        UIManager.put("ScrollBarUI", ScrollBarModerno.class.getName());
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollPane.background", new javax.swing.plaf.ColorUIResource(238, 243, 250));
    }

    static Graphics2D suave(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g2;
    }

    /** Panel con fondo en degradado (vertical u horizontal) y esquinas opcionalmente redondeadas. */
    public static class PanelDegradado extends JPanel {
        private final Color[] colores;
        private final float[] posiciones;
        private final boolean vertical;
        private final int arco;

        public PanelDegradado(LayoutManager lm, boolean vertical, int arco, Color... colores) {
            super(lm);
            this.vertical = vertical;
            this.arco = arco;
            this.colores = colores;
            this.posiciones = new float[colores.length];
            for (int i = 0; i < colores.length; i++) {
                posiciones[i] = colores.length == 1 ? 0f : (float) i / (colores.length - 1);
            }
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = suave(g);
            int w = getWidth(), h = getHeight();
            Color[] cs = colores.length == 1 ? new Color[]{colores[0], colores[0]} : colores;
            float[] ps = colores.length == 1 ? new float[]{0f, 1f} : posiciones;
            g2.setPaint(new LinearGradientPaint(0, 0, vertical ? 0 : w, vertical ? h : 0, ps, cs));
            if (arco > 0) g2.fill(new RoundRectangle2D.Float(0, 0, w, h, arco, arco));
            else g2.fillRect(0, 0, w, h);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Tarjeta con esquinas redondeadas, borde fino y sombra suave. */
    public static class Tarjeta extends JPanel {
        private final int arco;
        private final boolean sombra;
        private Color colorBorde = new Color(226, 232, 240);

        public Tarjeta(LayoutManager lm, int arco, boolean sombra) {
            super(lm);
            this.arco = arco;
            this.sombra = sombra;
            setOpaque(false);
        }

        public Tarjeta(LayoutManager lm) {
            this(lm, 16, true);
        }

        public void setColorBorde(Color c) {
            this.colorBorde = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = suave(g);
            int w = getWidth() - 1, h = getHeight() - 1;
            if (sombra) {
                for (int i = 3; i >= 1; i--) {
                    g2.setColor(new Color(15, 23, 42, 8));
                    g2.fill(new RoundRectangle2D.Float(0, i, w, h - 2 + i, arco, arco));
                }
            }
            int alto = sombra ? h - 3 : h;
            g2.setColor(getBackground());
            g2.fill(new RoundRectangle2D.Float(0, 0, w, alto, arco, arco));
            if (colorBorde != null) {
                g2.setColor(colorBorde);
                g2.draw(new RoundRectangle2D.Float(0, 0, w, alto, arco, arco));
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Botón de acción redondeado: se oscurece al pasar el ratón y se atenúa si está deshabilitado. */
    public static class BotonModerno extends JButton {
        private boolean encima;
        private boolean presionado;

        public BotonModerno(String texto, Color color) {
            super(texto);
            setBackground(color);
            setForeground(Color.WHITE);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(new Cursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { encima = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { encima = false; presionado = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { presionado = true; repaint(); }
                @Override public void mouseReleased(MouseEvent e) { presionado = false; repaint(); }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = suave(g);
            Color base = getBackground();
            if (!isEnabled()) {
                base = new Color(148, 163, 184);
            } else if (presionado) {
                base = base.darker().darker();
            } else if (encima) {
                base = base.darker();
            }
            g2.setColor(base);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 12, 12));
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Botón del menú lateral: "píldora" azul cuando está seleccionado y resaltado al pasar el ratón. */
    public static class BotonMenu extends JButton {
        private boolean seleccionado;
        private boolean encima;

        public BotonMenu(String texto) {
            super(texto);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(new Cursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { encima = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { encima = false; repaint(); }
            });
        }

        public void setSeleccionado(boolean s) {
            this.seleccionado = s;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = suave(g);
            int x = 10, y = 3, w = getWidth() - 20, h = getHeight() - 6;
            if (seleccionado) {
                g2.setPaint(new GradientPaint(x, 0, AZUL_VIVO, x + w, 0, AZUL_OSCURO));
                g2.fill(new RoundRectangle2D.Float(x, y, w, h, 14, 14));
                g2.setColor(Color.WHITE);
                g2.fill(new RoundRectangle2D.Float(x + 6, y + 10, 3, h - 20, 3, 3));
            } else if (encima) {
                g2.setColor(new Color(255, 255, 255, 22));
                g2.fill(new RoundRectangle2D.Float(x, y, w, h, 14, 14));
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Logo: cuadro azul con un pequeño grafo de red (un nodo central y tres satélites). */
    public static class LogoRed extends JComponent {
        public LogoRed() {
            setPreferredSize(new Dimension(38, 38));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = suave(g);
            int s = Math.min(getWidth(), getHeight());
            g2.setPaint(new GradientPaint(0, 0, AZUL_VIVO, s, s, AZUL_OSCURO));
            g2.fill(new RoundRectangle2D.Float(0, 0, s, s, 12, 12));
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = s / 2, cy = s / 2;
            int[][] sat = {{s / 4, s / 4}, {3 * s / 4, s / 4}, {s / 2, 3 * s / 4 + 1}};
            for (int[] p : sat) {
                g2.drawLine(cx, cy, p[0], p[1]);
                g2.fillOval(p[0] - 3, p[1] - 3, 6, 6);
            }
            g2.fillOval(cx - 5, cy - 5, 10, 10);
            g2.dispose();
        }
    }

    /** Barra de desplazamiento fina con pulgar redondeado azul. */
    public static class ScrollBarModerno extends BasicScrollBarUI {
        public static ComponentUI createUI(JComponent c) {
            return new ScrollBarModerno();
        }

        @Override protected JButton createDecreaseButton(int o) { return botonVacio(); }
        @Override protected JButton createIncreaseButton(int o) { return botonVacio(); }

        private JButton botonVacio() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            Graphics2D g2 = suave(g);
            g2.setColor(new Color(241, 245, 249));
            g2.fillRect(r.x, r.y, r.width, r.height);
            g2.dispose();
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty() || !scrollbar.isEnabled()) return;
            Graphics2D g2 = suave(g);
            g2.setColor(isThumbRollover() ? AZUL : new Color(96, 165, 250));
            g2.fill(new RoundRectangle2D.Float(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 8, 8));
            g2.dispose();
        }
    }
}
