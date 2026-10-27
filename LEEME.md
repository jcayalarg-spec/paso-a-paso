# Paso a Paso Ayuda (Android) — versión de prueba 0.1

El agente ve la pantalla del cliente en vivo y le señala dónde tocar con un círculo amarillo
que aparece en el teléfono del cliente, encima de cualquier app. También puede llevarlo al
inicio o abrirle WhatsApp, Mensajes o el correo con un botón.

**Regla de diseño:** la app señala y abre apps; **nunca toca nada por el cliente**. La
autorización, las contraseñas y los datos de ID.me los toca y escribe el cliente.

## Las tres piezas

| Pieza | Dónde vive | Para qué |
|---|---|---|
| `android/` | GitHub la convierte en la app (archivo .apk) | La instala el cliente |
| `web/ayuda/` | Netlify, dentro de tu sitio | `agente.html`: tu panel. `index.html`: la página que abre el cliente |
| `firebase/` | Firebase (Google) | Conecta tu panel con el teléfono del cliente |

---

## Paso 1 · Firebase (10 minutos, gratis)

1. Entra a https://console.firebase.google.com con tu cuenta de Google y toca **Crear un proyecto**.
   Nombre: `paso-a-paso-ayuda`. Puedes desactivar Google Analytics.
2. En el menú de la izquierda: **Compilación → Realtime Database → Crear base de datos**.
   Ubicación: **Estados Unidos (us-central1)**. Elige **Comenzar en modo bloqueado**.
3. Arriba verás la dirección de tu base de datos, algo como
   `https://paso-a-paso-ayuda-default-rtdb.firebaseio.com`. **Cópiala.**
4. Abre la pestaña **Reglas**, borra todo, pega el contenido de `firebase/database.rules.json`
   y toca **Publicar**.

## Paso 2 · GitHub arma la app (15 minutos, gratis)

1. Crea una cuenta en https://github.com si no tienes.
2. Arriba a la derecha: **+ → New repository**. Nombre: `paso-a-paso-ayuda`. Puede ser privado.
   Toca **Create repository**.
3. En la página del repositorio toca **uploading an existing file** y arrastra **todo el contenido**
   de esta carpeta (incluida la carpeta oculta `.github`). Toca **Commit changes**.
   - En Windows, si no ves `.github`, en el Explorador activa *Vista → Mostrar → Elementos ocultos*.
5. Ve a la pestaña **Actions**, toca **Construir la app Android → Run workflow**.
   Tarda unos 5 minutos. Cuando salga la palomita verde, en la página principal del repositorio,
   a la derecha, verás **Releases**: ahí está `paso-a-paso-ayuda.apk`.
6. El link fijo para descargar la última versión es:
   `https://github.com/jcayalarg-spec/paso-a-paso/releases/latest/download/paso-a-paso-ayuda.apk`
   - Si el repositorio es **privado**, los clientes no podrán descargarla con ese link. En ese caso
     descarga el .apk tú y súbelo a Netlify junto con la página (ver paso 3).

## Paso 3 · Netlify (5 minutos)

1. Abre `web/ayuda/config.js` con el Bloc de notas y llena las dos líneas:
   ```
   window.PAP_DB = 'https://paso-a-paso-ayuda-default-rtdb.firebaseio.com';
   window.PAP_APK = 'https://github.com/jcayalarg-spec/paso-a-paso/releases/latest/download/paso-a-paso-ayuda.apk';
   ```
   Si el repositorio es privado: copia el `.apk` dentro de `web/ayuda/` y pon
   `window.PAP_APK = '/ayuda/paso-a-paso-ayuda.apk';`
2. Copia la carpeta `ayuda` dentro de la carpeta de tu sitio (la misma que subes a Netlify,
   donde está `index.html`).
3. En https://app.netlify.com abre el sitio **pasoapasoid → Deploys** y arrastra la carpeta del sitio.
4. Tu panel queda en **https://pasoapasoid.netlify.app/ayuda/agente.html**.

## Paso 4 · Primera prueba

Usa un teléfono Android de prueba, no el tuyo de trabajo.

1. Abre tu panel en la computadora, escribe tu nombre y el del cliente y toca **Crear sesión**.
2. Toca **Enviar por WhatsApp** y mándate el link al teléfono de prueba.
3. En el teléfono: toca el link → **Instalar la app** → instala (Android pedirá permitir
   instalar desde Chrome: es normal en pruebas) → vuelve a la página → **Conectar con mi agente**.
4. La app pide dos permisos: **Mostrar sobre otras apps** y **Compartir pantalla**. El panel te
   dice qué decirle en cada momento.
5. Cuando veas la pantalla en tu panel, haz clic en cualquier lugar: en el teléfono debe aparecer
   el círculo amarillo justo ahí.

## Lo que hay que saber

- **Privacidad:** la imagen de la pantalla pasa por tu Firebase y se reemplaza con cada imagen
  nueva. Al tocar **Terminar sesión y borrar** se borra todo. Si el cliente escribe su Seguro
  Social, tú lo verás en pantalla: no lo anotes. Para producción conviene pasar la imagen directo
  de teléfono a teléfono (WebRTC) sin guardarla en ningún servidor.
- **Seguridad del link:** cada sesión usa un código largo al azar; quien no lo tenga no puede ver
  nada. Igual, no reenvíes links de sesión.
- **La llave de firma** (`android/app/pasoapaso-prueba.jks`) es solo para pruebas. Para la Play
  Store se usa la firma de Google Play.
- **Play Store:** cuando la prueba funcione, se publica con una cuenta de desarrollador de Google
  (pago único de 25 USD) para que los clientes la instalen sin el permiso de "orígenes desconocidos".
- **Algunas apps bloquean capas encima** (por ejemplo, ciertas apps de bancos). Ahí el círculo
  puede no verse; la imagen sí.
- **iPhone:** esta versión es solo para Android.
