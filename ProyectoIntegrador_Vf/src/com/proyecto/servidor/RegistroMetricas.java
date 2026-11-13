package com.proyecto.servidor;

import com.proyecto.modelo.Metrica;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Guarda en un CSV cada lectura periódica de CPU, RAM y disco recibida de los
 * nodos. Sirve como evidencia del REQ-02 ("la prueba recibe registros con el
 * intervalo definido"): la columna fecha_hora permite comprobar el intervalo.
 */
public class RegistroMetricas {

    private static final String ENCABEZADO = "fecha_hora,nodo,ip,cpu_pct,ram_pct,disco_pct";
    private static final DateTimeFormatter FORMATO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path archivo;

    public RegistroMetricas(String nombreArchivo) {
        this.archivo = Path.of(nombreArchivo);
    }

    /** Añade una fila. Es synchronized porque lo llaman varios hilos ManejadorCliente. */
    public synchronized void registrar(int idNodo, String ip, Metrica m) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (!Files.exists(archivo)) {
            sb.append(ENCABEZADO).append(System.lineSeparator());
        }
        sb.append(String.format(Locale.ROOT, "%s,%03d,%s,%.1f,%.1f,%.1f",
                LocalDateTime.now().format(FORMATO), idNodo, ip,
                m.getCpu(), m.getRam(), m.getDisco()))
          .append(System.lineSeparator());

        Files.writeString(archivo, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
