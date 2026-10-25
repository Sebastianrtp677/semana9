# SimpleGPSMapApp

Aplicación Android (Java) que muestra un mapa de OpenStreetMap (osmdroid, sin API key) con la ubicación actual del usuario,
permite añadir marcadores y capturar y mostrar una imagen.

## Funcionalidades

- Mostrar la ubicación actual en el mapa (indicador de ubicación + cámara centrada).
- Añadir marcadores: pulsación larga en cualquier punto del mapa, o botón **Marcar aquí** para
  dejar un marcador en la ubicación actual.
- Dirección del marcador obtenida con geocodificación inversa en un hilo secundario (`ExecutorService`),
  sin bloquear la interfaz.
- Capturar una foto con la cámara, mostrarla en pantalla y guardarla (JPEG) en el almacenamiento
  interno de la app, también en un hilo secundario.
- Solicitud de permisos de ubicación en tiempo de ejecución.

## Instrucciones de Ejecución

1. Clona el repositorio: `git clone https://github.com/USERNAME/SimpleGPSMapApp.git`
2. Abre el proyecto en Android Studio.
3. No necesitas ninguna API key ni cuenta de Google Cloud.
4. Ejecuta la aplicación en un dispositivo o emulador Android con Google Play Services (para la ubicación)
   (minSdk 30). En el emulador, fija una ubicación desde *Extended controls > Location*.

## Tecnologías

Java, osmdroid (OpenStreetMap), Fused Location Provider (`play-services-location`),
Activity Result API (permisos y cámara), `ExecutorService` para tareas en segundo plano.
