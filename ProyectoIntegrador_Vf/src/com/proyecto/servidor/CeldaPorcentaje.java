package com.proyecto.servidor;

import javax.swing.BorderFactory;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Locale;

/**
 * Celda de la tabla de dispositivos para las columnas CPU y RAM.
 *
 * Muestra el porcentaje y una barra horizontal; ambos toman el color del
 * nivel (verde, ámbar o rojo). Si el nodo todavía no envió métricas, o ya se
 * desconectó, la celda contiene "—" y se dibuja en gris sin barra.
 */
public class CeldaPorcentaje extends DefaultTableCellRenderer {

    private static final Color COLOR_PISTA = new Color(226, 232, 240);
    private static final Color COLOR_SIN_DATO = new Color(148, 163, 184);
    private static final int ANCHO_TEXTO = 58;
    private static final int ALTO_BARRA = 6;

    private final Color filaPar;
    private final Color filaImpar;
    private final Color seleccion;

    /** Valor que se está dibujando; NaN si la celda no tiene dato. */
    private double valor = Double.NaN;

    public CeldaPorcentaje(Color filaPar, Color filaImpar, Color seleccion) {
        this.filaPar = filaPar;
        this.filaImpar = filaImpar;
        this.seleccion = seleccion;
    }

    @Override
    public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {

        super.getTableCellRendererComponent(table, value, isSelected, false, row, column);
        setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        setHorizontalAlignment(SwingConstants.LEFT);
        setBackground(isSelected ? seleccion : (row % 2 == 0 ? filaPar : filaImpar));

        if (value instanceof Number) {
            valor = ((Number) value).doubleValue();
            setText(String.format(Locale.ROOT, "%.1f %%", valor));
            setForeground(NivelMetrica.de(valor).getColor());
            setFont(new Font("Segoe UI", Font.BOLD, 12));
        } else {
            valor = Double.NaN;
            setText("—");
            setForeground(COLOR_SIN_DATO);
            setFont(new Font("Segoe UI", Font.PLAIN, 12));
        }
        return this;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);   // fondo + texto
        if (Double.isNaN(valor)) {
            return;
        }

        int x = 12 + ANCHO_TEXTO;
        int ancho = getWidth() - x - 12;
        if (ancho < 20) {
            return;               // columna demasiado angosta: solo el número
        }
        int y = (getHeight() - ALTO_BARRA) / 2;

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(COLOR_PISTA);
        g2.fillRoundRect(x, y, ancho, ALTO_BARRA, ALTO_BARRA, ALTO_BARRA);

        int lleno = (int) Math.round(ancho * Math.max(0, Math.min(100, valor)) / 100.0);
        if (lleno > 0) {
            g2.setColor(NivelMetrica.de(valor).getColor());
            g2.fillRoundRect(x, y, Math.max(lleno, ALTO_BARRA), ALTO_BARRA, ALTO_BARRA, ALTO_BARRA);
        }
        g2.dispose();
    }
}
