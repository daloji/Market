# Scalping live check

Récupère le signal et l'état en live depuis le serveur de prod, analyse et explique.

```bash
curl -s http://162.19.67.58:8080/api/crypto/btc/scalping | python3 -m json.tool 2>/dev/null | head -80
```

```bash
curl -s http://162.19.67.58:8080/api/scalping/status | python3 -m json.tool 2>/dev/null
```

Après avoir récupéré les données, analyse et réponds :

1. **Direction & confidence** — LONG/SHORT/WAIT et pourquoi
2. **Filtres actifs** — TTM Squeeze ou ATR gate bloquant ?
   - Si `direction=WAIT` et ATR < 0.15% : normal, volatilité insuffisante
   - Si `direction=WAIT` et ATR ≥ 0.15% : vérifier les scores
3. **Indicateurs à 0** — NORMAL si `direction=WAIT` (non calculés avant les gates)
4. **Position active** — entrée, TP1/TP2, SL, PnL latent
5. **Cooldown / loss streak** — bloquant ?
6. **Conclusion** — le bot va-t-il trader au prochain cycle ?
