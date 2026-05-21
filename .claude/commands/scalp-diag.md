# Scalping diagnostic complet

Effectue un diagnostic complet du bot scalping : signal, position, logs récents.

**Étape 1 — Signal live**
```bash
curl -s http://162.19.67.58:8080/api/crypto/btc/scalping | python3 -m json.tool 2>/dev/null
```

**Étape 2 — Statut**
```bash
curl -s http://162.19.67.58:8080/api/scalping/status | python3 -m json.tool 2>/dev/null
```

**Étape 3 — Diagnostic endpoint**
```bash
curl -s http://162.19.67.58:8080/api/scalping/diagnose | python3 -m json.tool 2>/dev/null
```

Après récupération, produis un rapport structuré :

## Rapport diagnostic

### Filtres
- TTM Squeeze : ON/OFF
- ATR% : X% (gate à 0.15% — [BLOQUÉ/OK])
- Seuil signal : X pts vs seuil Y pts

### Indicateurs (seulement si ATR gate passé)
Liste les indicateurs qui contribuent le plus (positif/négatif) au score.

### Position active
- Direction, entrée, TP1/TP2, SL, durée, PnL latent estimé

### Blocages identifiés
Liste ordonnée des raisons pour lesquelles le bot ne trade pas (si applicable).

### Recommandation
Action concrète si quelque chose est anormal.
