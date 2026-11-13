package com.proyecto.servidor;

import com.proyecto.modelo.Metrica;
import com.proyecto.modelo.Nodo;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Locale;

/**
 * Ventana (no modal) con la gráfica en vivo de CPU y RAM de un nodo.
 *
 * Se abre con doble clic sobre una fila de la tabla de dispositivos. El
 * ServidorCentral llama a {@link #actualizar()} cada vez que llega una lectura
 * de ese nodo, y un temporizador repinta cada segundo para que el eje de
 * tiempo avance aunque no lleguen datos. Todos los métodos se usan en el EDT.
 */
public class VentanaGraficaNodo extends JDialog {

    public static final int MINUTOS_VISIBLES = 10;

    private static final Color COLOR_TEXTO = new Color(15, 23, 42);
    private static final Color COLOR_TEXTO_GRIS = new Color(71, 85, 105);
    private static final Color COLOR_FONDO = new Color(238, 243, 250);
    private static final Color COLOR_BORDE = new Color(226, 232, 240);

    private final Nodo nodo;
    private final GraficaMetricas grafica = new GraficaMetricas(MINUTOS_VISIBLES);
    private final JLabel lblCpu = crearValor();
    private final JLabel lblRam = crearValor();
    private final JLabel lblEstado = new JLabel();
    private final Timer reloj;

    public VentanaGraficaNodo(JFrame duenio, Nodo nodo, Runnable alCerrar) {
        super(duenio, "Gráfica en vivo · Nodo #" + String.format("%03d", nodo.getId()), false);
        this.nodo = nodo;

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(620, 440));
        setSize(860, 520);
        setLocationRelativeTo(duenio);

        JPanel raiz = new JPanel(new BorderLayout(0, 14));
        raiz.setBackground(COLOR_FONDO);
        raiz.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        raiz.add(crearEncabezado(), BorderLayout.NORTH);

        JPanel marcoGrafica = new JPanel(new BorderLayout());
        marcoGrafica.setBackground(Color.WHITE);
        marcoGrafica.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(COLOR_BORDE),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));
        marcoGrafica.add(grafica, BorderLayout.CENTER);
        raiz.add(marcoGrafica, BorderLayout.CENTER);

        lblEstado.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        lblEstado.setForeground(COLOR_TEXTO_GRIS);
        raiz.add(lblEstado, BorderLayout.SOUTH);

        setContentPane(raiz);

        reloj = new Timer(1000, e -> grafica.repaint());
        reloj.start();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                reloj.stop();
                if (alCerrar != null) {
                    alCerrar.run();
                }
            }
        });

        actualizar();
    }

    private JPanel crearEncabezado() {
        JPanel encabezado = new JPanel(new BorderLayout(16, 0));
        encabezado.setOpaque(false);

        JPanel textos = new JPanel(new GridLayout(2, 1, 0, 2));
        textos.setOpaque(false);
        JLabel titulo = new JLabel("Nodo #" + String.format("%03d", nodo.getId()) + "  ·  " + nodo.getNombreVisible());
        titulo.setFont(new Font("Segoe UI", Font.BOLD, 16));
        titulo.setForeground(COLOR_TEXTO);
        JLabel subtitulo = new JLabel("CPU y RAM de los últimos " + MINUTOS_VISIBLES
                + " minutos · " + nodo.getSistemaOperativo());
        subtitulo.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        subtitulo.setForeground(COLOR_TEXTO_GRIS);
        textos.add(titulo);
        textos.add(subtitulo);
        encabezado.add(textos, BorderLayout.CENTER);

        JPanel valores = new JPanel(new GridLayout(1, 2, 10, 0));
        valores.setOpaque(false);
        valores.add(crearTarjetaValor("CPU", GraficaMetricas.COLOR_CPU, lblCpu));
        valores.add(crearTarjetaValor("RAM", GraficaMetricas.COLOR_RAM, lblRam));
        encabezado.add(valores, BorderLayout.EAST);
        return encabezado;
    }

    private JPanel crearTarjetaValor(String nombre, Color colorSerie, JLabel valor) {
        JPanel tarjeta = new JPanel(new BorderLayout(0, 2));
        tarjeta.setBackground(Color.WHITE);
        tarjeta.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 4, 0, 0, colorSerie),
                BorderFactory.createEmptyBorder(6, 12, 6, 14)));
        JLabel etiqueta = new JLabel(nombre + " ACTUAL");
        etiqueta.setFont(new Font("Segoe UI", Font.BOLD, 9));
        etiqueta.setForeground(COLOR_TEXTO_GRIS);
        tarjeta.add(etiqueta, BorderLayout.NORTH);
        tarjeta.add(valor, BorderLayout.CENTER);
        tarjeta.setPreferredSize(new Dimension(118, 54));
        return tarjeta;
    }

    private static JLabel crearValor() {
        JLabel lbl = new JLabel("—");
        lbl.setFont(new Font("Segoe UI", Font.BOLD, 20));
        return lbl;
    }

    /** Vuelve a leer el historial del nodo. Llamar en el EDT al llegar una lectura nueva. */
    public void actualizar() {
        List<Metrica> historial = nodo.getHistorial();
        grafica.setDatos(historial);

        if (historial.isEmpty()) {
            pintarValor(lblCpu, Double.NaN);
            pintarValor(lblRam, Double.NaN);
            lblEstado.setText("Aún no llegan lecturas de este nodo. El agente envía una cada ~10 s.");
            return;
        }

        Metrica ultima = historial.get(historial.size() - 1);
        pintarValor(lblCpu, ultima.getCpu());
        pintarValor(lblRam, ultima.getRam());
        lblEstado.setText("Última lectura: " + ultima.getFechaHoraFormateada()
                + "   ·   " + historial.size() + " lecturas en memoria"
                + "   ·   se actualiza en vivo");
    }

    /** El nodo se desconectó: la gráfica se congela con lo último que llegó. */
    public void marcarDesconectado() {
        reloj.stop();
        setTitle(getTitle() + " (desconectado)");
        lblEstado.setForeground(NivelMetrica.CRITICO.getColor());
        lblEstado.setText("El nodo se desconectó. La gráfica muestra las últimas lecturas recibidas.");
    }

    private static void pintarValor(JLabel lbl, double valor) {
        if (Double.isNaN(valor)) {
            lbl.setText("—");
            lbl.setForeground(COLOR_TEXTO_GRIS);
        } else {
            lbl.setText(String.format(Locale.ROOT, "%.1f %%", valor));
            lbl.setForeground(NivelMetrica.de(valor).getColor());
        }
    }
}
