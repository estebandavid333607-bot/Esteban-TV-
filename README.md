# Esteban TV

Navegador con reproductor de video integrado. Interfaz negro AMOLED + color a elegir (paleta).
App hecha por Esteban Ruiz.

## Novedades v2.0
- **Rotación de pantalla** (Ajustes): sistema, automática, vertical u horizontal. En ExoPlayer hay botón "Rotar".
- **ExoPlayer con extras**: botón de resolución (lista las calidades disponibles del video), velocidad,
  aspecto, audio/subtítulos, doble toque ±10 s, brillo/volumen deslizando, bloqueo de controles,
  buffer ampliado, reintento y copiar enlace.
- **Aviso antes de ExoPlayer**: ventana Sí / No con el nombre del video en un renglón.
- **Inicio**: logo real de Google; al guardar una web pide el nombre (con sugerencia) y muestra su logotipo.
- **Anti-redirección**: desactivada / solo tras 2 toques / nunca (Ajustes). Menú ⋮ > "Permitir última redirección bloqueada".
- **Ajustes** en ⋮: buscador, texto, imágenes, cookies, última página, pantalla completa, paleta, datos y más.
- **Bienvenida** de 2 segundos al abrir.

## Compilar
**Android Studio:** File > Open > carpeta EstebanTV > Run.

**Desde el celular (sin PC):** sube esta carpeta a GitHub. En Actions se ejecuta "Build APK";
descarga el artefacto `EstebanTV-apk` (app-debug.apk) e instálalo.
