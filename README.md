# EconomySystem

[![Build](https://github.com/minefalo/EconomySystem/actions/workflows/build.yml/badge.svg)](https://github.com/minefalo/EconomySystem/actions/workflows/build.yml)
[![Lizenz: MIT](https://img.shields.io/badge/Lizenz-MIT-blue.svg)](LICENSE)

BigDecimal-basiertes Wirtschaftssystem für Paper-Server mit YAML- oder
MySQL-Speicher, konfigurierbarem Zinszyklus und Admin-Befehlen.

## Funktionen

- **Geld ohne Rundungsfehler** – alle Beträge sind `BigDecimal`, mit
  konfigurierbaren Nachkommastellen und Rundungsmodus.
- **Spieler-Befehle** – `/money` und `/pay`, inklusive Kontostand fremder
  Spieler (berechtigungsabhängig).
- **Admin-Befehle** – Geld hinzufügen, abziehen, setzen oder auf das
  Startguthaben zurücksetzen.
- **Kreditsystem** – Kredite bis zum 3-fachen Eigenkapital, frei wählbare
  Laufzeit, Zinsen nach `y = x · m + a` und automatische Tilgung bei
  Fälligkeit.
- **Zinszyklus** – nach der Blockcraft-Wirtschaftsformel `y = a · √x` für
  **alle gespeicherten Konten**, auch offline. Zeitpunkt und Intervall sind
  konfigurierbar (Standard: täglich um 00:01 Uhr).
- **Zwei Speicherarten** – YAML (`accounts/<uuid>.yml`, Kredite in
  `loans.yml`) oder MySQL (`DECIMAL(30,8)`, Tabellen werden automatisch
  angelegt).
- **Konfiguration mit Kommentaren** – Änderungen an Zinsfaktoren werden
  zeilenweise geschrieben, bestehende Kommentare bleiben erhalten.
- **Mehrseitige Hilfe** – `/economysystem-admin help` liest `help.yml` und
  blättert per Klick im Chat.
- **PlaceholderAPI** – optional: Guthaben, Top-Liste und aktueller Sparzins
  als Platzhalter für Scoreboards, TAB & Co.
- **Aliase & Reload** – zusätzliche Befehlsnamen über `commands.yml`,
  `/economysystem-admin reload` lädt alle Konfigurationen neu.

## Voraussetzungen

| Komponente | Version |
| --- | --- |
| Paper-Server | API-Version `26.3` |
| Java | 25 |
| Maven (nur zum Bauen) | 3.9+ |
| PlaceholderAPI | optional |

## Installation

1. Projekt bauen:

   ```bash
   mvn clean package
   ```

2. Die erzeugte Datei `target/economysystem-0.1.jar` in den
   `plugins/`-Ordner des Servers kopieren.
3. Server starten – im Plugin-Ordner `plugins/EconomySystem/` werden
   `settings.yml`, `messages.yml`, `commands.yml` und `help.yml` angelegt.
4. Anpassen und mit `/economysystem-admin reload` neu laden.

## Befehle

| Befehl | Beschreibung |
| --- | --- |
| `/money [Spieler]` | Zeigt das eigene oder ein fremdes Guthaben. |
| `/pay <Spieler> <Betrag>` | Überweist Geld an einen anderen Spieler. |
| `/kredit add <Betrag> <Tage>` | Nimmt einen Kredit auf. |
| `/kredit bezahlen [Betrag]` | Zahlt offene Kredite zurück (ohne Betrag: so viel wie möglich). |
| `/kredit info` | Zeigt Guthaben, Kreditrahmen und laufende Kredite. |
| `/economysystem help` | Zeigt die allgemeine Hilfe. |
| `/economysystem-admin help [Seite]` | Zeigt die mehrseitige Admin-Hilfe. |
| `/economysystem-admin info` | Zeigt Speicher, Währung, Zinsen und Zyklus. |
| `/economysystem-admin reload` | Lädt alle Konfigurationen neu. |
| `/economysystem-admin money <Spieler> add <Betrag>` | Schreibt Geld gut. |
| `/economysystem-admin money <Spieler> remove <Betrag>` | Zieht Geld ab. |
| `/economysystem-admin money <Spieler> set <Betrag>` | Setzt das Guthaben exakt. |
| `/economysystem-admin money <Spieler> reset` | Setzt auf das Startguthaben zurück. |
| `/economysystem-admin zinsen spar set <Faktor>` | Setzt den Sparzins-Faktor `a`. |
| `/economysystem-admin zinsen kredit set <Faktor>` | Setzt den Kreditzins-Faktor `a`. |
| `/economysystem-admin tests zins-zyklus add <Anzahl>` | Führt Zinszyklen sofort aus (nur mit `tests.enabled`). |

Beträge akzeptieren `.` und `,` als Dezimaltrennzeichen. Das maximale
Guthaben aus der `settings.yml` wird immer beachtet.

### Aliase

Die Aliase stehen in `commands.yml` und können dort erweitert werden:

| Befehl | Standard-Aliase |
| --- | --- |
| `money` | `geld`, `balance`, `coins` |
| `pay` | `zahlen`, `ueberweisen` |
| `kredit` | `credit`, `darlehen` |
| `economysystem` | `es`, `ecoadmin` |
| `economysystem-admin` | `esa`, `ecoadm` |

## Berechtigungen

| Permission | Standard | Beschreibung |
| --- | --- | --- |
| `economysystem.balance` | alle | Eigenes Guthaben anzeigen. |
| `economysystem.balance.others` | OP | Guthaben anderer Spieler anzeigen. |
| `economysystem.pay` | alle | Geld überweisen. |
| `economysystem.kredit` | alle | Kreditsystem nutzen (`/kredit`). |
| `economysystem.admin` | OP | Admin-Befehle: Geldverwaltung, Zinsen, Info. |
| `economysystem.reload` | OP | Zusätzlich zu `economysystem.admin` das Neuladen über `/economysystem-admin reload`. |
| `economysystem.tests` | OP | Zusätzlich zu `economysystem.admin` die Test-Befehle (nur mit `tests.enabled`). |

## PlaceholderAPI (optional)

PlaceholderAPI wird über `softdepend` automatisch erkannt. Ohne das Plugin
startet EconomySystem normal, es stehen dann nur keine Platzhalter zur
Verfügung.

| Platzhalter | Ausgabe |
| --- | --- |
| `%money_%` | Eigenes Guthaben, formatiert (z. B. `$1.234,56`) |
| `%money_<Spieler>%` | Guthaben eines Spielers |
| `%eco_top_<Rang>%` | Formatiertes Guthaben auf diesem Rang |
| `%eco_top_<Rang>_name%` | Spielername auf diesem Rang |
| `%eco_top_<Rang>_raw%` | Betrag ohne Währungsformat |
| `%aktuelle_sparzinsen%` | Absoluter Sparzins für das eigene Guthaben |
| `%aktuelle_sparzinsen_<Spieler>%` | Absoluter Sparzins für ein fremdes Guthaben |

Zusätzlich gibt es die eindeutig benannte Variante:

| Platzhalter | Ausgabe |
| --- | --- |
| `%economysystem_money%` | Eigenes Guthaben |
| `%economysystem_money_<Spieler>%` | Guthaben eines Spielers |
| `%economysystem_balance[_<Spieler>]%` | Alternative Schreibweise |
| `%economysystem_top_<Rang>[_name\|_raw]%` | Top-Liste |
| `%economysystem_sparzinsen[_<Spieler>]%` | Sparzins absolut |

Hinweise:

- **Platzhalter brauchen immer einen Unterstrich.** PlaceholderAPI erwartet
  die Form `%präfix_parameter%` und reicht Text ohne Unterstrich (etwa
  `%money%`) gar nicht erst an eine Erweiterung weiter – dort bleibt
  `%money%` unverändert stehen. Für das eigene Guthaben deshalb
  `%money_%` schreiben (Unterstrich mit leerem Parameter) oder die
  Variante `%economysystem_money%` nutzen.
- Beträge werden mit dem konfigurierten `currency.format` ausgegeben;
  `_raw` liefert den reinen `BigDecimal`-String.
- Die Top-Liste umfasst 50 Plätze und wird alle 60 Sekunden asynchron neu
  berechnet (auch offline gespeicherte Konten).
- Offline-Guthaben werden 30 Sekunden zwischengespeichert; Online-Spieler
  und bereits geladene Konten werden immer live aus dem Cache gelesen.
- Der Sparzins ist ein **absoluter Betrag** (`y = a · √x`), kein Prozentwert.

## Konfiguration

Alle Dateien liegen in `plugins/EconomySystem/`.

### `settings.yml` (Auszug)

```yaml
tests:
  enabled: false                 # Test-Befehle (nur auf Testservern!)

currency:
  name: "Coins"
  symbol: "$"
  format: "%symbol%%amount%"
  number-format: "#,##0.00"
  locale: "de-DE"
  decimals: 2
  rounding: HALF_UP
  starting-balance: "100.00"
  max-balance: "1000000000.00"   # "-1" = unbegrenzt
  min-balance: "-1000.00"        # Untergrenze bei Zwangsabbuchungen

interest:
  enabled: true
  start-time: "00:01"            # erster Lauf (Serverzeit)
  interval-minutes: 1440         # 1440 = täglich
  factors:
    spar: "1.3"                  # Sparzins
    kredit: "1.3"                # a der Kreditformel

credits:
  enabled: true
  max-loans: 3                   # gleichzeitige Kredite pro Spieler
  limit-multiplier: "3.0"        # Kreditrahmen = 3 × Eigenkapital
  slope: "0.5"                   # m in y = x*m + a
  minimum-amount: "10.00"
  min-days: 1
  max-days: 365
  check-interval-seconds: 60     # Prüfung fälliger Kredite

storage:
  type: YAML                     # YAML oder MYSQL
  save-interval-seconds: 300
```

### Zinsformel

```
y = a · √x
```

- `x` = aktueller Kontostand, `a` = Zinsfaktor, `y` = Zinsbetrag.
- Gültig für `x ≥ 0` und `a > 0`; bei `x = 0` fällt kein Zins an.
- Beispiel: `x = 100.00`, `a = 1.3` → `y = 13.00`.
- Das Ergebnis wird auf das maximale Guthaben begrenzt.
- Der **Sparzins** wird am Ende jedes Zyklus allen Konten gutgeschrieben.
- Der **Kreditzins** (`a` in `y = x · m + a`) gilt für neu aufgenommene
  Kredite; bestehende Konten werden im Zyklus nicht belastet.

Der Zyklus verarbeitet alle gespeicherten Konten – auch von Spielern, die
gerade offline sind. Läuft der Server zur konfigurierten Startzeit nicht,
wird der Lauf nicht nachgeholt.

### Zinszyklen simulieren (Tests)

Zum Prüfen der Zinsformel kann der Zyklus sofort mehrfach ausgeführt werden:

```yaml
tests:
  enabled: true    # Standard: false
```

```
/economysystem-admin tests zins-zyklus add <Anzahl>
```

- Führt den Zinszyklus sofort `<Anzahl>`-mal aus (1 – 1000) und simuliert
  damit vergangene Zyklen.
- Jeder Lauf baut auf dem Guthaben des vorherigen auf, das Wachstum
  entspricht also mehreren echten Zyklen.
- Verwendet immer den aktuellen Sparzins-Faktor, auch wenn der automatische
  Zyklus (`interest.enabled`) deaktiviert ist.
- **Verändert echte Kontostände.** Nur auf Test-/Entwicklungsservern
  aktivieren; zusätzlich ist die Permission `economysystem.tests` nötig.
- Ist `tests.enabled` auf `false`, antwortet der Befehl nur mit einem
  Hinweis und ändert nichts.

### Kreditsystem

Spieler können sich mit `/kredit add <Betrag> <Tage>` Geld leihen. Alle
Regeln stehen in der `settings.yml` unter `credits:`.

**Kreditrahmen**

```
verfügbar = limit-multiplier × (Guthaben − alle offenen Kredite)
```

- Standard: das 3-fache des Eigenkapitals (`limit-multiplier: "3.0"`).
- Ist das Eigenkapital negativ, wird kein Kredit vergeben.
- Maximal `max-loans` gleichzeitige Kredite (Standard: 3).
- Die Auszahlung darf das `max-balance` nicht überschreiten.

**Zinsen**

```
y = x · m + a
```

- `x` = Laufzeit in Tagen (natürliche Zahl zwischen `min-days` und `max-days`).
- `a` = `interest.factors.kredit` (Standard: `1.3`).
- `m` = `credits.slope` (Standard: `0.5`).
- `y` ist der **absolute Zinsbetrag**, der zusätzlich zum Kredit zurückgezahlt
  wird. Rückzahlung insgesamt: `Kredit + y`.
- Beispiel: `1.000` für `30` Tage → `y = 30 · 0.5 + 1.3 = 16.30`,
  Rückzahlung `1.016,30`.

**Rückzahlung und Fälligkeit**

- `/kredit bezahlen [Betrag]` zahlt manuell zurück (ältester Kredit zuerst);
  ohne Betrag so viel wie das Guthaben hergibt.
- Am Fälligkeitstag bucht das System automatisch ab – auch ins Negative,
  jedoch nie unter `currency.min-balance` (Standard: `-1000.00`).
- Wird dabei die Untergrenze erreicht und bleibt etwas offen, werden die
  restlichen **Zinsen erlassen**; der offene Kreditbetrag bleibt bestehen.
- Ab dann gehen **alle Einnahmen** (z. B. `/pay`-Zahlungen oder
  Admin-Gutschriften) automatisch in die Tilgung, bis der Kredit bezahlt ist.
- Fällige Kredite werden alle `check-interval-seconds` Sekunden geprüft.

Kredite werden persistent gespeichert – im YAML-Modus in `loans.yml`, im
MySQL-Modus in der Tabelle `<table-prefix>loans`.

Weitere Dateien:

- `messages.yml` – alle Nachrichten, Farben (`&`-Codes und `&#RRGGBB`).
- `help.yml` – Seiten der Admin-Hilfe inklusive klickbarer Navigation.
- `commands.yml` – Aliase für alle Befehle.
- `loans.yml` – gespeicherte Kredite (nur im YAML-Modus).

## Projektstruktur

```
src/main/java/de/Minefalo/economySystem/
├── command/      Befehle und Alias-Registrierung
├── config/       settings.yml, messages.yml, commands.yml, help.yml
├── credit/       Kredite, Zinsformel und automatische Tilgung
├── currency/     BigDecimal-Währung (Parsen, Formatieren, Runden)
├── economy/      Konten, Service, Zinsberechnung und -scheduler
├── help/         Mehrseitige Hilfe
├── listener/     Join-Handling
├── placeholder/  PlaceholderAPI-Erweiterungen
├── storage/      YAML- und MySQL-Speicher
└── util/         Text-/Farb-Helfer
```

## Entwicklung

```bash
mvn clean package     # JAR bauen
mvn test              # Tests ausführen
```

Der GitHub-Actions-Workflow `.github/workflows/build.yml` baut das Projekt
bei jedem Push und Pull Request gegen Java 25 und lädt das JAR als Artifact
hoch.

## Lizenz

Dieses Projekt steht unter der [MIT-Lizenz](LICENSE).
