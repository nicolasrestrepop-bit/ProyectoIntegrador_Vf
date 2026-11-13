package com.proyecto.servidor;

import java.awt.Color;

/**
 * Nivel visual de una lectura de CPU o RAM: decide el color con que se muestra
 * en la tabla de dispositivos y en la gráfica en vivo.
 *
 *   NORMAL       por debajo de 70 %          verde
 *   ADVERTENCIA  desde 70 % hasta 90 %       ámbar
 *   CRITICO      desde 90 %                  rojo
 *
 * El 90 % coincide con el umbral por defecto del monitoreo continuo del agente
 * (MonitorContinuo). La diferencia es que aquí el color cambia con cada lectura,
 * mientras que el agente solo envía @@ALERTA cuando la medida se mantiene alta
 * varias muestras seguidas.
 */
public enum NivelMetrica {

    NORMAL(new Color(22, 163, 74)),
    ADVERTENCIA(new Color(217, 119, 6)),
    CRITICO(new Color(220, 38, 38));

    public static final double UMBRAL_ADVERTENCIA = 70;
    public static final double UMBRAL_CRITICO = 90;

    private final Color color;

    NivelMetrica(Color color) {
        this.color = color;
    }

    public Color getColor() {
        return color;
    }

    public static NivelMetrica de(double porcentaje) {
        if (porcentaje >= UMBRAL_CRITICO) {
            return CRITICO;
        }
        if (porcentaje >= UMBRAL_ADVERTENCIA) {
            return ADVERTENCIA;
        }
        return NORMAL;
    }
}
