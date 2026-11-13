package com.proyecto.servidor;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class GeneradorReportes {

    private GeneradorReportes() {
        // Clase de utilidad: no necesita ser instanciada.
    }

    public static String construirReporteEstructurado(
            String logs,
            int nodosActivos,
            String estadoRed) {

        StringBuilder reporte = new StringBuilder();

        reporte.append("========================================\n");
        reporte.append("       REPORTE GENERAL DEL SISTEMA\n");
        reporte.append("========================================\n\n");

        reporte.append("ESTADO DE LA RED\n");
        reporte.append("----------------------------------------\n");
        reporte.append(estadoRed).append("\n");
        reporte.append("Nodos activos: ")
                .append(nodosActivos)
                .append("\n\n");

        reporte.append("REGISTRO DE ACTIVIDAD\n");
        reporte.append("----------------------------------------\n");

        if (logs == null || logs.isBlank()) {
            reporte.append("No hay registros disponibles.\n");
        } else {
            reporte.append(logs);
        }

        reporte.append("\n========================================\n");
        reporte.append("Fin del reporte\n");
        reporte.append("========================================\n");

        return reporte.toString();
    }

    public static void exportarAArchivo(
            String contenido,
            String nombreArchivo) {

        File archivo = new File(nombreArchivo);

        try (PrintWriter escritor =
                     new PrintWriter(new FileWriter(archivo))) {

            escritor.print(contenido);

        } catch (IOException e) {
            throw new RuntimeException(
                    "No se pudo guardar el reporte: "
                            + e.getMessage(),
                    e
            );
        }
    }
}