package com.proyecto.cliente;

import java.io.File;

public class ReporteMetricas {

    public static String generarReporte() {
        String os = System.getProperty("os.name");
        String usuario = System.getProperty("user.name");
        
        File disco = new File("/");
        long totalGB = disco.getTotalSpace() / (1024 * 1024 * 1024);
        long libreGB = disco.getFreeSpace() / (1024 * 1024 * 1024);
        long usadoGB = totalGB - libreGB;

        long ramLibreMB = Runtime.getRuntime().freeMemory() / (1024 * 1024);

        return String.format(
            "   -> Sistema Operativo: %s\n" +
            "   -> Usuario Activo:    %s\n" +
            "   -> Almacenamiento:    %d GB usados / %d GB libres (Total: %d GB)\n" +
            "   -> Memoria JVM Libre: %d MB",
            os, usuario, usadoGB, libreGB, totalGB, ramLibreMB
        );
    }
}
