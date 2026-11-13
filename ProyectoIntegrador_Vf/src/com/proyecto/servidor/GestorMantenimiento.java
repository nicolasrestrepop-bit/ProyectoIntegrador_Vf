package com.proyecto.servidor;

public class GestorMantenimiento {

    private final GestorNodos gestorNodos;
    private final ServidorCentral servidor;

    public GestorMantenimiento(
            GestorNodos gestorNodos,
            ServidorCentral servidor) {

        this.gestorNodos = gestorNodos;
        this.servidor = servidor;
    }

    public void ejecutarEnTodosLosNodos(String comando) {

        if (gestorNodos.cantidadNodos() == 0) {
            servidor.agregarLog(
                    "No hay nodos conectados para ejecutar: "
                            + comando
            );
            return;
        }

        for (ManejadorCliente manejador :
                gestorNodos.obtenerNodos()) {

            manejador.enviarComando(comando);
        }
    }
}