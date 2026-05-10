#!/usr/bin/env bash
set -euo pipefail

echo "━━━ [1/3] Build du binaire natif (GraalVM container) ━━━"
mvn package -Pnative -Dquarkus.native.container-build=true -DskipTests

echo "━━━ [2/3] Build de l'image Docker native ━━━"
docker build -f src/main/docker/Dockerfile.native -t market-app:native .

echo "━━━ [3/3] Démarrage des services ━━━"
docker compose -f docker-compose.yml -f docker-compose.native.yml up -d

echo "✅ Application native disponible sur http://localhost:8080"
