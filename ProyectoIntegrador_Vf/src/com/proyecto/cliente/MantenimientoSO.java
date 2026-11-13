package com.proyecto.cliente;

import com.proyecto.modelo.ResultadoMantenimiento;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.PowerSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agente de mantenimiento del nodo cliente.
 *
 * Recibe comandos desde el Servidor Central y los ejecuta localmente.
 * Toda la información de hardware (CPU, RAM, temperatura, batería) se
 * obtiene aquí mediante OSHI; el servidor solo recibe el texto resultante
 * y no necesita conocer detalles del hardware de cada nodo.
 *
 * Comandos principales (botones de la interfaz):
 *   LIMPIAR_TEMP         -> limpieza de temporales
 *   OPTIMIZAR_MEMORIA    -> libera la memoria de la JVM del agente y reporta
 *                           la RAM real antes/después (no cierra procesos)
 *   DIAGNOSTICO_SISTEMA  -> diagnóstico completo (SO, CPU, RAM, todas las
 *                           unidades de disco, temperatura, batería, salud)
 *   MONITOREAR_RED       -> diagnóstico avanzado de red
 *   PING_GATEWAY         -> ping al gateway real y a 8.8.8.8 por separado
 *   FLUSH_DNS            -> purga real de la caché DNS del sistema
 *   REPORTE_NODO         -> información general del nodo
 *
 * Comandos adicionales (sin botón, disponibles por si se necesitan):
 *   CPU, RAM, DISCO, TEMPERATURA, BATERIA, SALUD
 *
 * Lecturas de red (entradas de las listas desplegables de las tarjetas):
 *   PING_EXTENDIDO       -> pérdida de paquetes y latencia con 20 muestras
 *   DNS_GATEWAY          -> servidores DNS configurados, resolución y gateway
 *   TRAFICO_RED          -> bytes/s y totales rx/tx por interfaz
 *   PROBAR_PUERTOS       -> prueba TCP; parámetros "host puertos"
 *                           (ej.: PROBAR_PUERTOS 192.168.1.1 22,80,443)
 *   CONEXIONES_ACTIVAS   -> resumen por estado, establecidas y en escucha
 *
 * Lecturas del equipo:
 *   TOP_CPU [n]          -> procesos que más CPU usan (n opcional, máx. 30)
 *   TOP_MEMORIA [n]      -> procesos que más RAM usan
 *   TIEMPO_ENCENDIDO     -> desde cuándo está encendido el equipo
 *   INVENTARIO           -> hardware y software básico
 *
 * Monitoreo continuo con alertas por umbral (lo ejecuta MonitorContinuo):
 *   MONITOREO_ESTADO     -> estado, umbrales, última muestra y alertas activas
 *   MONITOREO_CONFIG ... -> cambia umbrales, p. ej. "cpu=85 ram=90 lat=150 intervalo=10"
 *   MONITOREO_ON / _OFF  -> activa o desactiva el muestreo
 *
 * Control interno (lo maneja AgenteCliente, no llega aquí): "@@LAT" mide la
 * latencia del canal servidor-agente.
 */
public class MantenimientoSO {

    // ------------------------------------------------------------------
    //  EJECUCIÓN DE COMANDOS
    // ------------------------------------------------------------------

    public static String ejecutarAccion(String comando) {
        if (comando == null || comando.trim().isEmpty()) {
            return "ERROR: Comando nulo o vacío recibido.";
        }

        String comandoLimpio = extraerComando(comando);
        String[] argumentos = extraerArgumentos(comando);

        try {
            switch (comandoLimpio) {

                // === DIAGNÓSTICO AVANZADO DE RED ===
                case "DIAGNOSTICO_RED_AVANZADO":
                case "DIAGNOSTICO_RED":
                case "RED_AVANZADA":
                case "MONITOREO_RED":
                case "MONITOREAR_RED":
                case "MONITORE_RED":
                    return obtenerDiagnosticoRedAvanzado();

                // === DIAGNÓSTICO DE SISTEMA Y HARDWARE (OSHI) ===
                case "DIAGNOSTICO_SISTEMA":
                case "DIAGNOSTICO":
                case "SISTEMA":
                    return obtenerDiagnosticoSistema();

                // === COMPONENTES INDIVIDUALES (sin botón propio) ===
                case "DISCO":
                    return seccionDiscos();

                case "CPU":
                    return conOshi(() -> LectorHardware.seccionCpu(
                            LectorHardware.leer(1000)));

                case "RAM":
                    return conOshi(() -> LectorHardware.seccionRam(
                            LectorHardware.leer(0)));

                case "TEMPERATURA":
                case "TEMPERATURA_CPU":
                    return conOshi(() -> LectorHardware.seccionTemperatura(
                            LectorHardware.leer(0)));

                case "BATERIA":
                case "BATERÍA":
                    return conOshi(LectorHardware::seccionBateria);

                case "SALUD":
                case "SALUD_HARDWARE":
                    return conOshi(() -> LectorHardware.seccionSalud(
                            LectorHardware.leer(500)));

                // === REPORTE DEL NODO ===
                case "REPORTE_NODO":
                    return obtenerReporteNodo();

                case "PING_GATEWAY":
                case "PING":
                    return probarConectividadGateway();

                // === LECTURAS DE RED ===
                case "PING_EXTENDIDO":
                case "PERDIDA_LATENCIA":
                    return probarConectividad(PINGS_EXTENDIDOS,
                            "PÉRDIDA DE PAQUETES Y LATENCIA (" + PINGS_EXTENDIDOS + " muestras)");

                case "DNS_GATEWAY":
                case "DNS":
                    return diagnosticoDnsGateway();

                case "TRAFICO_RED":
                case "TRAFICO":
                    return traficoPorInterfaz();

                case "PROBAR_PUERTOS":
                case "PUERTOS":
                    return probarPuertos(argumentos);

                case "CONEXIONES_ACTIVAS":
                case "CONEXIONES":
                    return conexionesActivas();

                // === LECTURAS DEL EQUIPO ===
                case "TOP_CPU":
                    return topProcesos(true, argumentos);

                case "TOP_MEMORIA":
                    return topProcesos(false, argumentos);

                case "TIEMPO_ENCENDIDO":
                case "UPTIME":
                    return tiempoEncendido();

                case "INVENTARIO":
                    return inventarioEquipo();

                // === MONITOREO CONTINUO (umbrales y alertas) ===
                case "MONITOREO_ESTADO":
                    return MonitorContinuo.estado();

                case "MONITOREO_CONFIG":
                    return MonitorContinuo.configurar(argumentos);

                case "MONITOREO_ON":
                    return MonitorContinuo.activar(true);

                case "MONITOREO_OFF":
                    return MonitorContinuo.activar(false);

                // === MANTENIMIENTO DEL SO ===
                case "LIMPIAR_TEMP":
                case "LIMPIEZA":
                    return ejecutarLimpiezaTemporales();

                case "OPTIMIZAR_MEMORIA":
                case "OPTIMIZAR":
                    return optimizarMemoria();

                // REINICIAR_SERVICIOS se conserva como alias: solo purga DNS.
                case "REINICIAR_SERVICIOS":
                case "FLUSH_DNS":
                    return purgarCacheDns();

                default:
                    return "ADVERTENCIA: El comando '" + comandoLimpio
                            + "' no está registrado en el Agente Cliente.";
            }
        } catch (Exception e) {
            // Evita que un error inesperado cierre el ciclo del AgenteCliente.
            return "ERROR al ejecutar '" + comandoLimpio + "': " + e.getMessage();
        }
    }

    /**
     * Ejecuta una acción y encapsula el resultado
     * en un objeto de tipo ResultadoMantenimiento.
     */
    public static ResultadoMantenimiento ejecutarAccionComoResultado(
            String comando) {

        String respuesta = ejecutarAccion(comando);

        String comandoLimpio;
        if (comando == null || comando.trim().isEmpty()) {
            comandoLimpio = "COMANDO_INVALIDO";
        } else {
            comandoLimpio = extraerComando(comando);
        }

        // Una respuesta es exitosa salvo que el agente la marque como
        // error, advertencia o alerta. (Así los reportes que empiezan con
        // "=== ..." o "RED OK" no se contabilizan como fallos.)
        boolean exitoso =
                !respuesta.startsWith("ERROR")
                && !respuesta.startsWith("ADVERTENCIA")
                && !respuesta.startsWith("ALERTA");

        return new ResultadoMantenimiento(
                comandoLimpio,
                exitoso,
                respuesta
        );
    }

    private static String extraerComando(String comando) {
        return comando.trim().split("\\s+")[0].toUpperCase(Locale.ROOT);
    }

    /** Parámetros que acompañan al comando: "PROBAR_PUERTOS host 22,80" -> {host, 22,80}. */
    private static String[] extraerArgumentos(String comando) {
        String[] partes = comando.trim().split("\\s+");
        return java.util.Arrays.copyOfRange(partes, 1, partes.length);
    }

    // ------------------------------------------------------------------
    //  DIAGNÓSTICO DEL SISTEMA (OSHI + disco)
    // ------------------------------------------------------------------

    /**
     * Diagnóstico completo: SO, CPU, RAM, disco, temperatura, batería y
     * salud del hardware. Si OSHI no está disponible (faltan las
     * librerías en el classpath) se devuelve el diagnóstico básico con
     * datos de la JVM para que el comando nunca falle del todo.
     */
    private static String obtenerDiagnosticoSistema() {
        try {
            return LectorHardware.diagnosticoCompleto();
        } catch (Exception | LinkageError e) {
            return diagnosticoBasicoJVM(e);
        }
    }

