# ERC Rebalancing (Google Sheets ↔ Colab)

Dieses Projekt berechnet **Equal Risk Contribution (ERC)** Portfolio-Gewichte aus Preisdaten in **Google Sheets** und schreibt die Ergebnisse wieder zurück in das Sheet.

**Ablauf:**
1. Preise aus Google Sheet (`Prices_5Y`) laden
2. Monatsrenditen (log) berechnen
3. Kovarianzmatrix erstellen (stabilisiert mit Ridge)
4. ERC optimieren (long-only, Summe = 1)
5. Gewichte in `Weights` Tab zurückschreiben

**Output im Weights Sheet:**
- `SleeveWeight` – reine ERC-Gewichte (Summe = 1)
- `TotalPortfolioWeight` – multipliziert mit dem Satellite-Anteil (`SLEEVE_SHARE`)
- `RC_%` – Risikoanteil jedes Assets

**Konfiguration im Notebook:**
```python
SPREADSHEET_NAME  = "ERC Rebalancing"
PRICES_SHEET_NAME = "Prices_5Y"
OUTPUT_SHEET_NAME = "Weights"
SLEEVE_SHARE      = 0.25
WINDOW_MONTHS     = 60
