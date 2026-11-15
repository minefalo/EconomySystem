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
- **Zinszyklus** – nach der Blockcraft-Wirtschaftsformel `y = a · √x` für
  **alle gespeicherten Konten**, auch offline. Zeitpunkt und Intervall sind
  konfigurierbar (Standard: täglich um 00:01 Uhr).
- **Zwei Speicherarten** – YAML (`accounts/<uuid>.yml`) oder MySQL
  (`DECIMAL(30,8)`, Tabelle wird automatisch angelegt).
- **Konfiguration mit Kommentaren** – Änderungen an Zinsfaktoren werden
  zeilenweise geschrieben, bestehende Kommentare bleiben erhalten.
- **Mehrseitige Hilfe** – `/economysystem-admin help` liest `help.yml` und
  blättert per Klick im Chat.
- **Aliase & Reload** – zusätzliche Befehlsnamen über `commands.yml`,
  `/economysystem reload` lädt alle Konfigurationen neu.

## Voraussetzungen

| Komponente | Version |
| --- | --- |
| Paper-Server | API-Version `26.3` |
| Java | 25 |
| Maven (nur zum Bauen) | 3.9+ |

## Installation

1. Projekt bauen:

   ```bash
   mvn clean package
   ```

2. Die erzeugte Datei `target/economysystem-0.1.jar` in den
   `plugins/`-Ordner des Servers kopieren.
3. Server starten – im Plugin-Ordner `plugins/EconomySystem/` werden
   `settings.yml`, `messages.yml`, `commands.yml` und `help.yml` angelegt.
4. Anpassen und mit `/economysystem reload` neu laden.

## Befehle

| Befehl | Beschreibung |
| --- | --- |
| `/money [Spieler]` | Zeigt das eigene oder ein fremdes Guthaben. |
| `/pay <Spieler> <Betrag>` | Überweist Geld an einen anderen Spieler. |
| `/economysystem help` | Zeigt die allgemeine Hilfe. |
| `/economysystem info` | Zeigt Speicher, Währung, Zinsen und Zyklus. |
| `/economysystem reload` | Lädt alle Konfigurationen neu. |
| `/economysystem-admin help [Seite]` | Zeigt die mehrseitige Admin-Hilfe. |
| `/economysystem-admin money <Spieler> add <Betrag>` | Schreibt Geld gut. |
| `/economysystem-admin money <Spieler> remove <Betrag>` | Zieht Geld ab. |
| `/economysystem-admin money <Spieler> set <Betrag>` | Setzt das Guthaben exakt. |
| `/economysystem-admin money <Spieler> reset` | Setzt auf das Startguthaben zurück. |
| `/economysystem-admin zinsen spar set <Faktor>` | Setzt den Sparzins-Faktor `a`. |
| `/economysystem-admin zinsen kredit set <Faktor>` | Setzt den Kreditzins-Faktor `a`. |

Beträge akzeptieren `.` und `,` als Dezimaltrennzeichen. Das maximale
Guthaben aus der `settings.yml` wird immer beachtet.

### Aliase

Die Aliase stehen in `commands.yml` und können dort erweitert werden:

| Befehl | Standard-Aliase |
| --- | --- |
| `money` | `geld`, `balance`, `coins` |
| `pay` | `zahlen`, `ueberweisen` |
| `economysystem` | `es`, `ecoadmin` |
| `economysystem-admin` | `esa`, `ecoadm` |

## Berechtigungen

| Permission | Standard | Beschreibung |
| --- | --- | --- |
| `economysystem.balance` | alle | Eigenes Guthaben anzeigen. |
| `economysystem.balance.others` | OP | Guthaben anderer Spieler anzeigen. |
| `economysystem.pay` | alle | Geld überweisen. |
| `economysystem.admin` | OP | Geldverwaltung und Zinsfaktoren. |
| `economysystem.reload` | OP | Konfiguration neu laden. |

## Konfiguration

Alle Dateien liegen in `plugins/EconomySystem/`.

### `settings.yml` (Auszug)

```yaml
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

interest:
  enabled: true
  start-time: "00:01"            # erster Lauf (Serverzeit)
  interval-minutes: 1440         # 1440 = täglich
  factors:
    spar: "1.3"                  # Sparzins
    kredit: "1.3"                # Kreditzins für neue Kredite

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
- Der **Kreditzins** wird gespeichert und gilt für neue Kredite; bestehende
  Konten werden im Zyklus nicht belastet.

Der Zyklus verarbeitet alle gespeicherten Konten – auch von Spielern, die
gerade offline sind. Läuft der Server zur konfigurierten Startzeit nicht,
wird der Lauf nicht nachgeholt.

Weitere Dateien:

- `messages.yml` – alle Nachrichten, Farben (`&`-Codes und `&#RRGGBB`).
- `help.yml` – Seiten der Admin-Hilfe inklusive klickbarer Navigation.
- `commands.yml` – Aliase für alle Befehle.

## Projektstruktur

```
src/main/java/de/Minefalo/economySystem/
├── command/      Befehle und Alias-Registrierung
├── config/       settings.yml, messages.yml, commands.yml, help.yml
├── currency/     BigDecimal-Währung (Parsen, Formatieren, Runden)
├── economy/      Konten, Service, Zinsberechnung und -scheduler
├── help/         Mehrseitige Hilfe
├── listener/     Join-Handling
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
