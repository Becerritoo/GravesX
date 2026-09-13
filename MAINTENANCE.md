# Mantenimiento del fork JL

## Repositorios y ramas

- Origin: https://github.com/Becerritoo/GravesX
- Upstream: https://github.com/Legoman99573/GravesX
- `jl/server-maintenance`: historial de las personalizaciones del servidor.
- `jl/compat-1.21.11`: rama usada por el PR upstream #252. No mezclar nuevas
  reparaciones en ese PR sin revisar su alcance con el mantenedor.
- Para cada trabajo, crear una rama desde la rama del servidor y hacer commits
  pequenos. Revisar el diff antes de incorporar y publicar.

## Politica del mantenedor original

En https://github.com/Legoman99573/GravesX/pull/252 el mantenedor solicita conservar
la version oficial y gestionar las integraciones mediante IntegrationManager.
`b0bfa1a` restaura `4.9.11.1` en pom.xml; no elimina funcionalidades JL.
`12086b4` incorpora la correccion de `grave.explode` del PR #251.

La inicializacion de BagOfGoldPhysicalMoneyIntegration sigue en EntityDeathListener;
su traslado a IntegrationManager queda pendiente y requiere pruebas independientes.

## Antes de editar

1. Revisar `git status --short --branch`, `git diff`, `git remote -v` y este documento.
2. Respaldar cambios sin commit, incluidos archivos nuevos, fuera del repositorio.
3. Hacer `git fetch origin` y comparar historiales. No usar pull/rebase a ciegas
   sobre trabajo sucio. No reescribir commits publicados ni forzar pushes.
4. Definir alcance y pruebas. Una solicitud de informe no autoriza reparaciones.

## Compilacion

Herramientas observadas al formalizar: JDK 21 y Maven 3.8.7. El POM declara
source/target Java 17; eso no equivale a certificar el servidor sobre Java 17.
Las dependencias se definen en pom.xml y las bibliotecas auxiliares en libs/.
Usar un clon/worktree separado para probar si hay otros trabajos en curso.

```sh
mvn -B -ntp package
git diff --check
git status --short
```

No sustituir dependencias o desactivar pruebas silenciosamente si falla la
resolucion. Registrar el error y las herramientas necesarias. Maven puede generar
dependency-reduced-pom.xml: no editarlo ni versionarlo. No desplegar el JAR API
como plugin. Registrar commit completo, comando, herramientas, SHA-256 del JAR
seleccionado y resultado de pruebas antes de distribuir una compilacion.

## Pruebas de regresion

Usar un servidor aislado con configuracion y almacenamiento propios, sin apuntar
a la base de datos de produccion. Anonimizar cualquier dato copiado.

- Crear, saquear, vaciar y expirar tumbas; verificar inventario y XP.
- Descargar/cargar chunks y reiniciar la instancia de prueba: los hologramas
  huerfanos deben desaparecer y los de tumbas validas deben conservarse.
- BetterRevive: muerte directa, estado derribado, reanimacion y muerte final;
  comprobar atacante y causa, incluyendo proyectiles y dano ambiental.
- BagOfGold/CustomItemsLib: reparto fisico y auto-loot, sin duplicacion, perdida
  inesperada ni desincronizacion de saldo. Probar tambien sin la integracion.
- `grave.explode` activado y desactivado con explosiones de bloques y entidades.
- Arranque/apagado y persistencia SQL; revisar errores de scheduler y base de datos.

Anotar versiones exactas y resultado por caso. Compilar no acredita estas pruebas.
No afirmar compatibilidad Folia si solo se ha probado Paper.

## Publicacion y contribuciones

Actualizar CHANGELOG.md y publicar solamente la rama revisada en origin.
Para upstream, seleccionar commits apropiados en una rama de contribucion,
respetar su version y explicar pruebas y comportamiento configurable. No marcar
como aceptado un cambio solo porque el mantenedor haya comentado favorablemente.

## Produccion y recuperacion

El servidor vive fuera de este repositorio, en /srv/minecraft/server-jl. No copiar
artefactos, recargar ni reiniciar sin autorizacion explicita. Antes de desplegar,
respaldar JAR, configuracion y almacenamiento de forma consistente y definir una
ventana. Evitar dos JAR del mismo plugin. Guardar checksums antes y despues.
Un rollback de JAR no revierte cambios de datos: evaluar compatibilidad del esquema
y recuperar datos solo mediante un procedimiento aprobado para evitar perdida.

## Estado al formalizar (2026-09-13)

El JAR observado en produccion se llama GravesX-4.9.11.1-JL.2.jar. El cambio de
version del codigo no reemplaza ese archivo ni demuestra reproducibilidad binaria.
Las reparaciones locales se importan en commits sin modificar su comportamiento;
no deben confundirse con una nueva validacion completa en juego.
Respaldo local previo: /srv/minecraft/dev/gravesx-before-formalization-20260913.tar.gz
(fuentes locales; NO es un respaldo de mundos ni de la base de datos).
