package com.proyecto.servidor;

public class GestorReportes {

    private final GestorNodos gestorNodos;

    public GestorReportes(GestorNodos gestorNodos) {
        this.gestorNodos = gestorNodos;
    }

    public String construirReporte(String logs) {

        int nodosActivos = gestorNodos.cantidadNodos();

        String estadoRed;

        if (nodosActivos > 0) {
            estadoRed =
                    "OPERATIVA ("
                    + nodosActivos
                    + " nodo(s) respondiendo)";
        } else {
            estadoRed =
                    "STANDBY (Sin agentes conectados actualmente)";
        }

        return GeneradorReportes.construirReporteEstructurado(
                logs,
                nodosActivos,
                estadoRed
        );
    }
}