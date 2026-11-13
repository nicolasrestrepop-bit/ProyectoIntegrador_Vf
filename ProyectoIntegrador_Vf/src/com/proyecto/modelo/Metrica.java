package com.proyecto.modelo;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Representa las métricas obtenidas de un nodo.
 */
public class Metrica {

    private final double cpu;
    private final double ram;
    private final double disco;
    private final LocalDateTime fechaHora;

    public Metrica(double cpu, double ram, double disco) {
        validarPorcentaje(cpu, "CPU");
        validarPorcentaje(ram, "RAM");
        validarPorcentaje(disco, "Disco");

        this.cpu = cpu;
        this.ram = ram;
        this.disco = disco;
        this.fechaHora = LocalDateTime.now();
    }

    private void validarPorcentaje(double valor, String nombre) {
        if (valor < 0 || valor > 100) {
            throw new IllegalArgumentException(
                    nombre + " debe estar entre 0 y 100."
            );
        }
    }

    public double getCpu() {
        return cpu;
    }

    public double getRam() {
        return ram;
    }

    public double getDisco() {
        return disco;
    }

    public LocalDateTime getFechaHora() {
        return fechaHora;
    }

    /** Momento de la lectura en milisegundos (para ubicarla en el eje de tiempo de la gráfica). */
    public long getInstanteMs() {
        return fechaHora.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    public String getFechaHoraFormateada() {
        DateTimeFormatter formato =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        return fechaHora.format(formato);
    }

    @Override
    public String toString() {
        return String.format(
                "CPU: %.1f%% | RAM: %.1f%% | Disco: %.1f%% | Fecha: %s",
                cpu,
                ram,
                disco,
                getFechaHoraFormateada()
        );
    }
}