    /** Respaldo sin OSHI (diagnóstico de la versión original). */
    private static String diagnosticoBasicoJVM(Throwable motivo) {
        try {
            String os = System.getProperty("os.name")
                    + " (" + System.getProperty("os.arch") + ")";
            String usuario = System.getProperty("user.name");

            long memoriaLibreMB = Runtime.getRuntime().freeMemory() / (1024 * 1024);
            long memoriaTotalMB = Runtime.getRuntime().totalMemory() / (1024 * 1024);
            long memoriaMaxMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);

            return "ÉXITO [DIAGNÓSTICO SISTEMA - MODO BÁSICO]: SO: " + os
                    + " | Usuario: " + usuario
                    + " | RAM JVM: " + (memoriaTotalMB - memoriaLibreMB) + "MB usada / "
                    + memoriaTotalMB + "MB asignada (máx. " + memoriaMaxMB + "MB)"
                    + "\n" + seccionDiscos()
                    + "\nNOTA: OSHI no está disponible en este nodo ("
                    + motivo.getClass().getSimpleName()
                    + "). Agregue oshi-core, jna, jna-platform y slf4j-api al classpath "
                    + "para obtener CPU, temperatura y batería.";
        } catch (Exception e) {
            return "ERROR en diagnóstico de sistema: " + e.getMessage();
        }
    }

    /** Ejecuta una lectura OSHI y convierte fallos de librería en mensaje. */
    private static String conOshi(java.util.function.Supplier<String> lectura) {
        try {
            return lectura.get();
        } catch (Exception | LinkageError e) {
            return "ERROR: OSHI no disponible en este nodo ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + ").";
        }
    }

    // ------------------------------------------------------------------
    //  LECTOR DE HARDWARE (única parte del programa que usa OSHI)
    //
    //  Está en una clase anidada para que OSHI solo se cargue cuando se
    //  pide un diagnóstico de hardware. Si las librerías faltan, el resto
    //  del agente (red, ping, limpieza, reporte) sigue funcionando.
    // ------------------------------------------------------------------

    private static final class LectorHardware {

        private static final SystemInfo SISTEMA = new SystemInfo();
        private static final HardwareAbstractionLayer HARDWARE = SISTEMA.getHardware();

        /** Instantánea de métricas para no medir dos veces lo mismo. */
        private static final class Lectura {
            double usoCpu;          // %
            long ramTotal;          // bytes
            long ramDisponible;     // bytes
            long ramUsada;          // bytes
            double usoRam;          // %
            double temperatura;     // °C (<= 0 si no hay sensor)

            boolean hayTemperatura() {
                return !Double.isNaN(temperatura) && temperatura > 0;
            }
        }

        /**
         * @param delayCpuMs tiempo de muestreo del CPU en ms (0 = no medir CPU)
         */
        static Lectura leer(long delayCpuMs) {
            Lectura l = new Lectura();

            if (delayCpuMs > 0) {
                double uso = HARDWARE.getProcessor()
                        .getSystemCpuLoad(delayCpuMs) * 100.0;
                l.usoCpu = Double.isNaN(uso) ? 0 : Math.max(0, uso);
            }

            GlobalMemory memoria = HARDWARE.getMemory();
            l.ramTotal = memoria.getTotal();
            l.ramDisponible = memoria.getAvailable();
            l.ramUsada = l.ramTotal - l.ramDisponible;
            l.usoRam = l.ramTotal > 0
                    ? ((double) l.ramUsada / l.ramTotal) * 100.0
                    : 0;

            l.temperatura = HARDWARE.getSensors().getCpuTemperature();
            return l;
        }

        // ---- Diagnóstico completo ----

        static String diagnosticoCompleto() {
            // Una sola medición compartida por CPU y Salud del hardware.
            Lectura l = leer(1000);

            StringBuilder sb = new StringBuilder();
            sb.append("ÉXITO [DIAGNÓSTICO DEL SISTEMA]\n");
            sb.append("============================================\n");
            sb.append(seccionSistema()).append("\n\n");
            sb.append(seccionCpu(l)).append("\n\n");
            sb.append(seccionRam(l)).append("\n\n");
            sb.append(seccionDiscos()).append("\n\n");
            sb.append(seccionTemperatura(l)).append("\n\n");
            sb.append(seccionBateria().stripTrailing()).append("\n\n");
            sb.append(seccionSalud(l)).append("\n");
            sb.append("============================================");
            return sb.toString();
        }

        // ---- Secciones ----

        static String seccionSistema() {
            return "=== SISTEMA OPERATIVO ===\n"
                    + "Sistema: " + SISTEMA.getOperatingSystem().getFamily() + "\n"
                    + "Versión: " + SISTEMA.getOperatingSystem().getVersionInfo() + "\n"
                    + "Arquitectura: " + System.getProperty("os.arch") + "\n"
                    + "Usuario: " + System.getProperty("user.name");
        }

        static String seccionCpu(Lectura l) {
            CentralProcessor cpu = HARDWARE.getProcessor();

            return "=== CPU ===\n"
                    + "Modelo: " + cpu.getProcessorIdentifier().getName() + "\n"
                    + "Núcleos físicos: " + cpu.getPhysicalProcessorCount() + "\n"
                    + "Núcleos lógicos: " + cpu.getLogicalProcessorCount() + "\n"
                    + "Uso real: " + fmt(l.usoCpu, 2) + "%\n"
                    + "Estado: " + estadoCpu(l.usoCpu);
        }

        static String seccionRam(Lectura l) {
            return "=== RAM FÍSICA ===\n"
                    + "RAM total: " + convertirGB(l.ramTotal) + "\n"
                    + "RAM usada: " + convertirGB(l.ramUsada) + "\n"
                    + "RAM disponible: " + convertirGB(l.ramDisponible) + "\n"
                    + "Uso real: " + fmt(l.usoRam, 2) + "%\n"
                    + "Estado: " + estadoRam(l.usoRam);
        }

        static String seccionTemperatura(Lectura l) {
            if (!l.hayTemperatura()) {
                return "=== TEMPERATURA CPU ===\n"
                        + "Sensor no disponible en este equipo.";
            }

            return "=== TEMPERATURA CPU ===\n"
                    + "Temperatura: " + fmt(l.temperatura, 1) + " °C\n"
                    + "Estado: " + estadoTemperatura(l.temperatura);
        }

        static String seccionBateria() {
            List<PowerSource> baterias = HARDWARE.getPowerSources();

            if (baterias == null || baterias.isEmpty()) {
                return "=== BATERÍA ===\nNo se detectó batería.";
            }

            StringBuilder sb = new StringBuilder("=== BATERÍA ===\n");

            for (PowerSource bateria : baterias) {
                double carga = bateria.getRemainingCapacityPercent() * 100.0;

                sb.append("Nombre: ").append(bateria.getName()).append("\n");

                sb.append("Carga: ")
                        .append(carga >= 0 ? fmt(carga, 1) + "%" : "NO DISPONIBLE")
                        .append("\n");

                sb.append("Conectada a corriente: ")
                        .append(bateria.isPowerOnLine() ? "SÍ" : "NO")
                        .append("\n");

                sb.append("Cargando: ")
                        .append(bateria.isCharging() ? "SÍ" : "NO")
                        .append("\n");

                if (bateria.getVoltage() > 0) {
                    sb.append("Voltaje: ")
                            .append(fmt(bateria.getVoltage(), 2))
                            .append(" V\n");
                }

                // Salud = capacidad máxima actual frente a la de diseño
                // (la capacidad "actual" es la carga, no el desgaste).
                int capacidadMaxima = bateria.getMaxCapacity();
                int capacidadDiseno = bateria.getDesignCapacity();

                if (capacidadDiseno > 0 && capacidadMaxima > 0) {
                    double salud = ((double) capacidadMaxima / capacidadDiseno) * 100.0;

                    sb.append("Salud estimada: ").append(fmt(salud, 1)).append("%\n");

                    if (salud < 50) {
                        sb.append("Estado: MUY DESGASTADA\n");
                    } else if (salud < 80) {
                        sb.append("Estado: DESGASTE MODERADO\n");
                    } else {
                        sb.append("Estado: BUENA\n");
                    }
                } else {
                    sb.append("Salud: NO DISPONIBLE\n");
                }
            }

            return sb.toString();
        }

        static String seccionSalud(Lectura l) {
            int problemas = 0;

            if (l.hayTemperatura() && l.temperatura >= 85) {
                problemas++;
            }
            if (l.usoCpu >= 90) {
                problemas++;
            }
            if (l.usoRam >= 95) {
                problemas++;
            }

            String estado;
            if (problemas >= 2) {
                estado = "CRÍTICO";
            } else if (problemas == 1) {
                estado = "ADVERTENCIA";
            } else {
                estado = "SALUDABLE";
            }

            return "=== SALUD DEL HARDWARE ===\n"
                    + "CPU: " + fmt(l.usoCpu, 2) + "%\n"
                    + "RAM: " + fmt(l.usoRam, 2) + "%\n"
                    + "Temperatura: "
                    + (l.hayTemperatura() ? fmt(l.temperatura, 1) + " °C" : "No disponible")
                    + "\n"
                    + "Problemas detectados: " + problemas + "\n"
                    + "Estado general: " + estado;
        }

        // ---- Procesos ----

        /** Texto con los {@code n} procesos que más RAM física usan. */
        static String topProcesosPorMemoria(int n) {
            List<oshi.software.os.OSProcess> lista = SISTEMA.getOperatingSystem().getProcesses(
                    oshi.software.os.OperatingSystem.ProcessFiltering.ALL_PROCESSES,
                    oshi.software.os.OperatingSystem.ProcessSorting.RSS_DESC,
                    n);

            StringBuilder sb = new StringBuilder();
            int pos = 1;
            for (oshi.software.os.OSProcess p : lista) {
                sb.append("  ").append(pos++).append(". ").append(p.getName())
                  .append(" (PID ").append(p.getProcessID()).append("): ")
                  .append(formatoBytes(p.getResidentSetSize())).append("\n");
            }
            return sb.toString().stripTrailing();
        }

        // ---- Muestreo continuo (sin esperas largas) ----

        private static long[] ticksCpuPrevios;

        /** CPU como diferencia entre lecturas consecutivas: no bloquea 1 s en cada muestra. */
        static synchronized void muestraContinua(Muestra m) {
            CentralProcessor cpu = HARDWARE.getProcessor();
            if (ticksCpuPrevios == null) {
                ticksCpuPrevios = cpu.getSystemCpuLoadTicks();
                try {
                    Thread.sleep(500); // primera muestra: hace falta un intervalo de referencia
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            double uso = cpu.getSystemCpuLoadBetweenTicks(ticksCpuPrevios) * 100.0;
            ticksCpuPrevios = cpu.getSystemCpuLoadTicks();
            if (!Double.isNaN(uso)) m.cpu = Math.max(0, uso);

            GlobalMemory memoria = HARDWARE.getMemory();
            long total = memoria.getTotal();
            if (total > 0) m.ram = (total - memoria.getAvailable()) * 100.0 / total;

            double temperatura = HARDWARE.getSensors().getCpuTemperature();
            if (!Double.isNaN(temperatura) && temperatura > 0) m.temperatura = temperatura;
        }

        // ---- Procesos: top por CPU o por memoria ----

        /**
         * @param porCpu true = ordena por CPU (mide 1 s); false = por RAM física
         */
        static String topProcesos(boolean porCpu, int n) throws InterruptedException {
            oshi.software.os.OperatingSystem os = SISTEMA.getOperatingSystem();
            long ramTotal = HARDWARE.getMemory().getTotal();
            int nucleos = Math.max(1, HARDWARE.getProcessor().getLogicalProcessorCount());
            List<FilaProceso> filas = new ArrayList<>();
            String medicion;
            String titulo;

            if (porCpu) {
                // El uso de CPU de un proceso es una diferencia entre dos lecturas.
                java.util.Map<Integer, oshi.software.os.OSProcess> previos = new java.util.HashMap<>();
                for (oshi.software.os.OSProcess p : os.getProcesses()) {
                    previos.put(p.getProcessID(), p);
                }
                long t0 = System.nanoTime();
                Thread.sleep(1000);
                double segundos = (System.nanoTime() - t0) / 1_000_000_000.0;

                for (oshi.software.os.OSProcess p : os.getProcesses()) {
                    if (p.getProcessID() == 0) continue; // "inactivo del sistema" no es un programa
                    oshi.software.os.OSProcess previo = previos.get(p.getProcessID());
                    // Un proceso nuevo no tiene lectura previa: se cuenta 0 en vez de inventar.
                    double carga = previo == null ? 0 : p.getProcessCpuLoadBetweenTicks(previo);
                    double cpu = Math.max(0, carga) * 100.0 / nucleos;
                    filas.add(new FilaProceso(p.getName(), p.getProcessID(), cpu, p.getResidentSetSize()));
                }
                filas.sort((a, b) -> Double.compare(b.cpu, a.cpu));
                medicion = "Medición de " + fmt(segundos, 1) + " s sobre " + nucleos
                        + " núcleos lógicos; el % es del total del equipo (100% = todos los núcleos).";
                titulo = "TOP " + Math.min(n, filas.size()) + " PROCESOS POR CPU";
            } else {
                List<oshi.software.os.OSProcess> lista = os.getProcesses(
                        oshi.software.os.OperatingSystem.ProcessFiltering.ALL_PROCESSES,
                        oshi.software.os.OperatingSystem.ProcessSorting.RSS_DESC,
                        n);
                for (oshi.software.os.OSProcess p : lista) {
                    filas.add(new FilaProceso(p.getName(), p.getProcessID(), Double.NaN,
                            p.getResidentSetSize()));
                }
                medicion = "Memoria física (RAM) que usa cada proceso. RAM total del equipo: "
                        + formatoBytes(ramTotal) + ".";
                titulo = "TOP " + Math.min(n, filas.size()) + " PROCESOS POR MEMORIA";
            }

            if (filas.isEmpty()) {
                return "ADVERTENCIA: OSHI no devolvió ningún proceso (el agente puede no tener permisos "
                        + "para listar procesos).";
            }
            return formatearTopProcesos(titulo, filas, n, medicion, ramTotal)
                    + "\nProcesos en ejecución: " + os.getProcessCount();
        }

        // ---- Tiempo encendido ----

        static long segundosEncendido() {
            return SISTEMA.getOperatingSystem().getSystemUptime();
        }

        // ---- Inventario de hardware y software ----

        static String inventario() {
            StringBuilder sb = new StringBuilder("ÉXITO [INVENTARIO DEL EQUIPO]\n");
            sb.append("============================================\n");
            sb.append(seguro("IDENTIFICACIÓN Y PLACA", LectorHardware::inventarioIdentificacion)).append("\n\n");
            sb.append(seguro("PROCESADOR", LectorHardware::inventarioProcesador)).append("\n\n");
            sb.append(seguro("MEMORIA RAM", LectorHardware::inventarioMemoria)).append("\n\n");
            sb.append(seguro("ALMACENAMIENTO", LectorHardware::inventarioDiscos)).append("\n\n");
            sb.append(seguro("GRÁFICOS", LectorHardware::inventarioGraficos)).append("\n\n");
            sb.append(seguro("ADAPTADORES DE RED", LectorHardware::inventarioRed)).append("\n\n");
            sb.append(seguro("SOFTWARE", LectorHardware::inventarioSoftware)).append("\n");
            sb.append("============================================");
            return sb.toString();
        }

        /** Una sección que falla no tumba el inventario completo. */
        private static String seguro(String titulo, java.util.function.Supplier<String> seccion) {
            try {
                return "=== " + titulo + " ===\n" + seccion.get();
            } catch (Exception | LinkageError e) {
                return "=== " + titulo + " ===\nNo disponible (" + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ").";
            }
        }

        private static String dato(String valor) {
            return valor == null || valor.isBlank() || valor.equalsIgnoreCase("unknown")
                    ? "No disponible" : valor.trim();
        }

        /** Une los datos que existan; si no hay ninguno, "No disponible". */
        private static String unir(String... valores) {
            StringBuilder sb = new StringBuilder();
            for (String v : valores) {
                if (v == null || v.isBlank() || v.equalsIgnoreCase("unknown")) continue;
                if (sb.length() > 0) sb.append(" ");
                sb.append(v.trim());
            }
            return sb.length() == 0 ? "No disponible" : sb.toString();
        }

        private static String inventarioIdentificacion() {
            oshi.hardware.ComputerSystem equipo = HARDWARE.getComputerSystem();
            String nombre;
            try {
                nombre = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                nombre = "No disponible";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Nombre del equipo: ").append(nombre).append("\n");
            sb.append("Fabricante: ").append(dato(equipo.getManufacturer())).append("\n");
            sb.append("Modelo: ").append(dato(equipo.getModel())).append("\n");
            sb.append("Número de serie: ").append(dato(equipo.getSerialNumber())).append("\n");
            oshi.hardware.Baseboard placa = equipo.getBaseboard();
            sb.append("Placa base: ").append(unir(placa.getManufacturer(), placa.getModel())).append("\n");
            oshi.hardware.Firmware bios = equipo.getFirmware();
            sb.append("BIOS/UEFI: ").append(unir(bios.getManufacturer(), bios.getVersion()))
              .append(" | Fecha: ").append(dato(bios.getReleaseDate()));
            return sb.toString();
        }

        private static String inventarioProcesador() {
            CentralProcessor cpu = HARDWARE.getProcessor();
            StringBuilder sb = new StringBuilder();
            sb.append("Modelo: ").append(cpu.getProcessorIdentifier().getName()).append("\n");
            sb.append("Núcleos físicos: ").append(cpu.getPhysicalProcessorCount())
              .append(" | Núcleos lógicos: ").append(cpu.getLogicalProcessorCount()).append("\n");
            long frecuencia = cpu.getProcessorIdentifier().getVendorFreq();
            sb.append("Frecuencia nominal: ")
              .append(frecuencia > 0 ? fmt(frecuencia / 1e9, 2) + " GHz" : "No disponible");
            long maxima = cpu.getMaxFreq();
            if (maxima > 0) sb.append(" | Máxima: ").append(fmt(maxima / 1e9, 2)).append(" GHz");
            return sb.toString();
        }

        private static String inventarioMemoria() {
            GlobalMemory memoria = HARDWARE.getMemory();
            StringBuilder sb = new StringBuilder("RAM total: ").append(convertirGB(memoria.getTotal()));
            List<oshi.hardware.PhysicalMemory> modulos = memoria.getPhysicalMemory();
            if (modulos == null || modulos.isEmpty()) {
                sb.append("\nMódulos: No disponible (el sistema no expone los módulos instalados).");
            } else {
                sb.append("\nMódulos instalados: ").append(modulos.size());
                for (oshi.hardware.PhysicalMemory m : modulos) {
                    sb.append("\n  - ").append(dato(m.getBankLabel())).append(": ")
                      .append(convertirGB(m.getCapacity())).append(" ").append(dato(m.getMemoryType()));
                    if (m.getClockSpeed() > 0) sb.append(" a ").append(m.getClockSpeed() / 1_000_000).append(" MHz");
                    sb.append(" (").append(dato(m.getManufacturer())).append(")");
                }
            }
            return sb.toString();
        }

        private static String inventarioDiscos() {
            List<oshi.hardware.HWDiskStore> discos = HARDWARE.getDiskStores();
            if (discos == null || discos.isEmpty()) return "No se detectaron discos físicos.";
            StringBuilder sb = new StringBuilder("Discos físicos: ").append(discos.size());
            for (oshi.hardware.HWDiskStore d : discos) {
                sb.append("\n  - ").append(d.getName()).append(" | ")
                  .append(unir(d.getModel())).append(" | ").append(convertirGB(d.getSize()));
            }
            return sb.toString();
        }

        private static String inventarioGraficos() {
            List<oshi.hardware.GraphicsCard> tarjetas = HARDWARE.getGraphicsCards();
            if (tarjetas == null || tarjetas.isEmpty()) return "No se detectaron tarjetas gráficas.";
            StringBuilder sb = new StringBuilder();
            for (oshi.hardware.GraphicsCard g : tarjetas) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("  - ").append(unir(g.getName())).append(" | Fabricante: ").append(dato(g.getVendor()));
                if (g.getVRam() > 0) sb.append(" | VRAM ").append(convertirGB(g.getVRam()));
            }
            return sb.toString();
        }

        private static String inventarioRed() {
            StringBuilder sb = new StringBuilder();
            for (oshi.hardware.NetworkIF n : HARDWARE.getNetworkIFs(false)) {
                String[] ipv4 = n.getIPv4addr();
                if (ipv4 == null || ipv4.length == 0) continue;
                if (sb.length() > 0) sb.append("\n");
                sb.append("  - ").append(dato(n.getDisplayName())).append(" | MAC ")
                  .append(dato(n.getMacaddr())).append(" | IPv4 ").append(String.join(", ", ipv4));
            }
            return sb.length() == 0 ? "No hay adaptadores con dirección IPv4." : sb.toString();
        }

        private static String inventarioSoftware() {
            oshi.software.os.OperatingSystem os = SISTEMA.getOperatingSystem();
            StringBuilder sb = new StringBuilder();
            sb.append("Sistema operativo: ").append(os.getManufacturer()).append(" ")
              .append(os.getFamily()).append(" ").append(os.getVersionInfo()).append(" (")
              .append(os.getBitness()).append(" bits)\n");
            sb.append("Usuario: ").append(System.getProperty("user.name")).append("\n");
            sb.append("Java del agente: ").append(System.getProperty("java.version")).append(" (")
              .append(System.getProperty("java.vendor")).append(")\n");
            sb.append("Procesos en ejecución: ").append(os.getProcessCount()).append("\n");

            List<oshi.software.os.ApplicationInfo> apps;
            try {
                apps = new ArrayList<>(os.getInstalledApplications());
            } catch (Exception | LinkageError e) {
                apps = new ArrayList<>();
            }
            if (apps.isEmpty()) {
                sb.append("Aplicaciones instaladas: No disponible (el sistema no expone la lista).");
                return sb.toString();
            }
            apps.removeIf(a -> a.getName() == null || a.getName().isBlank());
            apps.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            sb.append("Aplicaciones instaladas: ").append(apps.size());
            int limite = 30;
            for (int i = 0; i < apps.size() && i < limite; i++) {
                oshi.software.os.ApplicationInfo a = apps.get(i);
                sb.append("\n  - ").append(a.getName().trim());
                if (a.getVersion() != null && !a.getVersion().isBlank()) {
                    sb.append(" ").append(a.getVersion().trim());
                }
            }
            if (apps.size() > limite) {
                sb.append("\n  ... (").append(apps.size() - limite).append(" más no mostradas)");
            }
            return sb.toString();
        }

        // ---- Clasificación de estados ----

        private static String estadoCpu(double uso) {
            if (uso >= 90) return "CRÍTICO";
            if (uso >= 75) return "ALTO";
            if (uso >= 50) return "MODERADO";
            return "NORMAL";
        }

        private static String estadoRam(double porcentaje) {
            if (porcentaje >= 95) return "CRÍTICO";
            if (porcentaje >= 85) return "ALTO";
            if (porcentaje >= 70) return "MODERADO";
            return "NORMAL";
        }

        private static String estadoTemperatura(double temperatura) {
            if (temperatura >= 95) return "CRÍTICO - SOBRECALENTAMIENTO";
            if (temperatura >= 85) return "ALTO";
            if (temperatura >= 75) return "ELEVADO";
            return "NORMAL";
        }

        // ---- Utilidades de formato ----

        private static String fmt(double valor, int decimales) {
            return String.format(Locale.ROOT, "%." + decimales + "f", valor);
        }

        private static String convertirGB(long bytes) {
            double gb = bytes / (1024.0 * 1024.0 * 1024.0);
            return String.format(Locale.ROOT, "%.2f GB", gb);
        }
    }

    // ------------------------------------------------------------------
    //  DIAGNÓSTICO AVANZADO DE RED (versión original, sin cambios)
    // ------------------------------------------------------------------

    private static String obtenerDiagnosticoRedAvanzado() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== DIAGNÓSTICO COMPLETO DE RED Y ADAPTADORES ===\n");

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            int contador = 1;

            while (interfaces.hasMoreElements()) {
                NetworkInterface netIface = interfaces.nextElement();

                // Filtrar interfaces que están inactivas
                if (!netIface.isUp()) continue;

                sb.append("\n[Interfaz #").append(contador++).append(": ").append(netIface.getDisplayName()).append("]\n");
                sb.append(" - Nombre Técnico: ").append(netIface.getName()).append("\n");
                sb.append(" - Estado: ").append(netIface.isUp() ? "ACTIVA (UP)" : "INACTIVA (DOWN)").append("\n");
                sb.append(" - Loopback: ").append(netIface.isLoopback() ? "Sí (Localhost)" : "No").append("\n");
                sb.append(" - MTU: ").append(netIface.getMTU()).append(" bytes\n");

                // Lectura de Dirección MAC
                byte[] mac = netIface.getHardwareAddress();
                if (mac != null) {
                    StringBuilder macSb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        macSb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? ":" : ""));
                    }
                    sb.append(" - Dirección MAC: ").append(macSb.toString()).append("\n");
                } else {
                    sb.append(" - Dirección MAC: No disponible / Interfaz Virtual\n");
                }

                // Lectura de Direcciones IPv4 y IPv6 con Máscara (Prefix CIDR)
                for (InterfaceAddress addr : netIface.getInterfaceAddresses()) {
                    InetAddress inet = addr.getAddress();
                    if (inet instanceof Inet4Address) {
                        sb.append(" - Dirección IPv4: ").append(inet.getHostAddress())
                          .append(" / Prefijo CIDR: /").append(addr.getNetworkPrefixLength()).append("\n");
                    } else if (inet instanceof Inet6Address) {
                        sb.append(" - Dirección IPv6: ").append(inet.getHostAddress().split("%")[0]).append("\n");
                    }
                }
            }

            // Pruebas de Conectividad, Latencia y Servicios DNS
            sb.append("\n--- PRUEBAS DE SALIDA Y SERVICIOS DE RED ---\n");
            InetAddress localHost = InetAddress.getLocalHost();
            sb.append(" - Hostname del Nodo: ").append(localHost.getHostName()).append("\n");

            long tiempoInicio = System.currentTimeMillis();
            boolean googlePing = InetAddress.getByName("8.8.8.8").isReachable(2000);
            long latencia = System.currentTimeMillis() - tiempoInicio;

            sb.append(" - Conexión a Internet / Gateway (8.8.8.8): ")
              .append(googlePing ? "OK (Latencia: ~" + latencia + " ms)" : "FALLÓ / TIMEOUT").append("\n");

            // Prueba de Resolución de Nombres (DNS)
            try {
                InetAddress dnsTest = InetAddress.getByName("google.com");
                sb.append(" - Servicio DNS: OPERATIVO (Resuelve google.com -> ").append(dnsTest.getHostAddress()).append(")\n");
            } catch (Exception e) {
                sb.append(" - Servicio DNS: ERROR DE RESOLUCIÓN (No se pudo resolver el dominio)\n");
            }

        } catch (Exception e) {
            sb.append("ERROR durante la inspección de red: ").append(e.getMessage());
        }

        return sb.toString();
    }

    // ------------------------------------------------------------------
    //  REPORTE DEL NODO Y LIMPIEZA
    // ------------------------------------------------------------------

    private static String obtenerReporteNodo() {
        try {
            String nombreEquipo = InetAddress.getLocalHost().getHostName();
            String ipLocal = InetAddress.getLocalHost().getHostAddress();
            String sistemaOperativo = System.getProperty("os.name");
            String arquitectura = System.getProperty("os.arch");
            String usuario = System.getProperty("user.name");

            long memoriaLibreMB =
                Runtime.getRuntime().freeMemory() / (1024 * 1024);

            long memoriaTotalMB =
                Runtime.getRuntime().totalMemory() / (1024 * 1024);

            return "ÉXITO [REPORTE DEL NODO]\n"
                + "==============================\n"
                + "Nombre del equipo: " + nombreEquipo + "\n"
                + "Dirección IP: " + ipLocal + "\n"
                + "Usuario: " + usuario + "\n"
                + "Sistema operativo: " + sistemaOperativo + "\n"
                + "Arquitectura: " + arquitectura + "\n"
                + "RAM JVM usada: "
                + (memoriaTotalMB - memoriaLibreMB)
                + " MB de "
                + memoriaTotalMB + " MB\n"
                + resumenDiscos() + "\n"
                + "Estado del nodo: OPERATIVO";

        } catch (Exception e) {
            return "ERROR en reporte del nodo: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    //  PING AL GATEWAY REAL Y A INTERNET (8.8.8.8) POR SEPARADO
    // ------------------------------------------------------------------

    private static final String DESTINO_INTERNET = "8.8.8.8";
    private static final int PINGS_POR_DESTINO = 4;
    private static final int TIMEOUT_PING_MS = 1000;
    private static final Pattern PATRON_IPV4 =
            Pattern.compile("^(\\d{1,3})(\\.\\d{1,3}){3}$");
    // "time=12.3 ms", "time<1ms", "tiempo=12ms", "tiempo<1m" (Windows en español):
    // el número va tras '=' o '<'.
    private static final Pattern PATRON_TIEMPO_PING =
            Pattern.compile("[=<]\\s*([0-9]+(?:[.,][0-9]+)?)\\s*ms?", Pattern.CASE_INSENSITIVE);

    /** Estadísticas de una serie de pings contra un destino. */
    private static final class ResultadoPing {
        final String destino;
        final int enviados;
        int recibidos;
        final List<Double> tiempos = new ArrayList<>();
        String metodo = "ping del sistema";

        ResultadoPing(String destino, int enviados) {
            this.destino = destino;
            this.enviados = enviados;
        }

        boolean responde() { return recibidos > 0; }
        int perdidaPorcentaje() {
            return enviados == 0 ? 100
                    : (int) Math.round((enviados - recibidos) * 100.0 / enviados);
        }
        double minimo() { return tiempos.stream().mapToDouble(d -> d).min().orElse(-1); }
        double maximo() { return tiempos.stream().mapToDouble(d -> d).max().orElse(-1); }
        double promedio() { return tiempos.stream().mapToDouble(d -> d).average().orElse(-1); }
        /** Variación media entre respuestas consecutivas (jitter). */
        double jitter() {
            if (tiempos.size() < 2) return -1;
            double suma = 0;
            for (int i = 1; i < tiempos.size(); i++) suma += Math.abs(tiempos.get(i) - tiempos.get(i - 1));
            return suma / (tiempos.size() - 1);
        }
    }

    private static String probarConectividadGateway() {
        return probarConectividad(PINGS_POR_DESTINO, "PRUEBA DE CONECTIVIDAD");
    }

    /** Ping al gateway y a Internet con {@code cantidad} muestras por destino. */
    private static String probarConectividad(int cantidad, String titulo) {
        try {
            String gateway = detectarGateway();

            // Los dos destinos se miden en paralelo para no sumar sus timeouts.
            CompletableFuture<ResultadoPing> futuroGw = gateway == null ? null
                    : CompletableFuture.supplyAsync(
                            () -> hacerPing(gateway, cantidad, TIMEOUT_PING_MS));
            CompletableFuture<ResultadoPing> futuroNet = CompletableFuture.supplyAsync(
                    () -> hacerPing(DESTINO_INTERNET, cantidad, TIMEOUT_PING_MS));

            ResultadoPing gw = futuroGw == null ? null : futuroGw.get();
            ResultadoPing net = futuroNet.get();

            StringBuilder detalle = new StringBuilder();
            detalle.append("\n--- ").append(titulo).append(" ---\n");
            if (gw == null) {
                detalle.append("Gateway (puerta de enlace): NO DETECTADO "
                        + "(no se pudo leer la tabla de rutas del equipo)\n");
            } else {
                detalle.append("Gateway (puerta de enlace): ").append(gateway).append("\n");
                detalle.append(detallePing(gw));
            }
            detalle.append("Internet (").append(DESTINO_INTERNET).append("):\n");
            detalle.append(detallePing(net));

            String resumen = resumenConectividad(gateway, gw, net);
            return resumen + detalle.toString().stripTrailing();

        } catch (Exception e) {
            return "ERROR de Red: " + e.getMessage();
        }
    }

    /** Primera línea de la respuesta: conserva los prefijos "RED OK" / "ALERTA RED". */
    private static String resumenConectividad(String gateway, ResultadoPing gw, ResultadoPing net) {
        boolean netOk = net.responde();
        boolean gwOk = gw != null && gw.responde();
        boolean hayPerdida = (gw != null && gwOk && gw.perdidaPorcentaje() > 0)
                || (netOk && net.perdidaPorcentaje() > 0);

        if (gw != null && !gwOk && !netOk) {
            return "ALERTA RED: Sin conectividad. No responde el gateway (" + gateway
                    + ") ni Internet (" + DESTINO_INTERNET + "). Revise cable/Wi-Fi y adaptador.";
        }
        if (gw == null && !netOk) {
            return "ALERTA RED: Sin salida a Internet (" + DESTINO_INTERNET
                    + ") y no se detectó gateway. Es probable que el equipo no tenga red.";
        }
        if (gwOk && !netOk) {
            return "ALERTA RED: El gateway (" + gateway + ") responde pero no hay salida a Internet ("
                    + DESTINO_INTERNET + "). Falla del proveedor/enlace externo, o 8.8.8.8 bloqueado.";
        }
        if (hayPerdida) {
            return "ALERTA RED: Hay pérdida de paquetes (gateway: "
                    + (gwOk ? gw.perdidaPorcentaje() + "%" : "n/d")
                    + ", Internet: " + net.perdidaPorcentaje() + "%).";
        }
        if (gw != null && !gwOk) {
            return "RED OK: Hay salida a Internet, pero el gateway (" + gateway
                    + ") no responde a ping (muchos routers bloquean ICMP).";
        }
        if (gw == null) {
            return "RED OK: Hay salida a Internet (" + DESTINO_INTERNET
                    + "); no se pudo determinar el gateway.";
        }
        return "RED OK: Gateway (" + gateway + ") e Internet (" + DESTINO_INTERNET
                + ") responden sin pérdida de paquetes.";
    }

    private static String detallePing(ResultadoPing r) {
        StringBuilder sb = new StringBuilder();
        sb.append("  Resultado: ").append(r.responde() ? "RESPONDE" : "SIN RESPUESTA")
          .append(" | Enviados: ").append(r.enviados)
          .append(" | Recibidos: ").append(r.recibidos)
          .append(" | Pérdida: ").append(r.perdidaPorcentaje()).append("%\n");
        if (!r.tiempos.isEmpty()) {
            sb.append("  Latencia (ms): mín ").append(fmt1(r.minimo()))
              .append(" | prom ").append(fmt1(r.promedio()))
              .append(" | máx ").append(fmt1(r.maximo())).append("\n");
            // El jitter solo tiene sentido con una serie larga (ping extendido).
            if (r.enviados >= PINGS_EXTENDIDOS && r.jitter() >= 0) {
                sb.append("  Jitter (variación entre respuestas): ").append(fmt1(r.jitter())).append(" ms\n");
            }
        }
        sb.append("  Método: ").append(r.metodo).append("\n");
        return sb.toString();
    }

    /** Ping con el comando del sistema; si no existe, usa Java como respaldo. */
    private static ResultadoPing hacerPing(String destino, int cantidad, int timeoutMs) {
        ResultadoPing r = pingSistema(destino, cantidad, timeoutMs);
        return r != null ? r : pingJava(destino, cantidad, timeoutMs);
    }

    /** @return null si el comando ping no se pudo ejecutar en este equipo. */
    private static ResultadoPing pingSistema(String destino, int cantidad, int timeoutMs) {
        String so = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] comando;
        if (so.contains("win")) {
            comando = new String[]{"ping", "-n", String.valueOf(cantidad),
                    "-w", String.valueOf(timeoutMs), destino};
        } else if (so.contains("mac")) {
            comando = new String[]{"ping", "-c", String.valueOf(cantidad),
                    "-W", String.valueOf(timeoutMs), destino};
        } else {
            comando = new String[]{"ping", "-c", String.valueOf(cantidad),
                    "-W", String.valueOf(Math.max(1, (timeoutMs + 999) / 1000)), destino};
        }

        long limite = (long) cantidad * (timeoutMs + 1000L) + 3000L;
        SalidaComando salida = ejecutarComando(limite, comando);
        if (salida == null) return null;

        return interpretarPing(salida.texto, destino, cantidad);
    }

    /**
     * Interpreta la salida del comando ping sin depender del idioma del
     * sistema: cada respuesta recibida contiene "TTL=" (un "host de destino
     * inaccesible" no la tiene, así que no cuenta como recibida).
     */
    static ResultadoPing interpretarPing(String texto, String destino, int cantidad) {
        ResultadoPing r = new ResultadoPing(destino, cantidad);
        for (String linea : texto.split("\\R")) {
            if (!linea.toLowerCase(Locale.ROOT).contains("ttl=")) continue;
            r.recibidos++;
            Matcher m = PATRON_TIEMPO_PING.matcher(linea);
            if (!m.find()) continue;
            double ms = Double.parseDouble(m.group(1).replace(',', '.'));
            if (m.group().startsWith("<")) ms = Math.min(ms, 0.5); // "<1ms": menos de 1 ms
            r.tiempos.add(ms);
        }
        return r;
    }

    private static ResultadoPing pingJava(String destino, int cantidad, int timeoutMs) {
        ResultadoPing r = new ResultadoPing(destino, cantidad);
        r.metodo = "Java isReachable (el comando ping no está disponible; menos fiable)";
        for (int i = 0; i < cantidad; i++) {
            long t0 = System.nanoTime();
            try {
                if (InetAddress.getByName(destino).isReachable(timeoutMs)) {
                    r.recibidos++;
                    r.tiempos.add((System.nanoTime() - t0) / 1_000_000.0);
                }
            } catch (Exception ignorado) {
                // cuenta como paquete perdido
            }
        }
        return r;
    }

    // ---- Gateway ----

    /** IPv4 del gateway predeterminado, o null si no se puede determinar. */
    private static String detectarGateway() {
        String gw = null;
        try {
            gw = GatewayOshi.ipv4();
        } catch (Exception | LinkageError ignorado) {
            // OSHI no disponible: se usa la tabla de rutas del sistema.
        }
        if (esIpv4Valida(gw)) return gw;

        gw = gatewayPorSistema();
        return esIpv4Valida(gw) ? gw : null;
    }

    private static boolean esIpv4Valida(String ip) {
        return ip != null && PATRON_IPV4.matcher(ip.trim()).matches()
                && !ip.trim().equals("0.0.0.0");
    }

    /** Lectura de la tabla de rutas sin OSHI. */
    private static String gatewayPorSistema() {
        String so = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (so.contains("win")) {
                // "route print -4": 0.0.0.0  0.0.0.0  <gateway>  <interfaz>  <métrica>
                SalidaComando r = ejecutarComando(8000, "route", "print", "-4");
                if (r == null) return null;
                Pattern p = Pattern.compile(
                        "^\\s*0\\.0\\.0\\.0\\s+0\\.0\\.0\\.0\\s+(\\S+)\\s+\\S+\\s+(\\d+)",
                        Pattern.MULTILINE);
                Matcher m = p.matcher(r.texto);
                String mejor = null;
                int mejorMetrica = Integer.MAX_VALUE;
                while (m.find()) {
                    int metrica = Integer.parseInt(m.group(2));
                    if (esIpv4Valida(m.group(1)) && metrica < mejorMetrica) {
                        mejor = m.group(1);
                        mejorMetrica = metrica;
                    }
                }
                return mejor;
            }
            if (so.contains("mac")) {
                SalidaComando r = ejecutarComando(8000, "netstat", "-rn", "-f", "inet");
                if (r == null) return null;
                for (String linea : r.texto.split("\\R")) {
                    String[] c = linea.trim().split("\\s+");
                    if (c.length > 1 && c[0].equals("default") && esIpv4Valida(c[1])) return c[1];
                }
                return null;
            }
            // Linux: /proc/net/route (el gateway está en hexadecimal little-endian).
            Path ruta = Paths.get("/proc/net/route");
            if (Files.isReadable(ruta)) {
                for (String linea : Files.readAllLines(ruta)) {
                    String[] c = linea.trim().split("\\s+");
                    if (c.length > 7 && c[1].equals("00000000") && c[7].equals("00000000")) {
                        long v = Long.parseLong(c[2], 16);
                        return (v & 0xFF) + "." + ((v >> 8) & 0xFF) + "."
                                + ((v >> 16) & 0xFF) + "." + ((v >> 24) & 0xFF);
                    }
                }
            }
        } catch (Exception ignorado) {
            // se devuelve null
        }
        return null;
    }

    /** Único punto del gateway que usa OSHI (se carga solo si hace falta). */
    private static final class GatewayOshi {
        static String ipv4() {
            return new SystemInfo().getOperatingSystem()
                    .getNetworkParams().getIpv4DefaultGateway();
        }

        static String[] dns() {
            return new SystemInfo().getOperatingSystem()
                    .getNetworkParams().getDnsServers();
        }
    }

    /** Lecturas de red con OSHI (se cargan solo si hacen falta). */
    private static final class RedOshi {

        /** Contadores por interfaz: {rx, tx, velocidad bps, errores, descartes}. */
        static java.util.Map<String, long[]> contadores() {
            java.util.Map<String, long[]> m = new java.util.LinkedHashMap<>();
            for (oshi.hardware.NetworkIF n : new SystemInfo().getHardware().getNetworkIFs(false)) {
                n.updateAttributes();
                String nombre = n.getDisplayName() == null || n.getDisplayName().isBlank()
                        ? n.getName() : n.getDisplayName();
                if (m.containsKey(nombre)) nombre = nombre + " [" + n.getName() + "]";
                m.put(nombre, new long[]{n.getBytesRecv(), n.getBytesSent(), n.getSpeed(),
                        n.getInErrors() + n.getOutErrors(), n.getInDrops()});
            }
            return m;
        }

        private static String direccion(byte[] bytes) {
            try {
                return InetAddress.getByAddress(bytes).getHostAddress().split("%")[0];
            } catch (Exception e) {
                return "?";
            }
        }

        private static String proceso(oshi.software.os.OperatingSystem os, java.util.Map<Integer, String> cache,
                int pid) {
            if (pid <= 0) return "sin proceso asociado";
            return cache.computeIfAbsent(pid, k -> {
                oshi.software.os.OSProcess p = os.getProcess(k);
                return (p == null ? "proceso desconocido" : p.getName()) + " (PID " + k + ")";
            });
        }

        /** @return null si el sistema no devolvió ninguna conexión. */
        static String conexiones() {
            oshi.software.os.OperatingSystem os = new SystemInfo().getOperatingSystem();
            List<oshi.software.os.InternetProtocolStats.IPConnection> todas =
                    os.getInternetProtocolStats().getConnections();
            if (todas == null || todas.isEmpty()) return null;

            java.util.Map<String, Integer> porEstado = new java.util.TreeMap<>();
            int udp = 0;
            List<oshi.software.os.InternetProtocolStats.IPConnection> establecidas = new ArrayList<>();
            List<oshi.software.os.InternetProtocolStats.IPConnection> escucha = new ArrayList<>();
            for (oshi.software.os.InternetProtocolStats.IPConnection c : todas) {
                if (c.getType() != null && c.getType().startsWith("udp")) {
                    udp++;
                    continue;
                }
                porEstado.merge(String.valueOf(c.getState()), 1, Integer::sum);
                if (c.getState() == oshi.software.os.InternetProtocolStats.TcpState.ESTABLISHED) {
                    establecidas.add(c);
                } else if (c.getState() == oshi.software.os.InternetProtocolStats.TcpState.LISTEN) {
                    escucha.add(c);
                }
            }
            establecidas.sort((a, b) -> direccion(a.getForeignAddress())
                    .compareTo(direccion(b.getForeignAddress())));
            escucha.sort((a, b) -> Integer.compare(a.getLocalPort(), b.getLocalPort()));

            java.util.Map<Integer, String> cache = new java.util.HashMap<>();
            StringBuilder sb = new StringBuilder("ÉXITO [CONEXIONES ACTIVAS]\n");
            sb.append("TCP: ");
            if (porEstado.isEmpty()) {
                sb.append("ninguna");
            } else {
                boolean primero = true;
                for (java.util.Map.Entry<String, Integer> e : porEstado.entrySet()) {
                    if (!primero) sb.append(", ");
                    sb.append(e.getValue()).append(" ").append(e.getKey());
                    primero = false;
                }
            }
            sb.append(" | UDP: ").append(udp).append(" sockets");

            int limiteEstab = 25;
            sb.append("\n--- Conexiones TCP establecidas (")
              .append(Math.min(limiteEstab, establecidas.size())).append(" de ")
              .append(establecidas.size()).append(") ---");
            for (int i = 0; i < establecidas.size() && i < limiteEstab; i++) {
                oshi.software.os.InternetProtocolStats.IPConnection c = establecidas.get(i);
                sb.append("\n  ").append(c.getType()).append(" ")
                  .append(direccion(c.getLocalAddress())).append(":").append(c.getLocalPort())
                  .append(" -> ").append(direccion(c.getForeignAddress())).append(":").append(c.getForeignPort())
                  .append(" | ").append(proceso(os, cache, c.getowningProcessId()));
            }

            int limiteEscucha = 20;
            sb.append("\n--- Puertos TCP en escucha (")
              .append(Math.min(limiteEscucha, escucha.size())).append(" de ")
              .append(escucha.size()).append(") ---");
            for (int i = 0; i < escucha.size() && i < limiteEscucha; i++) {
                oshi.software.os.InternetProtocolStats.IPConnection c = escucha.get(i);
                sb.append("\n  ").append(c.getType()).append(" ")
                  .append(direccion(c.getLocalAddress())).append(":").append(c.getLocalPort())
                  .append(" | ").append(proceso(os, cache, c.getowningProcessId()));
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------
    //  FLUSH DNS REAL
    // ------------------------------------------------------------------

    private static String purgarCacheDns() {
        String so = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        List<String[]> intentos = new ArrayList<>();
        if (so.contains("win")) {
            intentos.add(new String[]{"ipconfig", "/flushdns"});
        } else if (so.contains("mac")) {
            intentos.add(new String[]{"dscacheutil", "-flushcache"});
        } else {
            intentos.add(new String[]{"resolvectl", "flush-caches"});
            intentos.add(new String[]{"systemd-resolve", "--flush-caches"});
            intentos.add(new String[]{"nscd", "-i", "hosts"});
        }

        String ultimoFallo = null;
        for (String[] comando : intentos) {
            String texto = String.join(" ", comando);
            SalidaComando r = ejecutarComando(15000, comando);
            if (r == null) continue; // comando inexistente: probar el siguiente

            if (r.codigo == 0) {
                // macOS: además hay que avisar al demonio mDNSResponder.
                String extra = "";
                if (so.contains("mac")) {
                    SalidaComando hup = ejecutarComando(8000, "killall", "-HUP", "mDNSResponder");
                    extra = (hup != null && hup.codigo == 0)
                            ? " + killall -HUP mDNSResponder"
                            : " (mDNSResponder no pudo reiniciarse: requiere sudo)";
                }
                return "ÉXITO: Caché DNS del sistema purgada con '" + texto + "'" + extra + "."
                        + salidaBreve(r.texto)
                        + "\nNOTA: este comando solo purga la caché DNS; no reinicia servicios "
                        + "ni restablece sockets.";
            }
            ultimoFallo = "'" + texto + "' terminó con código " + r.codigo + salidaBreve(r.texto);
        }

        if (ultimoFallo == null) {
            return "ADVERTENCIA: No se purgó ninguna caché DNS: este equipo no tiene un servicio "
                    + "de caché DNS administrable por comando (en Linux: systemd-resolved o nscd).";
        }
        return "ADVERTENCIA: No se pudo purgar la caché DNS. " + ultimoFallo
                + "\nCausa habitual: el agente no se ejecuta con permisos de administrador/root. "
                + "Ejecútelo como Administrador y repita la tarea.";
    }

    private static String salidaBreve(String texto) {
        String t = texto == null ? "" : texto.strip();
        if (t.isEmpty()) return "";
        return "\nSalida: " + (t.length() > 300 ? t.substring(0, 300) + "..." : t.replace("\r", ""));
    }

    // ------------------------------------------------------------------
    //  OPTIMIZAR MEMORIA (honesto: mide antes y después)
    // ------------------------------------------------------------------

    private static String optimizarMemoria() {
        Runtime rt = Runtime.getRuntime();
        long heapAntes = rt.totalMemory() - rt.freeMemory();
        long[] ramAntes = ramSistema();

        System.gc();
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.gc();

        long heapDespues = rt.totalMemory() - rt.freeMemory();
        long[] ramDespues = ramSistema();

        StringBuilder sb = new StringBuilder();
        sb.append("ÉXITO [OPTIMIZACIÓN DE MEMORIA]\n");
        sb.append("Alcance: el agente solo puede liberar la memoria de su propia JVM. "
                + "No cierra procesos ni vacía la memoria de otras aplicaciones.\n");
        sb.append("Memoria del agente (heap JVM): ").append(formatoBytes(heapAntes))
          .append(" -> ").append(formatoBytes(heapDespues))
          .append(" (liberado: ").append(formatoBytes(Math.max(0, heapAntes - heapDespues))).append(")\n");

        if (ramAntes == null || ramDespues == null) {
            sb.append("RAM del sistema: no disponible (OSHI no está en el classpath de este nodo).");
            return sb.toString();
        }

        long total = ramDespues[0];
        long variacion = ramDespues[1] - ramAntes[1];
        double uso = total > 0 ? (total - ramDespues[1]) * 100.0 / total : 0;
        sb.append("RAM del sistema (disponible): ").append(formatoBytes(ramAntes[1]))
          .append(" -> ").append(formatoBytes(ramDespues[1]))
          .append(" (variación: ").append(variacion >= 0 ? "+" : "-")
          .append(formatoBytes(Math.abs(variacion)))
          .append("; no atribuible al agente, otras aplicaciones también cambian la RAM)\n");
        sb.append("Uso de RAM actual: ").append(fmt1(uso)).append("% de ")
          .append(formatoBytes(total)).append("\n");

        if (uso >= 85) {
            sb.append("Conclusión: la RAM sigue alta. Para liberarla de verdad hay que cerrar "
                    + "o reiniciar los procesos que más consumen:\n");
        } else {
            sb.append("Conclusión: la RAM está en niveles normales; no se requiere acción. "
                    + "Procesos que más memoria usan:\n");
        }
        try {
            sb.append(LectorHardware.topProcesosPorMemoria(3));
        } catch (Exception | LinkageError e) {
            sb.append("  (lista de procesos no disponible)");
        }
        return sb.toString().stripTrailing();
    }

    /** @return {total, disponible} en bytes, o null si OSHI no está disponible. */
    private static long[] ramSistema() {
        try {
            GlobalMemory m = new SystemInfo().getHardware().getMemory();
            return new long[]{m.getTotal(), m.getAvailable()};
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    //  DISCOS: todas las unidades (no depende de OSHI)
    // ------------------------------------------------------------------

    private static final Set<String> SISTEMAS_ARCHIVOS_VIRTUALES = Set.of(
            "tmpfs", "devtmpfs", "proc", "sysfs", "cgroup", "cgroup2", "overlay",
            "squashfs", "devpts", "securityfs", "debugfs", "tracefs", "configfs",
            "fusectl", "pstore", "bpf", "autofs", "mqueue", "hugetlbfs",
            "binfmt_misc", "ramfs", "efivarfs", "nsfs", "rpc_pipefs");

    private static final class Unidad {
        final String etiqueta;
        final String tipo;
        final long total;
        final long libre;

        Unidad(String etiqueta, String tipo, long total, long libre) {
            this.etiqueta = etiqueta;
            this.tipo = tipo;
            this.total = total;
            this.libre = libre;
        }

        long usado() { return total - libre; }
        double porcentaje() { return total > 0 ? usado() * 100.0 / total : 0; }
    }

    private static List<Unidad> listarUnidades() {
        List<Unidad> unidades = new ArrayList<>();
        try {
            for (FileStore almacen : FileSystems.getDefault().getFileStores()) {
                try {
                    // Se omiten: solo lectura (DVD, snaps), virtuales y sin medio.
                    if (almacen.isReadOnly()) continue;
                    String tipo = almacen.type() == null ? "" : almacen.type();
                    if (SISTEMAS_ARCHIVOS_VIRTUALES.contains(tipo.toLowerCase(Locale.ROOT))) continue;
                    long total = almacen.getTotalSpace();
                    if (total <= 0) continue;
                    unidades.add(new Unidad(almacen.toString(), tipo, total, almacen.getUsableSpace()));
                } catch (IOException | RuntimeException ignorado) {
                    // unidad sin disco o inaccesible (lector vacío, red caída)
                }
            }
        } catch (RuntimeException ignorado) {
            // se intenta con las raíces
        }

        if (unidades.isEmpty()) {
            for (File raiz : File.listRoots()) {
                long total = raiz.getTotalSpace();
                if (total > 0) {
                    unidades.add(new Unidad(raiz.getPath(), "", total, raiz.getUsableSpace()));
                }
            }
        }
        return unidades;
    }

    private static String estadoUsoDisco(double porcentaje) {
        if (porcentaje >= 95) return "CRÍTICO";
        if (porcentaje >= 85) return "ALTO";
        if (porcentaje >= 70) return "MODERADO";
        return "NORMAL";
    }

    /** Sección "DISCO" con todas las unidades detectadas. */
    private static String seccionDiscos() {
        List<Unidad> unidades = listarUnidades();
        if (unidades.isEmpty()) {
            return "=== DISCO ===\nInformación no disponible.";
        }

        StringBuilder sb = new StringBuilder("=== DISCO (" + unidades.size()
                + (unidades.size() == 1 ? " unidad" : " unidades") + ") ===");
        double peor = 0;
        for (Unidad u : unidades) {
            peor = Math.max(peor, u.porcentaje());
            sb.append("\nUnidad: ").append(u.etiqueta);
            if (!u.tipo.isEmpty()) sb.append(" [").append(u.tipo).append("]");
            sb.append("\n  Disco total: ").append(formatoBytes(u.total))
              .append("\n  Disco usado: ").append(formatoBytes(u.usado()))
              .append("\n  Disco libre: ").append(formatoBytes(u.libre))
              .append("\n  Uso real: ").append(fmt2(u.porcentaje())).append("%")
              .append("\n  Estado: ").append(estadoUsoDisco(u.porcentaje()));
        }
        if (unidades.size() > 1) {
            sb.append("\nEstado general de discos: ").append(estadoUsoDisco(peor));
        }
        return sb.toString();
    }

    /** Resumen de una línea por unidad (para REPORTE_NODO). */
    private static String resumenDiscos() {
        List<Unidad> unidades = listarUnidades();
        if (unidades.isEmpty()) return "Discos: información no disponible";

        StringBuilder sb = new StringBuilder("Discos (" + unidades.size() + "):");
        for (Unidad u : unidades) {
            sb.append("\n  - ").append(u.etiqueta).append(": ")
              .append(formatoBytes(u.libre)).append(" libres de ")
              .append(formatoBytes(u.total)).append(" (uso ")
              .append(fmt1(u.porcentaje())).append("%)");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    //  FASE 2 - LECTURAS DE RED
    // ------------------------------------------------------------------

    // ---- Pérdida de paquetes y latencia (más muestras que el ping rápido) ----

    private static final int PINGS_EXTENDIDOS = 20;

    // ---- DNS y gateway ----

    private static final String NOMBRE_PRUEBA_DNS = "google.com";
    private static final String[] NOMBRES_RESOLUCION = {"google.com", "cloudflare.com"};
    private static final int TIMEOUT_DNS_MS = 2000;
    private static final Pattern PATRON_IPV4_SUELTA =
            Pattern.compile("\\b(\\d{1,3}(?:\\.\\d{1,3}){3})\\b");

    /** Respuesta de un servidor DNS a una consulta directa por UDP/53. */
    private static final class RespuestaDns {
        final boolean resuelve;
        final double ms;
        final String detalle;

        RespuestaDns(boolean resuelve, double ms, String detalle) {
            this.resuelve = resuelve;
            this.ms = ms;
            this.detalle = detalle;
        }
    }

    private static String diagnosticoDnsGateway() throws Exception {
        List<String> servidores = detectarServidoresDns();
        String gateway = detectarGateway();

        // Gateway, servidores DNS y resolución del sistema se miden en paralelo.
        CompletableFuture<ResultadoPing> futuroGw = gateway == null ? null
                : CompletableFuture.supplyAsync(() -> hacerPing(gateway, 2, TIMEOUT_PING_MS));
        List<CompletableFuture<RespuestaDns>> futurosDns = new ArrayList<>();
        for (String servidor : servidores) {
            futurosDns.add(CompletableFuture.supplyAsync(
                    () -> consultarDns(servidor, NOMBRE_PRUEBA_DNS)));
        }

        StringBuilder detalle = new StringBuilder();

        // --- Gateway ---
        detalle.append("=== GATEWAY ===\n");
        boolean gatewayResponde = false;
        if (gateway == null) {
            detalle.append("Gateway: NO DETECTADO (no se pudo leer la tabla de rutas del equipo)\n");
        } else {
            ResultadoPing gw = futuroGw.get();
            gatewayResponde = gw.responde();
            detalle.append("Gateway (puerta de enlace): ").append(gateway).append(" -> ")
                   .append(gatewayResponde
                           ? "RESPONDE a ping (" + gw.recibidos + "/" + gw.enviados
                               + ", prom " + fmt1(gw.promedio()) + " ms)"
                           : "no responde a ping (muchos routers bloquean ICMP)")
                   .append("\n");
        }

        // --- Servidores DNS configurados ---
        detalle.append("\n=== SERVIDORES DNS CONFIGURADOS (").append(servidores.size()).append(") ===\n");
        int servidoresOk = 0;
        if (servidores.isEmpty()) {
            detalle.append("No se detectaron servidores DNS configurados en este equipo.\n");
        }
        for (int i = 0; i < servidores.size(); i++) {
            RespuestaDns r = futurosDns.get(i).get();
            if (r.resuelve) servidoresOk++;
            detalle.append("  ").append(servidores.get(i)).append(": ").append(r.detalle);
            if (r.ms >= 0) detalle.append(" (").append(fmt1(r.ms)).append(" ms)");
            detalle.append("\n");
        }

        // --- Resolución con el resolutor del sistema ---
        detalle.append("\n=== RESOLUCIÓN DE NOMBRES (resolutor del sistema) ===\n");
        int resueltos = 0;
        for (String nombre : NOMBRES_RESOLUCION) {
            long t0 = System.nanoTime();
            try {
                InetAddress[] direcciones = InetAddress.getAllByName(nombre);
                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                resueltos++;
                detalle.append("  ").append(nombre).append(" -> ")
                       .append(direcciones[0].getHostAddress())
                       .append(" (").append(fmt1(ms)).append(" ms)\n");
            } catch (UnknownHostException e) {
                detalle.append("  ").append(nombre).append(" -> NO SE PUDO RESOLVER\n");
            }
        }
        detalle.append("Nota: el resolutor del sistema puede responder desde su caché; "
                + "la consulta directa a cada servidor DNS no la usa.");

        String resumen;
        int total = servidores.size();
        if (resueltos == 0) {
            resumen = "ALERTA DNS: El sistema no resuelve nombres"
                    + (total == 0 ? " y no hay servidores DNS configurados." : ".")
                    + (servidoresOk == 0 && total > 0 ? " Ningún servidor DNS responde a consulta directa." : "");
        } else if (total == 0) {
            resumen = "ADVERTENCIA DNS: El sistema resuelve nombres pero no se pudo leer la lista "
                    + "de servidores DNS configurados.";
        } else if (servidoresOk == 0) {
            resumen = "ADVERTENCIA DNS: El sistema resuelve (posible caché) pero ningún servidor DNS "
                    + "responde a consulta directa por UDP/53.";
        } else if (servidoresOk < total) {
            resumen = "ADVERTENCIA DNS: " + (total - servidoresOk) + " de " + total
                    + " servidores DNS no responden a consulta directa.";
        } else {
            resumen = "DNS OK: " + total + " servidor(es) DNS responden y el sistema resuelve nombres."
                    + (gateway == null ? " Gateway no detectado."
                       : (gatewayResponde ? "" : " El gateway no responde a ping (puede bloquear ICMP)."));
        }
        return resumen + "\n" + detalle;
    }

    /** Consulta directa (registro A) al puerto 53 UDP de un servidor DNS. */
    private static RespuestaDns consultarDns(String servidor, String nombre) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_DNS_MS);
            InetAddress direccion = InetAddress.getByName(servidor);

            int id = (int) (System.nanoTime() & 0xFFFF);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream datos = new DataOutputStream(bytes);
            datos.writeShort(id);
            datos.writeShort(0x0100);   // consulta estándar con recursión
            datos.writeShort(1);        // una pregunta
            datos.writeShort(0);
            datos.writeShort(0);
            datos.writeShort(0);
            for (String etiqueta : nombre.split("\\.")) {
                byte[] b = etiqueta.getBytes(StandardCharsets.US_ASCII);
                datos.writeByte(b.length);
                datos.write(b);
            }
            datos.writeByte(0);
            datos.writeShort(1);        // tipo A
            datos.writeShort(1);        // clase IN
            byte[] consulta = bytes.toByteArray();

            long t0 = System.nanoTime();
            socket.send(new DatagramPacket(consulta, consulta.length, direccion, 53));

            byte[] buffer = new byte[512];
            DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
            for (int intento = 0; intento < 5; intento++) {
                socket.receive(paquete); // SocketTimeoutException si no hay respuesta
                int idRespuesta = ((buffer[0] & 0xFF) << 8) | (buffer[1] & 0xFF);
                if (paquete.getLength() >= 12 && idRespuesta == id) {
                    double ms = (System.nanoTime() - t0) / 1_000_000.0;
                    int rcode = buffer[3] & 0x0F;
                    int respuestas = ((buffer[6] & 0xFF) << 8) | (buffer[7] & 0xFF);
                    if (rcode == 0 && respuestas > 0) {
                        return new RespuestaDns(true, ms, "RESPONDE y resuelve " + nombre);
                    }
                    return new RespuestaDns(false, ms, "responde pero no resuelve " + nombre
                            + " (" + descripcionRcode(rcode) + ")");
                }
            }
            return new RespuestaDns(false, -1, "respuesta DNS inválida");
        } catch (SocketTimeoutException e) {
            return new RespuestaDns(false, -1, "SIN RESPUESTA en " + TIMEOUT_DNS_MS
                    + " ms (servidor caído o UDP/53 bloqueado)");
        } catch (IOException e) {
            return new RespuestaDns(false, -1, "no se pudo consultar (" + e.getMessage() + ")");
        }
    }

    private static String descripcionRcode(int rcode) {
        switch (rcode) {
            case 0: return "sin registros";
            case 2: return "fallo del servidor";
            case 3: return "el dominio no existe";
            case 5: return "consulta rechazada";
            default: return "código DNS " + rcode;
        }
    }

    /** Servidores DNS configurados en el equipo (OSHI; si no, comandos del sistema). */
    private static List<String> detectarServidoresDns() {
        java.util.LinkedHashSet<String> ips = new java.util.LinkedHashSet<>();
        try {
            for (String s : GatewayOshi.dns()) {
                if (s != null && !s.isBlank()) ips.add(s.trim().split("%")[0]);
            }
        } catch (Exception | LinkageError ignorado) {
            // OSHI no disponible: se usan los comandos del sistema.
        }
        if (!ips.isEmpty()) return new ArrayList<>(ips);

        String so = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (so.contains("win")) {
                SalidaComando r = ejecutarComando(12000, "powershell", "-NoProfile", "-NonInteractive",
                        "-Command", "(Get-DnsClientServerAddress -AddressFamily IPv4).ServerAddresses");
                if (r != null && r.codigo == 0) extraerIpv4(r.texto, ips);
            } else if (so.contains("mac")) {
                SalidaComando r = ejecutarComando(8000, "scutil", "--dns");
                if (r != null) {
                    Matcher m = Pattern.compile("nameserver\\[\\d+\\]\\s*:\\s*(\\S+)").matcher(r.texto);
                    while (m.find()) ips.add(m.group(1).split("%")[0]);
                }
            } else {
                Path resolv = Paths.get("/etc/resolv.conf");
                if (Files.isReadable(resolv)) {
                    for (String linea : Files.readAllLines(resolv)) {
                        String[] c = linea.trim().split("\\s+");
                        if (c.length >= 2 && c[0].equals("nameserver")) ips.add(c[1].split("%")[0]);
                    }
                }
                // Con systemd-resolved, resolv.conf apunta a un stub local (127.0.0.53).
                SalidaComando r = ejecutarComando(8000, "resolvectl", "dns");
                if (r != null && r.codigo == 0) extraerIpv4(r.texto, ips);
            }
        } catch (Exception ignorado) {
            // se devuelve lo que se haya podido leer
        }
        return new ArrayList<>(ips);
    }

    private static void extraerIpv4(String texto, java.util.Collection<String> destino) {
        Matcher m = PATRON_IPV4_SUELTA.matcher(texto == null ? "" : texto);
        while (m.find()) {
            if (esIpv4Valida(m.group(1))) destino.add(m.group(1));
        }
    }

    // ---- Tráfico por interfaz ----

    /** Contadores acumulados por interfaz y la fuente de la que salieron. */
    private static final class ContadoresRed {
        final String fuente;
        // nombre -> {rx bytes, tx bytes, velocidad del enlace en bps (-1 = n/d), errores, descartes}
        final java.util.Map<String, long[]> datos;

        ContadoresRed(String fuente, java.util.Map<String, long[]> datos) {
            this.fuente = fuente;
            this.datos = datos;
        }
    }

    private static String traficoPorInterfaz() throws InterruptedException {
        ContadoresRed antes = leerContadoresRed();
        if (antes == null) {
            return "ADVERTENCIA: No se pudieron leer los contadores de tráfico por interfaz. "
                    + "Causa: OSHI no está disponible y este sistema no ofrece una lectura alternativa "
                    + "(Linux: /proc/net/dev; Windows: PowerShell Get-NetAdapterStatistics).";
        }

        long t0 = System.nanoTime();
        Thread.sleep(1000);
        ContadoresRed despues = leerContadoresRed();
        if (despues == null) {
            return "ADVERTENCIA: Se leyeron los contadores una vez pero falló la segunda lectura; "
                    + "no se pueden calcular los bytes por segundo.";
        }
        double segundos = Math.max(0.1, (System.nanoTime() - t0) / 1_000_000_000.0);

        StringBuilder filas = new StringBuilder();
        double rxTotalBps = 0;
        double txTotalBps = 0;
        int mostradas = 0;
        int sinTrafico = 0;

        for (java.util.Map.Entry<String, long[]> e : despues.datos.entrySet()) {
            long[] a = antes.datos.get(e.getKey());
            long[] b = e.getValue();
            if (a == null) continue;

            double rxBps = Math.max(0, b[0] - a[0]) / segundos;
            double txBps = Math.max(0, b[1] - a[1]) / segundos;
            if (b[0] == 0 && b[1] == 0) {
                sinTrafico++;
                continue;
            }
            rxTotalBps += rxBps;
            txTotalBps += txBps;
            mostradas++;

            filas.append("\nInterfaz: ").append(e.getKey())
                 .append("\n  Recibido (rx): total ").append(formatoTamano(b[0]))
                 .append(" | ahora ").append(formatoVelocidad(rxBps))
                 .append("\n  Enviado  (tx): total ").append(formatoTamano(b[1]))
                 .append(" | ahora ").append(formatoVelocidad(txBps));
            if (b[2] > 0) {
                filas.append("\n  Enlace: ").append(fmt1(b[2] / 1_000_000.0)).append(" Mbps");
            }
            if (b[3] > 0 || b[4] > 0) {
                filas.append("\n  Errores: ").append(b[3]).append(" | Paquetes descartados: ").append(b[4]);
            }
        }

        if (mostradas == 0) {
            return "ADVERTENCIA: Se leyeron los contadores (" + despues.fuente + ") pero ninguna "
                    + "interfaz registra tráfico acumulado.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("ÉXITO [TRÁFICO DE RED POR INTERFAZ]\n");
        sb.append("Muestra de ").append(fmt1(segundos)).append(" s | Fuente: ").append(despues.fuente)
          .append(" | Total ahora: rx ").append(formatoVelocidad(rxTotalBps))
          .append(", tx ").append(formatoVelocidad(txTotalBps));
        sb.append(filas);
        if (sinTrafico > 0) {
            sb.append("\n(").append(sinTrafico).append(" interfaz/interfaces sin tráfico no se muestran)");
        }
        sb.append("\nNota: los totales son los contadores del sistema desde el último arranque "
                + "de la interfaz; la velocidad es el promedio de la muestra.");
        return sb.toString();
    }

    /** @return null si no hay ninguna fuente de contadores disponible. */
    private static ContadoresRed leerContadoresRed() {
        try {
            java.util.Map<String, long[]> m = RedOshi.contadores();
            if (!m.isEmpty()) return new ContadoresRed("OSHI", m);
        } catch (Exception | LinkageError ignorado) {
            // OSHI no disponible: se usan las fuentes del sistema.
        }

        String so = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (so.contains("win")) {
                SalidaComando r = ejecutarComando(12000, "powershell", "-NoProfile", "-NonInteractive",
                        "-Command", "Get-NetAdapterStatistics | ForEach-Object { "
                        + "$_.Name + '|' + $_.ReceivedBytes + '|' + $_.SentBytes }");
                if (r == null || r.codigo != 0) return null;
                java.util.Map<String, long[]> m = new java.util.LinkedHashMap<>();
                for (String linea : r.texto.split("\\R")) {
                    String[] c = linea.trim().split("\\|");
                    if (c.length < 3) continue;
                    try {
                        m.put(c[0].trim(), new long[]{Long.parseLong(c[1].trim()),
                                Long.parseLong(c[2].trim()), -1, 0, 0});
                    } catch (NumberFormatException ignorado) {
                        // línea que no es un dato
                    }
                }
                return m.isEmpty() ? null : new ContadoresRed("PowerShell (Get-NetAdapterStatistics)", m);
            }

            Path proc = Paths.get("/proc/net/dev");
            if (so.contains("linux") && Files.isReadable(proc)) {
                java.util.Map<String, long[]> m = new java.util.LinkedHashMap<>();
                for (String linea : Files.readAllLines(proc)) {
                    int dosPuntos = linea.indexOf(':');
                    if (dosPuntos < 0) continue;
                    String nombre = linea.substring(0, dosPuntos).trim();
                    if (nombre.equals("lo")) continue;
                    String[] c = linea.substring(dosPuntos + 1).trim().split("\\s+");
                    if (c.length < 12) continue;
                    m.put(nombre, new long[]{Long.parseLong(c[0]), Long.parseLong(c[8]), -1,
                            Long.parseLong(c[2]) + Long.parseLong(c[10]), Long.parseLong(c[3])});
                }
                return m.isEmpty() ? null : new ContadoresRed("/proc/net/dev", m);
            }
        } catch (Exception ignorado) {
            // sin fuente alternativa
        }
        return null;
    }

    // ---- Prueba de puertos TCP ----

    private static final int MAX_PUERTOS_PRUEBA = 50;
    private static final int TIMEOUT_PUERTO_MS = 2000;

    private static final class ResultadoPuerto {
        final int puerto;
        final boolean abierto;
        final String estado;

        ResultadoPuerto(int puerto, boolean abierto, String estado) {
            this.puerto = puerto;
            this.abierto = abierto;
            this.estado = estado;
        }
    }

    /**
     * Prueba de conexión TCP desde este nodo hacia un host.
     * Parámetros: "host puertos", p. ej. "192.168.1.1 22,80,443" o "servidor 20-25".
     */
    private static String probarPuertos(String[] args) throws Exception {
        String uso = "Uso: PROBAR_PUERTOS <host> <puertos>  (ej.: 192.168.1.1 22,80,443 o 20-25).";
        if (args.length == 0) {
            return "ADVERTENCIA: Faltan el host y los puertos. " + uso;
        }

        String host = args[0];
        StringBuilder acumulado = new StringBuilder();
        for (int i = 1; i < args.length; i++) acumulado.append(args[i]);
        String textoPuertos = acumulado.toString();

        // Forma abreviada "host:puertos" (solo con un único ':', para no romper IPv6).
        int dosPuntos = host.indexOf(':');
        if (textoPuertos.isEmpty() && dosPuntos > 0 && dosPuntos == host.lastIndexOf(':')) {
            textoPuertos = host.substring(dosPuntos + 1);
            host = host.substring(0, dosPuntos);
        }
        if (textoPuertos.isEmpty()) {
            return "ADVERTENCIA: No se indicaron puertos. " + uso;
        }

        List<Integer> puertos;
        try {
            puertos = interpretarPuertos(textoPuertos);
        } catch (IllegalArgumentException e) {
            return "ADVERTENCIA: " + e.getMessage() + " " + uso;
        }

        final InetAddress destino;
        try {
            destino = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return "ADVERTENCIA: No se pudo resolver el host '" + host + "' desde este nodo. "
                    + "Revise el nombre o los servidores DNS del nodo.";
        }

        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(Math.min(16, puertos.size()));
        List<java.util.concurrent.Future<ResultadoPuerto>> futuros = new ArrayList<>();
        List<ResultadoPuerto> resultados = new ArrayList<>();
        try {
            for (int puerto : puertos) {
                futuros.add(pool.submit(() -> probarPuerto(destino, puerto)));
            }
            for (java.util.concurrent.Future<ResultadoPuerto> f : futuros) {
                resultados.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }

        int abiertos = 0;
        StringBuilder detalle = new StringBuilder();
        for (ResultadoPuerto r : resultados) {
            if (r.abierto) abiertos++;
            String servicio = nombreServicio(r.puerto);
            detalle.append("\n  Puerto ").append(r.puerto)
                   .append(servicio.isEmpty() ? "" : " (" + servicio + ")")
                   .append(": ").append(r.estado);
        }

        String destinoTexto = host.equals(destino.getHostAddress()) ? host
                : host + " (" + destino.getHostAddress() + ")";
        String resumen = abiertos == resultados.size()
                ? "ÉXITO [PRUEBA DE PUERTOS]: " + destinoTexto + ": "
                    + abiertos + " de " + resultados.size() + " puertos abiertos."
                : "ALERTA PUERTOS: " + destinoTexto + ": " + abiertos + " de " + resultados.size()
                    + " puertos abiertos; " + (resultados.size() - abiertos) + " cerrados o sin respuesta.";

        return resumen + detalle
                + "\nPrueba TCP hecha desde este nodo. CERRADO = el host rechazó la conexión (no hay servicio "
                + "escuchando); SIN RESPUESTA = firewall que descarta paquetes, host apagado o sin ruta.";
    }

    private static ResultadoPuerto probarPuerto(InetAddress destino, int puerto) {
        long t0 = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(destino, puerto), TIMEOUT_PUERTO_MS);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return new ResultadoPuerto(puerto, true, "ABIERTO (" + ms + " ms)");
        } catch (SocketTimeoutException e) {
            return new ResultadoPuerto(puerto, false, "SIN RESPUESTA (timeout de "
                    + TIMEOUT_PUERTO_MS / 1000 + " s)");
        } catch (NoRouteToHostException e) {
            return new ResultadoPuerto(puerto, false, "SIN RUTA AL HOST");
        } catch (ConnectException e) {
            String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (m.contains("refused")) {
                return new ResultadoPuerto(puerto, false, "CERRADO (conexión rechazada)");
            }
            if (m.contains("timed out")) {
                return new ResultadoPuerto(puerto, false, "SIN RESPUESTA (tiempo agotado)");
            }
            return new ResultadoPuerto(puerto, false, "NO CONECTÓ (" + e.getMessage() + ")");
        } catch (IOException e) {
            return new ResultadoPuerto(puerto, false, "ERROR (" + e.getMessage() + ")");
        }
    }

    /** Convierte "22,80,443" o "20-25,80" en una lista ordenada y sin repetidos. */
    private static List<Integer> interpretarPuertos(String texto) {
        java.util.TreeSet<Integer> puertos = new java.util.TreeSet<>();
        for (String trozo : texto.split(",")) {
            if (trozo.isEmpty()) continue;
            try {
                int guion = trozo.indexOf('-');
                int inicio;
                int fin;
                if (guion > 0) {
                    inicio = Integer.parseInt(trozo.substring(0, guion));
                    fin = Integer.parseInt(trozo.substring(guion + 1));
                } else {
                    inicio = Integer.parseInt(trozo);
                    fin = inicio;
                }
                if (inicio < 1 || fin > 65535 || fin < inicio) {
                    throw new IllegalArgumentException("Puerto o rango fuera de 1-65535: '" + trozo + "'.");
                }
                if (fin - inicio + 1 > MAX_PUERTOS_PRUEBA) {
                    throw new IllegalArgumentException("Máximo " + MAX_PUERTOS_PRUEBA
                            + " puertos por prueba (el rango '" + trozo + "' es mayor).");
                }
                for (int p = inicio; p <= fin; p++) puertos.add(p);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Puerto inválido: '" + trozo + "'.");
            }
            if (puertos.size() > MAX_PUERTOS_PRUEBA) {
                throw new IllegalArgumentException("Máximo " + MAX_PUERTOS_PRUEBA + " puertos por prueba.");
            }
        }
        if (puertos.isEmpty()) {
            throw new IllegalArgumentException("No se indicó ningún puerto válido.");
        }
        return new ArrayList<>(puertos);
    }

    private static String nombreServicio(int puerto) {
        switch (puerto) {
            case 21: return "FTP";
            case 22: return "SSH";
            case 23: return "Telnet";
            case 25: return "SMTP";
            case 53: return "DNS";
            case 80: return "HTTP";
            case 110: return "POP3";
            case 135: return "RPC";
            case 139: return "NetBIOS";
            case 143: return "IMAP";
            case 443: return "HTTPS";
            case 445: return "SMB";
            case 993: return "IMAPS";
            case 995: return "POP3S";
            case 1433: return "SQL Server";
            case 3306: return "MySQL";
            case 3389: return "RDP";
            case 5000: return "Servidor Central";
            case 5432: return "PostgreSQL";
            case 5900: return "VNC";
            case 8080: return "HTTP alterno";
            default: return "";
        }
    }

    // ---- Conexiones activas ----

    private static String conexionesActivas() {
        try {
            String texto = RedOshi.conexiones();
            if (texto != null) return texto;
            return "ADVERTENCIA: El sistema no devolvió ninguna conexión. Es probable que el agente "
                    + "no tenga permisos para leer la tabla de conexiones (ejecútelo como Administrador/root).";
        } catch (Exception | LinkageError e) {
            return conexionesBasico(e);
        }
    }

    /** Respaldo sin OSHI: muestra la salida de netstat / ss. */
    private static String conexionesBasico(Throwable motivo) {
        String so = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String[]> candidatos = new ArrayList<>();
        if (so.contains("win")) {
            candidatos.add(new String[]{"netstat", "-ano"});
        } else if (so.contains("mac")) {
            candidatos.add(new String[]{"netstat", "-an", "-p", "tcp"});
        } else {
            candidatos.add(new String[]{"ss", "-tunap"});
            candidatos.add(new String[]{"netstat", "-tunap"});
        }

        for (String[] cmd : candidatos) {
            SalidaComando r = ejecutarComando(15000, cmd);
            if (r == null || r.codigo != 0 || r.texto.isBlank()) continue;

            StringBuilder sb = new StringBuilder("ÉXITO [CONEXIONES ACTIVAS - MODO BÁSICO]\n");
            sb.append("Salida de '").append(String.join(" ", cmd)).append("':");
            int mostradas = 0;
            int total = 0;
            for (String linea : r.texto.split("\\R")) {
                if (linea.isBlank()) continue;
                total++;
                if (mostradas < 40) {
                    sb.append("\n").append(linea.stripTrailing());
                    mostradas++;
                }
            }
            if (total > mostradas) {
                sb.append("\n... (").append(total - mostradas).append(" líneas más no mostradas)");
            }
            sb.append("\nNOTA: OSHI no está disponible en este nodo (")
              .append(motivo.getClass().getSimpleName())
              .append("), por eso no hay resumen por estado ni nombre de proceso.");
            return sb.toString();
        }
        return "ADVERTENCIA: No se pudieron listar las conexiones: OSHI no está disponible ("
                + motivo.getClass().getSimpleName() + ") y el comando netstat/ss no se pudo ejecutar.";
    }

    // ------------------------------------------------------------------
    //  FASE 2 - LECTURAS DEL EQUIPO
    // ------------------------------------------------------------------

    private static final int TOP_PROCESOS_POR_DEFECTO = 10;
    private static final int TOP_PROCESOS_MAXIMO = 30;

    /** Un proceso en las listas "Top". cpu = NaN y ram = -1 cuando el dato no existe. */
    private static final class FilaProceso {
        final String nombre;
        final int pid;
        final double cpu;   // % del total del equipo
        final long ram;     // bytes

        FilaProceso(String nombre, int pid, double cpu, long ram) {
            this.nombre = nombre;
            this.pid = pid;
            this.cpu = cpu;
            this.ram = ram;
        }
    }

    /** Cantidad pedida como primer argumento ("TOP_CPU 5"); si no hay, el valor por defecto. */
    private static int cantidadPedida(String[] args) {
        if (args.length > 0) {
            try {
                int n = Integer.parseInt(args[0]);
                return Math.max(1, Math.min(TOP_PROCESOS_MAXIMO, n));
            } catch (NumberFormatException ignorado) {
                // se usa el valor por defecto
            }
        }
        return TOP_PROCESOS_POR_DEFECTO;
    }

    private static String topProcesos(boolean porCpu, String[] args) {
        int n = cantidadPedida(args);
        try {
            return LectorHardware.topProcesos(porCpu, n);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: La lectura de procesos fue interrumpida.";
        } catch (Exception | LinkageError e) {
            return topProcesosBasico(porCpu, n, e);
        }
    }

    /** Respaldo sin OSHI: CPU con la API de procesos de Java y RAM con tasklist / ps. */
    private static String topProcesosBasico(boolean porCpu, int n, Throwable motivo) {
        try {
            List<FilaProceso> memoria = procesosMemoriaSistema();
            List<FilaProceso> filas;
            String medicion;

            if (porCpu) {
                int nucleos = Math.max(1, Runtime.getRuntime().availableProcessors());
                java.util.Map<Long, Long> antes = new java.util.HashMap<>();
                ProcessHandle.allProcesses().forEach(ph ->
                        ph.info().totalCpuDuration().ifPresent(d -> antes.put(ph.pid(), d.toNanos())));
                long t0 = System.nanoTime();
                Thread.sleep(1000);
                final double segundos = Math.max(0.1, (System.nanoTime() - t0) / 1_000_000_000.0);

                java.util.Map<Integer, Long> ramPorPid = new java.util.HashMap<>();
                java.util.Map<Integer, String> nombrePorPid = new java.util.HashMap<>();
                if (memoria != null) {
                    for (FilaProceso f : memoria) {
                        ramPorPid.put(f.pid, f.ram);
                        nombrePorPid.put(f.pid, f.nombre);
                    }
                }

                filas = new ArrayList<>();
                ProcessHandle.allProcesses().forEach(ph -> {
                    Long previo = antes.get(ph.pid());
                    java.util.Optional<java.time.Duration> actual = ph.info().totalCpuDuration();
                    if (previo == null || actual.isEmpty()) return;
                    double cpu = (actual.get().toNanos() - previo) / (segundos * 1e9) * 100.0 / nucleos;
                    String nombre = ph.info().command().map(c -> {
                        Path f = Paths.get(c).getFileName();
                        return f == null ? c : f.toString();
                    }).orElse(nombrePorPid.getOrDefault((int) ph.pid(), "(sin acceso al nombre)"));
                    filas.add(new FilaProceso(nombre, (int) ph.pid(), Math.max(0, cpu),
                            ramPorPid.getOrDefault((int) ph.pid(), -1L)));
                });
                filas.sort((a, b) -> Double.compare(b.cpu, a.cpu));
                medicion = "Medición de " + fmt1(segundos) + " s sobre " + nucleos
                        + " núcleos lógicos; solo procesos a los que el agente tiene acceso.";
            } else {
                if (memoria == null) {
                    return "ADVERTENCIA: No se pudo leer la lista de procesos: OSHI no está disponible ("
                            + motivo.getClass().getSimpleName() + ") y el comando tasklist/ps no respondió.";
                }
                filas = new ArrayList<>(memoria);
                filas.sort((a, b) -> Long.compare(b.ram, a.ram));
                medicion = "Memoria física (RAM) que usa cada proceso.";
            }

            if (filas.isEmpty()) {
                return "ADVERTENCIA: No se pudo leer ningún proceso (sin permisos o sin datos de CPU).";
            }
            String titulo = "TOP " + Math.min(n, filas.size()) + " PROCESOS POR " + (porCpu ? "CPU" : "MEMORIA")
                    + " - MODO BÁSICO";
            return formatearTopProcesos(titulo, filas, n, medicion, -1)
                    + "\nNOTA: OSHI no está disponible en este nodo ("
                    + motivo.getClass().getSimpleName() + "); lectura con herramientas del sistema.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: La lectura de procesos fue interrumpida.";
        } catch (Exception e) {
            return "ERROR al leer procesos: " + e.getMessage();
        }
    }

    /** RAM por proceso con tasklist (Windows) o ps (Linux/macOS); null si no responde. */
    private static List<FilaProceso> procesosMemoriaSistema() {
        String so = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<FilaProceso> filas = new ArrayList<>();
        try {
            if (so.contains("win")) {
                SalidaComando r = ejecutarComando(15000, "tasklist", "/fo", "csv", "/nh");
                if (r == null || r.codigo != 0) return null;
                for (String linea : r.texto.split("\\R")) {
                    String t = linea.trim();
                    if (t.length() < 2 || t.charAt(0) != '"') continue;
                    // "nombre","pid","sesión","n.º sesión","uso de memoria"  (p. ej. "123,456 K")
                    String[] c = t.substring(1, t.length() - 1).split("\",\"");
                    if (c.length < 5) continue;
                    try {
                        String digitos = c[4].replaceAll("\\D", "");
                        if (digitos.isEmpty()) continue;
                        filas.add(new FilaProceso(c[0], Integer.parseInt(c[1].trim()), Double.NaN,
                                Long.parseLong(digitos) * 1024L));
                    } catch (NumberFormatException ignorado) {
                        // línea que no es un proceso
                    }
                }
            } else {
                SalidaComando r = ejecutarComando(15000, "ps", "-A", "-o", "pid=,rss=,comm=");
                if (r == null || r.codigo != 0) return null;
                for (String linea : r.texto.split("\\R")) {
                    String[] c = linea.trim().split("\\s+", 3);
                    if (c.length < 3) continue;
                    try {
                        filas.add(new FilaProceso(c[2], Integer.parseInt(c[0]), Double.NaN,
                                Long.parseLong(c[1]) * 1024L));
                    } catch (NumberFormatException ignorado) {
                        // línea que no es un proceso
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        return filas.isEmpty() ? null : filas;
    }

    /** Texto común de las listas "Top" (con OSHI y en modo básico). */
    private static String formatearTopProcesos(String titulo, List<FilaProceso> filas, int n,
            String medicion, long ramTotal) {
        StringBuilder sb = new StringBuilder("ÉXITO [").append(titulo).append("]\n");
        sb.append(medicion);
        int pos = 1;
        for (FilaProceso f : filas) {
            if (pos > n) break;
            sb.append("\n  ").append(pos++).append(". ").append(f.nombre)
              .append(" (PID ").append(f.pid).append("):");
            if (!Double.isNaN(f.cpu)) sb.append(" CPU ").append(fmt1(f.cpu)).append("%");
            if (f.ram >= 0) {
                if (!Double.isNaN(f.cpu)) sb.append(" |");
                sb.append(" RAM ").append(formatoTamano(f.ram));
                if (ramTotal > 0) {
                    sb.append(" (").append(fmt1(f.ram * 100.0 / ramTotal)).append("% de la RAM)");
                }
            }
        }
        return sb.toString();
    }

    // ---- Tiempo encendido ----

    private static String tiempoEncendido() {
        long segundos = -1;
        String fuente = "OSHI";
        try {
            segundos = LectorHardware.segundosEncendido();
        } catch (Exception | LinkageError ignorado) {
            // OSHI no disponible: se usa el sistema.
        }
        if (segundos <= 0) {
            segundos = segundosEncendidoPorSistema();
            fuente = "comando del sistema";
        }
        if (segundos <= 0) {
            return "ADVERTENCIA: No se pudo determinar el tiempo encendido: OSHI no está disponible "
                    + "y la lectura alternativa del sistema no respondió.";
        }

        java.time.ZonedDateTime arranque = java.time.ZonedDateTime.now().minusSeconds(segundos);
        StringBuilder sb = new StringBuilder("ÉXITO [TIEMPO ENCENDIDO]\n");
        sb.append("Encendido desde: ")
          .append(arranque.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n");
        sb.append("Tiempo encendido: ").append(formatoDuracion(segundos))
          .append(" (fuente: ").append(fuente).append(")");
        if (segundos >= 30L * 24 * 3600) {
            sb.append("\nAviso: lleva más de 30 días sin reiniciar; conviene reiniciar para aplicar "
                    + "actualizaciones y liberar recursos.");
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            sb.append("\nNota: en Windows con \"Inicio rápido\" activado, Apagar no reinicia este contador; "
                    + "solo Reiniciar lo hace.");
        }
        return sb.toString();
    }

    /** Segundos desde el arranque sin OSHI, o -1 si no se pudo leer. */
    private static long segundosEncendidoPorSistema() {
        String so = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (so.contains("win")) {
                SalidaComando r = ejecutarComando(12000, "powershell", "-NoProfile", "-NonInteractive",
                        "-Command", "[int]((Get-Date) - (Get-CimInstance Win32_OperatingSystem)"
                        + ".LastBootUpTime).TotalSeconds");
                if (r != null && r.codigo == 0) {
                    String digitos = r.texto.replaceAll("\\D", "");
                    if (!digitos.isEmpty()) return Long.parseLong(digitos);
                }
            } else if (so.contains("mac")) {
                SalidaComando r = ejecutarComando(8000, "sysctl", "-n", "kern.boottime");
                if (r != null) {
                    Matcher m = Pattern.compile("sec\\s*=\\s*(\\d+)").matcher(r.texto);
                    if (m.find()) return System.currentTimeMillis() / 1000 - Long.parseLong(m.group(1));
                }
            } else {
                Path ruta = Paths.get("/proc/uptime");
                if (Files.isReadable(ruta)) {
                    String[] c = Files.readString(ruta).trim().split("\\s+");
                    return (long) Double.parseDouble(c[0]);
                }
            }
        } catch (Exception ignorado) {
            // se devuelve -1
        }
        return -1;
    }

    // ---- Inventario ----

    private static String inventarioEquipo() {
        try {
            return LectorHardware.inventario();
        } catch (Exception | LinkageError e) {
            return inventarioBasico(e);
        }
    }

    /** Respaldo sin OSHI: datos de la JVM, discos y adaptadores de red. */
    private static String inventarioBasico(Throwable motivo) {
        try {
            StringBuilder sb = new StringBuilder("ÉXITO [INVENTARIO DEL EQUIPO - MODO BÁSICO]\n");
            sb.append("=== IDENTIFICACIÓN Y SOFTWARE ===\n");
            String nombre;
            try {
                nombre = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                nombre = "No disponible";
            }
            sb.append("Nombre del equipo: ").append(nombre).append("\n");
            sb.append("Sistema operativo: ").append(System.getProperty("os.name")).append(" ")
              .append(System.getProperty("os.version")).append(" (").append(System.getProperty("os.arch"))
              .append(")\n");
            sb.append("Usuario: ").append(System.getProperty("user.name")).append("\n");
            sb.append("Java del agente: ").append(System.getProperty("java.version")).append(" (")
              .append(System.getProperty("java.vendor")).append(")\n");

            sb.append("\n=== PROCESADOR Y MEMORIA ===\n");
            sb.append("Núcleos lógicos: ").append(Runtime.getRuntime().availableProcessors()).append("\n");
            java.lang.management.OperatingSystemMXBean mx =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (mx instanceof com.sun.management.OperatingSystemMXBean) {
                sb.append("RAM total: ")
                  .append(formatoTamano(((com.sun.management.OperatingSystemMXBean) mx).getTotalMemorySize()))
                  .append("\n");
            } else {
                sb.append("RAM total: No disponible\n");
            }

            sb.append("\n").append(resumenDiscos()).append("\n");

            sb.append("\n=== ADAPTADORES DE RED ACTIVOS ===");
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            int cuantos = 0;
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                StringBuilder ips = new StringBuilder();
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address) {
                        if (ips.length() > 0) ips.append(", ");
                        ips.append(ia.getAddress().getHostAddress());
                    }
                }
                byte[] mac = ni.getHardwareAddress();
                StringBuilder macTxt = new StringBuilder();
                if (mac != null) {
                    for (int i = 0; i < mac.length; i++) {
                        macTxt.append(String.format("%02X%s", mac[i], i < mac.length - 1 ? ":" : ""));
                    }
                }
                sb.append("\n  - ").append(ni.getDisplayName())
                  .append(" | MAC ").append(macTxt.length() > 0 ? macTxt : "n/d")
                  .append(" | IPv4 ").append(ips.length() > 0 ? ips : "n/d");
                cuantos++;
            }
            if (cuantos == 0) sb.append("\n  (ninguno)");

            sb.append("\n\nNOTA: OSHI no está disponible en este nodo (")
              .append(motivo.getClass().getSimpleName())
              .append("), por eso faltan fabricante, modelo, serie, BIOS, módulos de RAM, discos físicos, "
                      + "gráficos y aplicaciones instaladas. Agregue oshi-core, jna, jna-platform y "
                      + "slf4j-api al classpath para obtenerlos.");
            return sb.toString();
        } catch (Exception e) {
            return "ERROR en inventario: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    //  MUESTREO PARA EL MONITOREO CONTINUO
    // ------------------------------------------------------------------

    /** Una muestra puntual del equipo. NaN = el dato no existe o no se midió. */
    static final class Muestra {
        double cpu = Double.NaN;            // %
        double ram = Double.NaN;            // %
        double disco = Double.NaN;          // % de la unidad más llena
        String discoUnidad = "";
        double temperatura = Double.NaN;    // °C
        double latencia = Double.NaN;       // ms al gateway
        boolean latenciaIntentada;
        boolean latenciaSinRespuesta;
        String gateway;
        String fuente = "OSHI";
    }

    private static final long VIGENCIA_GATEWAY_MS = 5 * 60 * 1000L;
    private static String gatewayEnCache;
    private static long gatewayEnCacheMs;

    /** El gateway casi no cambia: se detecta cada 5 minutos, no en cada muestra. */
    private static synchronized String gatewayParaMuestreo() {
        long ahora = System.currentTimeMillis();
        if (gatewayEnCacheMs == 0 || ahora - gatewayEnCacheMs > VIGENCIA_GATEWAY_MS
                || gatewayEnCache == null && ahora - gatewayEnCacheMs > 30_000L) {
            gatewayEnCache = detectarGateway();
            gatewayEnCacheMs = ahora;
        }
        return gatewayEnCache;
    }

    /**
     * Toma CPU, RAM, disco, temperatura y (opcional) latencia al gateway.
     * Sin OSHI usa los datos de la JVM: CPU y RAM sí, temperatura no.
     */
    static Muestra tomarMuestra(boolean medirLatencia) {
        Muestra m = new Muestra();
        try {
            LectorHardware.muestraContinua(m);
        } catch (Exception | LinkageError e) {
            m.fuente = "JVM (OSHI no disponible)";
            m.cpu = Double.NaN;
            m.ram = Double.NaN;
            m.temperatura = Double.NaN;
            muestraBasica(m);
        }

        double peor = -1;
        for (Unidad u : listarUnidades()) {
            if (u.porcentaje() > peor) {
                peor = u.porcentaje();
                m.discoUnidad = u.etiqueta;
            }
        }
        if (peor >= 0) m.disco = peor;

        m.latenciaIntentada = medirLatencia;
        if (medirLatencia) {
            m.gateway = gatewayParaMuestreo();
            if (m.gateway != null) {
                ResultadoPing r = hacerPing(m.gateway, 1, TIMEOUT_PING_MS);
                if (!r.responde()) {
                    m.latenciaSinRespuesta = true;
                } else if (!r.tiempos.isEmpty()) {
                    m.latencia = r.promedio();
                }
            }
        }
        return m;
    }

    /** CPU y RAM del sistema con la API de la JVM (sin OSHI). */
    private static void muestraBasica(Muestra m) {
        java.lang.management.OperatingSystemMXBean mx =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if (mx instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sx = (com.sun.management.OperatingSystemMXBean) mx;
            double cpu = sx.getCpuLoad();
            if (cpu >= 0) m.cpu = cpu * 100.0;
            long total = sx.getTotalMemorySize();
            if (total > 0) m.ram = (total - sx.getFreeMemorySize()) * 100.0 / total;
        }
    }

    // ------------------------------------------------------------------
    //  UTILIDADES COMUNES
    // ------------------------------------------------------------------

    private static final class SalidaComando {
        final int codigo;
        final String texto;

        SalidaComando(int codigo, String texto) {
            this.codigo = codigo;
            this.texto = texto;
        }
    }

    /**
     * Ejecuta un comando del sistema y devuelve su código de salida y texto.
     *
     * @return null si el comando no existe o no se pudo iniciar.
     */
    private static SalidaComando ejecutarComando(long timeoutMs, String... comando) {
        try {
            Process proceso = new ProcessBuilder(comando).redirectErrorStream(true).start();
            proceso.getOutputStream().close();

            CompletableFuture<String> lectura = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(proceso.getInputStream().readAllBytes(), charsetConsola());
                } catch (IOException e) {
                    return "";
                }
            });

            if (!proceso.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                proceso.destroyForcibly();
                return new SalidaComando(-1, "Tiempo de espera agotado.");
            }
            String texto;
            try {
                texto = lectura.get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                texto = "";
            }
            return new SalidaComando(proceso.exitValue(), texto);

        } catch (IOException e) {
            return null; // el comando no existe en este equipo
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Codificación con la que la consola del sistema escribe su salida. */
    private static Charset charsetConsola() {
        for (String clave : new String[]{"stdout.encoding", "sun.stdout.encoding", "native.encoding"}) {
            String nombre = System.getProperty(clave);
            if (nombre != null) {
                try {
                    return Charset.forName(nombre);
                } catch (Exception ignorado) {
                    // probar la siguiente
                }
            }
        }
        return StandardCharsets.ISO_8859_1;
    }

    private static String fmt1(double v) { return String.format(Locale.ROOT, "%.1f", v); }

    private static String fmt2(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    private static String formatoBytes(long bytes) {
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1) return String.format(Locale.ROOT, "%.2f GB", gb);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Tamaño legible desde bytes hasta TB (para tráfico y procesos, donde importan los KB). */
    private static String formatoTamano(long bytes) {
        if (bytes < 0) return "n/d";
        double v = bytes;
        String[] unidades = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < unidades.length - 1) {
            v /= 1024;
            i++;
        }
        if (i == 0) return bytes + " B";
        return String.format(Locale.ROOT, i >= 3 ? "%.2f %s" : "%.1f %s", v, unidades[i]);
    }

    private static String formatoVelocidad(double bytesPorSegundo) {
        return formatoTamano((long) bytesPorSegundo) + "/s";
    }

    private static String formatoDuracion(long segundos) {
        long dias = segundos / 86400;
        long horas = (segundos % 86400) / 3600;
        long minutos = (segundos % 3600) / 60;
        return dias + " días, " + horas + " h, " + minutos + " min";
    }

    private static String ejecutarLimpiezaTemporales() {
        try {
            String temp = System.getProperty("java.io.tmpdir");
            Path directorio = Paths.get(temp);
            int eliminados = 0;
            if (Files.isDirectory(directorio)) {
                try (var archivos = Files.list(directorio)) {
                    for (Path archivo : archivos.toList()) {
                        try {
                            if (Files.isRegularFile(archivo)) {
                                Files.deleteIfExists(archivo);
                                eliminados++;
                            }
                        } catch (Exception ignorado) {
                            // Algunos archivos temporales pueden estar en uso.
                        }
                    }
                }
            }
            return "ÉXITO: Limpieza segura completada. Archivos temporales eliminados: " + eliminados;
        } catch (Exception e) {
            return "ERROR en limpieza: " + e.getMessage();
        }
    }
}
