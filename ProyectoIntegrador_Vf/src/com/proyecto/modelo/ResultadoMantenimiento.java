package com.proyecto.modelo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Representa el resultado de una tarea de mantenimiento
 * ejecutada en un nodo.
 */
public class ResultadoMantenimiento {

    private final String comando;
    private final boolean exitoso;
    private final String mensaje;
    private final LocalDateTime fechaHora;

    public ResultadoMantenimiento(
            String comando,
            boolean exitoso,
            String mensaje) {

        this.comando = comando;
        this.exitoso = exitoso;
        this.mensaje = mensaje;
        this.fechaHora = LocalDateTime.now();
    }

    public String getComando() {
        return comando;
    }

    public boolean isExitoso() {
        return exitoso;
    }

    public String getMensaje() {
        return mensaje;
    }

    public LocalDateTime getFechaHora() {
        return fechaHora;
    }

    public String getFechaHoraFormateada() {

        DateTimeFormatter formato =
                DateTimeFormatter.ofPattern(
                        "yyyy-MM-dd HH:mm:ss"
                );

        return fechaHora.format(formato);
    }

    @Override
    public String toString() {

        String estado =
                exitoso ? "ÉXITO" : "ERROR";

        return String.format(
                "[%s] %s | Comando: %s | %s",
                getFechaHoraFormateada(),
                estado,
                comando,
                mensaje
        );
    }
}