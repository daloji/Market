# Run scalping tests

Lance les tests des 3 services scalping et affiche un résumé clair.

```bash
mvn test -Dtest="ScalpingAnalysisServiceTest,BinanceScalpingTradeServiceTest,BinanceFuturesServiceTest" 2>&1 | grep -E "Tests run:|FAIL|ERROR|BUILD|Exception" | grep -v "^\[INFO\] \[" | grep -v "StockData"
```

Interprète le résultat :
- `Tests run: X, Failures: 0, Errors: 0` × 3 lignes + `BUILD SUCCESS` = tout est bon
- Si un test échoue, lis le message d'erreur complet et propose un fix
- Rappel patterns tests : voir CLAUDE.md section "Patterns critiques"
