# Changelog del fork JL

## 2026-09-13 - Formalizacion del codigo local

### Reparaciones existentes incorporadas al historial

- `f7908fd`: limpieza diferida de hologramas, barrido de chunks cargados y
  seguimiento de la carga del mapa de tumbas. Importado del trabajo local previo.
- `f9c4fcf`: snapshots de causa de dano y atacante para BetterRevive.
  Importado del trabajo local previo.
- No se afirma que el JAR instalado sea reproducible desde estos commits.

### Mantenimiento

- Rama dedicada jl/server-maintenance; README, MAINTENANCE.md y AGENTS.md.
- Version Maven alineada con 4.9.11.1, siguiendo la indicacion de b0bfa1a.
- Se deja de versionar dependency-reduced-pom.xml, generado por Maven.
- Sin cambios de configuracion, base de datos ni despliegue en produccion.

### Pendientes

- Centralizar la inicializacion de BagOfGold en IntegrationManager (PR #252).
- Ejecutar la matriz de regresion en un servidor de pruebas aislado.
- Filtro de tumbas por especie del atacante: solicitado para analisis, NO implementado.

## Historial anterior seleccionado

- `12086b4`: respetar grave.explode (correccion upstream #251).
- `5ae9511`: evitar alteraciones UNIQUE repetidas sobre grave.uuid en MariaDB.
- `c623196`: reconciliar saldo BagOfGold tras auto-loot.
- `23ec1f1`: registrar ubicaciones solidas secas para colocacion segura.
- `cca7437`: reparto de dinero fisico BagOfGold y colocacion segura prioritaria.
- `063e2de`: protecciones durante apagado y desregistro de PlaceholderAPI.

Esta lista es un resumen, no sustituye git log ni constituye evidencia de pruebas.
