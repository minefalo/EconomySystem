# EconomySystem

[![Build](https://github.com/Minefalo/EconomySystem/actions/workflows/build.yml/badge.svg)](https://github.com/Minefalo/EconomySystem/actions/workflows/build.yml)
[![Paper](https://img.shields.io/badge/Paper-26.3-blue)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-25-orange)](https://adoptium.net/)
[![Version](https://img.shields.io/badge/Version-v0.2.0--alpha-gold)](https://github.com/Minefalo/EconomySystem/releases)

**EconomySystem** ist ein schlankes, aber vollständiges Wirtschafts-Plugin für Paper-Server. Es verwaltet Spielerguthaben mit `BigDecimal`, speichert standardmäßig in SQLite und wahlweise in YAML oder MySQL, kennt Zinsen und Kredite, bietet eine Bestenliste, PlaceholderAPI-Unterstützung, eine Vault-Schnittstelle und ein konfigurierbares GUI.

Die Dokumentation beschreibt den **tatsächlichen Stand dieses Repositorys**. Sie unterscheidet bewusst zwischen Funktionen, die vollständig umgesetzt sind, und solchen, die zwar konfiguriert oder geparst werden, aber noch keine Wirkung haben. Eine vollständige Liste steht in [Abschnitt 17](#17-bekannte-grenzen-und-noch-nicht-umgesetzte-funktionen).

> **Wichtig:** Dieses Plugin ist eine Alpha-Version. Vor dem produktiven Einsatz auf einem wichtigen Server zuerst eine Sicherung von Datenbank bzw. `plugins/EconomySystem/` anlegen und auf einem Testserver prüfen.

## Inhaltsverzeichnis

1. [Überblick](#1-überblick)
2. [Voraussetzungen](#2-voraussetzungen)
3. [Installation und erster Start](#3-installation-und-erster-start)
4. [Schnellstart](#4-schnellstart)
5. [Befehle](#5-befehle)
6. [Berechtigungen](#6-berechtigungen)
7. [Konfiguration](#7-konfiguration)
8. [Wirtschaft, Zinsen und Kredite](#8-wirtschaft-zinsen-und-kredite)
9. [Speicherung](#9-speicherung)
10. [Netzwerkbetrieb](#10-netzwerkbetrieb)
11. [GUI](#11-gui)
12. [PlaceholderAPI](#12-placeholderapi)
13. [Vault](#13-vault)
14. [Config-Updater und Backups](#14-config-updater-und-backups)
15. [Betrieb und Performance](#15-betrieb-und-performance)
16. [Fehlerbehebung und FAQ](#16-fehlerbehebung-und-faq)
17. [Bekannte Grenzen und noch nicht umgesetzte Funktionen](#17-bekannte-grenzen-und-noch-nicht-umgesetzte-funktionen)
18. [Entwicklung](#18-entwicklung)
19. [Lizenz](#19-lizenz)
- [Anhang A: Befehlsmatrix FULL/READ_ONLY](#anhang-a-befehlsmatrix-fullread_only)
- [Anhang B: Konfigurationsschlüssel](#anhang-b-konfigurationsschlüssel)
- [Anhang C: Nachrichten-Keys](#anhang-c-nachrichten-keys)
- [Anhang D: Glossar](#anhang-d-glossar)

---

## 1. Überblick

### 1.1 Funktionen

- **Guthaben mit `BigDecimal`:** konfigurierbare Nachkommastellen (0–8), Rundungsmodus, Startguthaben, Maximal- und Minimalguthaben.
- **UUID-first:** Konten werden über die UUID des Spielers geführt, nicht über den Namen. Namensänderungen verlieren das Guthaben nicht.
- **Drei Speicherarten:** SQLite als Standard ohne Einrichtung, YAML-Dateien für kleine Server und MySQL/MariaDB für größere oder vernetzte Server.
- **Zinsen:** automatischer Zyklus mit der Formel `y = a·√x`; Faktoren für Spar- und Kreditzins getrennt einstellbar.
- **Kredite:** Kreditrahmen, Laufzeit, Zinsen, automatische Tilgung aus Einnahmen, Fälligkeit, Zwangsabbuchung und Härtefall.
- **Bestenliste:** `/eco balance top` bzw. `/money top` mit Seiten, Cache und Placeholder-Top.
- **Überweisungen:** `/eco pay` lokal; optional idempotent über die Datenbank (`ANY`) mit Audit.
- **GUI:** vollständig konfigurierbare Menüs mit Items, Köpfen, Amboss-Eingaben, Spielerauswahl und Bestätigungsdialog.
- **PlaceholderAPI:** Guthaben, Ränge, Kreditdaten und Top-Platzhalter.
- **Vault:** Registrierung als Economy-Anbieter für Shops, Scoreboards und andere Plugins.
- **MiniMessage:** alle Nachrichten und Hilfetexte unterstützen moderne Farben, Hex-Farben und Formatierung.
- **Config-Updater:** ergänzt fehlende Schlüssel und Kommentare, sichert alte Dateien und entfernt veraltete GUI-Items.
- **Deutsch:** Standardnachrichten, Hilfe und Dokumentation sind deutsch; alle Texte sind anpassbar.

### 1.2 Was das Plugin bewusst nicht tut

- Es ist **kein** vollständiges Banken-Plugin mit Filialen, Zinsen pro Spieler, Steuern oder Auktionshaus.
- Es ersetzt **keine** Permission-Verwaltung. Permissions werden über ein externes Plugin wie LuckPerms vergeben.
- Es ist **kein** Proxy-Plugin. Für serverübergreifende Überweisungen wird eine gemeinsame MySQL-Datenbank benötigt; `PROXY` ist noch nicht implementiert.
- Es führt **keine** automatische Migration zwischen SQLite, YAML und MySQL durch. Ein Wechsel ist ein manueller, dokumentierter Vorgang.
- Es garantiert **keine** Transaktionen über mehrere Server hinweg. Die Lease schützt den Zinszyklus, nicht jede einzelne Buchung.
- Es ist **kein** Ersatz für ein Backup-System. Datenbank- und Dateisicherungen müssen extern erfolgen.

---

## 2. Voraussetzungen

| Komponente | Version / Hinweis |
|---|---|
| Server | Paper 1.21.x mit API-Version **26.3** (siehe `plugin.yml`) |
| Java | **Java 25** (vom Build und von Paper 26.3 vorausgesetzt) |
| Build | Maven **3.9+**; im Repository liegt kein Maven-Wrapper |
| Datenbank | **SQLite** als Standard (Datei unter `data/`, keine Einrichtung); optional MySQL 8.0+ oder MariaDB 10.6+ |
| PlaceholderAPI | optional, für Platzhalter |
| Vault | optional, für die Economy-Schnittstelle |
| Permission-Plugin | empfohlen: LuckPerms |

**Wichtig:** Die `pom.xml` baut gegen `io.papermc.paper:paper-api:26.3.build` (Versionsbereich `[26.3.build,)`). Ein reines Spigot- oder CraftBukkit-Server reicht nicht aus. Der Server muss die von Paper bereitgestellte API und Java 25 unterstützen.

---

## 3. Installation und erster Start

### 3.1 Plugin bauen

Im Projektordner:

```bash
mvn clean package
```

Das fertige Plugin liegt danach unter:

```text
target/EconomySystem-v0.2.0-alpha.jar
```

Nur diese JAR in den `plugins/`-Ordner des Servers kopieren. Die ebenfalls erzeugten Quell-/Original-JARs und der `target/`-Ordner werden nicht benötigt.

Falls `mvn` nicht im `PATH` liegt, die Maven-Installation der IDE oder eine lokale Maven-Distribution verwenden. In dieser Arbeitskopie liegt beispielsweise ein gebündeltes Maven unter `target/tools/apache-maven-3.9.11`; dieser Ordner ist **nicht** Teil des Repositorys und wird nicht mit ausgeliefert.

Tests separat:

```bash
mvn test
```

### 3.2 Installieren

1. Server stoppen.
2. `EconomySystem-v0.2.0-alpha.jar` nach `plugins/` kopieren.
3. Optional `PlaceholderAPI` und/oder `Vault` installieren.
4. Server starten.
5. Im Log prüfen, ob `EconomySystem` ohne Fehler aktiviert wurde.
6. Mit `/eco info` (Permission `economysystem.command.info` bzw. Admin) den Zustand prüfen.

### 3.3 Erster Start

Beim ersten Start legt das Plugin an:

```text
plugins/EconomySystem/
├── config/
│   ├── settings.yml
│   ├── messages.yml
│   ├── commands.yml
│   ├── roles.yml
│   ├── help.yml
│   ├── gui.yml
│   └── backup/               # nur bei Config-Updates
│       └── <datei>.vor-config-update.bak
└── data/                     # alle lokalen Speicherdaten
    ├── economy.db            # Standard: SQLite-Datenbank
    ├── economy.db-wal        # SQLite; nur bei laufendem Server
    ├── economy.db-shm        # SQLite; nur bei laufendem Server
    ├── accounts/             # nur bei storage.type: YAML
    │   └── <uuid>.yml
    └── pending-transaction.yml  # nur bei YAML; Journal
```

Die genaue Struktur kann je nach Speicherart und Config-Updater abweichen. `data/accounts/` wird nur bei YAML, `data/economy.db` nur bei SQLite angelegt und genutzt. Daten einer älteren Version, die noch direkt im Plugin-Ordner liegen, verschiebt das Plugin beim Start nach `data/`.

### 3.4 Ordnerstruktur im Überblick

| Pfad | Zweck |
|---|---|
| `config/settings.yml` | Wirtschaft, Zinsen, Kredite, Speicher, Netzwerk, Limits |
| `config/messages.yml` | Alle Chat-Nachrichten und die Kurzhilfe |
| `config/commands.yml` | Aliase, Kurzbefehle, Ersatzwörter, Tab-Vorschläge |
| `config/roles.yml` | Rollen mit vordefinierten Permissions |
| `config/help.yml` | Mehrseitige Admin-Hilfe (`/eco admin help`) |
| `config/gui.yml` | Menüs, Items, Eingaben, Bestätigungsdialog |
| `data/` | Alle lokalen Speicherdaten; Konfiguration bleibt unter `config/` |
| `data/economy.db` | SQLite-Datenbank (Standard): Konten, Kredite, Transfers, Audit, Lease |
| `data/economy.db-wal`, `data/economy.db-shm` | SQLite-Journaldateien; gehören zur Datenbank |
| `data/accounts/<uuid>.yml` | Ein Konto pro Spieler (YAML) |
| `data/pending-transaction.yml` | Wiederherstellungsjournal für unterbrochene YAML-Schreibvorgänge |
| `config/backup/...` | Automatische Sicherungen vor Config-Updates |

### 3.5 Rechte vergeben (LuckPerms)

Minimalvariante für normale Spieler:

```bash
lp group default permission set economysystem.command.balance true
lp group default permission set economysystem.command.balance.top true
lp group default permission set economysystem.command.pay true
lp group default permission set economysystem.command.credit true
lp group default permission set economysystem.command.gui true
```

Admin:

```bash
lp group admin permission set economysystem.command.admin true
lp group admin permission set economysystem.command.info true
lp group admin permission set economysystem.pay.remote true
```

Die alten Knoten (`economysystem.balance`, `economysystem.pay`, `economysystem.kredit`, `economysystem.gui`, `economysystem.admin`) funktionieren weiterhin. Details und Stolperfallen stehen in [Abschnitt 6](#6-berechtigungen).

### 3.6 Upgrade

1. Server stoppen.
2. **Sicherung** von `plugins/EconomySystem/` und – bei MySQL – der Datenbank anlegen.
3. Alte JAR durch die neue ersetzen.
4. Server starten. Der Config-Updater ergänzt fehlende Schlüssel und Kommentare.
5. Log auf Warnungen prüfen, insbesondere die Versionen in `config-version.yml` und fehlende Pfade.
6. Mit `/eco admin reload` (oder Neustart) die neuen Werte laden.

Der Updater **überschreibt keine vorhandenen Werte**. Er ergänzt fehlende Schlüssel, übernimmt die Standardkommentare der Vorlage und legt Sicherungen an. Eigene Kommentare an Stellen, für die die Vorlage einen Kommentar enthält, werden durch den Vorlagenkommentar ersetzt.

---

## 4. Schnellstart

Für einen einzelnen Server mit SQLite (Standard) reicht Folgendes:

```yaml
# plugins/EconomySystem/config/settings.yml
currency:
  starting-balance: "100.00"
  max-balance: "1000000000.00"

interest:
  enabled: true
  start-time: "00:01"
  interval-minutes: 1440
  factors:
    spar: "1.3"
    kredit: "1.3"

storage:
  type: SQLITE

network:
  mode: FULL
  transfer:
    mode: LOCAL
```

Danach:

```text
/eco balance
/eco pay Spieler 10
/eco credit take 100 30
/eco credit repay 50
/eco balance top
/eco gui
```

Wichtig:

- `storage.type` ist **`SQLITE`** (Standard), **`YAML`** oder **`MYSQL`** (Groß-/Kleinschreibung wird toleriert, aber diese Schreibweise ist empfohlen).
- `network.transfer.mode: LOCAL` bucht auch an offline Spieler, sofern das Konto bereits existiert.
- `interest.enabled: true` startet den Zyklus; `start-time` und `interval-minutes` bestimmen den Rhythmus.
- `currency.starting-balance` gilt nur für **neu angelegte** Konten. Bestehende Konten werden nicht angehoben.

---

## 5. Befehle

### 5.1 Aufbau

Das Plugin registriert den Wurzelbefehl `/eco` sowie mehrere Kurzbefehle. Alle Pfade sind unabhängig von Groß-/Kleinschreibung; `balance`, `BALANCE` und `Balance` sind gleichwertig.

Ein Aufruf besteht aus:

```text
/eco <pfad> [argumente] [--flag [wert]]
```

Flags müssen **am Ende** stehen. Alles ab dem ersten `--` wird als Flag behandelt und nicht mehr als normales Argument gezählt. Deshalb kann ein Spielername oder eine Begründung nicht mit `--` beginnen.

### 5.2 Kanonische Befehle

| Befehl | Bedeutung | Benötigtes Recht |
|---|---|---|
| `/eco` | Zeigt die Hilfe (Seite 1). | keins |
| `/eco help [<seite>]` | Hilfe; Seite 1 Spieler, Seite 2 Admin. | keins; Seite > 1 Admin |
| `/eco balance` | Eigenes Guthaben. | `economysystem.command.balance` |
| `/eco balance <konto>` | Guthaben eines anderen Kontos. | `economysystem.command.balance.other` |
| `/eco balance top [<seite>]` | Bestenliste. | `economysystem.command.balance.top` |
| `/eco pay <konto> <betrag> [--reason <text>]` | Überweisung. | `economysystem.command.pay` |
| `/eco credit` | Kreditübersicht (ohne Unterbefehl). | `economysystem.command.credit` |
| `/eco credit take <betrag> <tage>` | Kredit aufnehmen. | `economysystem.command.credit` |
| `/eco credit repay [<betrag>]` | Kredit zurückzahlen. | `economysystem.command.credit` |
| `/eco credit info` | Guthaben, Rahmen und Kredite anzeigen. | `economysystem.command.credit` |
| `/eco credit list` | Alle laufenden Kredite anzeigen. | `economysystem.command.credit` |
| `/eco gui [<menü>]` | GUI öffnen. | `economysystem.command.gui` |
| `/eco info` | Systeminformationen. | `economysystem.command.info` |
| `/eco admin` | Admin-Hilfe (Seite 1). | `economysystem.command.admin` |
| `/eco admin help [<seite>]` | Mehrseitige Admin-Hilfe aus `help.yml`. | `economysystem.command.admin` |
| `/eco admin info` | Systeminformationen. | `economysystem.command.admin` |
| `/eco admin balance add <konto> <betrag>` | Guthaben gutschreiben. | `economysystem.command.admin.balance` |
| `/eco admin balance remove <konto> <betrag>` | Guthaben abziehen. | `economysystem.command.admin.balance` |
| `/eco admin balance set <konto> <betrag> --confirm` | Guthaben exakt setzen. | `economysystem.command.admin.balance` |
| `/eco admin balance reset <konto> --confirm` | Auf Startguthaben zurücksetzen. | `economysystem.command.admin.balance` |
| `/eco admin credit list <konto>` | Kredite eines Spielers. | `economysystem.command.admin.credit` |
| `/eco admin interest show` | Aktuelle Zinsfaktoren. | `economysystem.command.admin.interest` |
| `/eco admin interest set <savings\|credit> <faktor>` | Zinsfaktor ändern. | `economysystem.command.admin.interest` |
| `/eco admin reload [local]` | Konfiguration neu laden. | `economysystem.command.admin.reload` |
| `/eco admin test interest-cycle add <anzahl>` | Zinszyklen sofort simulieren. | `economysystem.command.admin.test` **und** `tests.enabled` |

`<konto>` ist im Normalfall ein Spielername. Eine UUID-Schreibweise wie `#uuid:...` ist **nicht** implementiert; der Name wird über Online-Spieler, den Konten-Cache und – falls verfügbar – über den Speicher aufgelöst.

### 5.3 Kurzbefehle und Altformen

Diese Befehle werden in `plugin.yml` deklariert und über `commands.yml` auf Pfade abgebildet:

| Kurzbefehl | Entspricht | Hinweis |
|---|---|---|
| `/money` | `/eco balance` | Eigenes Guthaben |
| `/money <konto>` | `/eco balance <konto>` | Fremdes Guthaben |
| `/money top [<seite>]` | `/eco balance top [<seite>]` | Bestenliste |
| `/pay <konto> <betrag>` | `/eco pay ...` | Überweisung |
| `/kredit` | `/eco credit` | Ohne Argument: Übersicht |
| `/kredit add <betrag> <tage>` | `/eco credit take ...` | Kredit aufnehmen |
| `/kredit bezahlen [<betrag>]` | `/eco credit repay ...` | Kredit zurückzahlen |
| `/kredit info` | `/eco credit info` | Übersicht |
| `/kredit liste` | `/eco credit list` | Liste |
| `/economysystem` | `/eco help` | Hauptbefehl |
| `/economysystem help [<seite>]` | `/eco help ...` | Zusätzliches `help` wird entfernt |
| `/economysystem-gui [<menü>]` | `/eco gui ...` | GUI |
| `/economysystem-admin` | `/eco admin` | Admin-Hilfe |
| `/economysystem-admin help [<seite>]` | `/eco admin help ...` | Hilfe aus `help.yml` |
| `/economysystem-admin info` | `/eco admin info` | Systeminfo |
| `/economysystem-admin reload` | `/eco admin reload` | Reload |
| `/economysystem-admin money <konto> <add\|remove\|set> <betrag>` | `/eco admin balance ...` | Alte Reihenfolge: Konto vor Aktion |
| `/economysystem-admin money <konto> reset` | `/eco admin balance reset ...` | `--confirm` bleibt Pflicht |
| `/economysystem-admin zinsen <spar\|kredit> set <faktor>` | `/eco admin interest set ...` | Alte Reihenfolge: Typ vor `set` |
| `/economysystem-admin tests zins-zyklus add <anzahl>` | `/eco admin test interest-cycle add ...` | Nur mit `tests.enabled` |

**Altformen und `--confirm`:** Die alten Admin-Schreibweisen werden in den kanonischen Pfad übersetzt. Dadurch gilt auch dort: `set` und `reset` benötigen `--confirm`. Beispiel:

```text
/economysystem-admin money Spieler set 500 --confirm
/economysystem-admin money Spieler reset --confirm
```

### 5.4 Aliase

Die Aliase stehen in `commands.yml` und werden beim Laden registriert. Standard:

| Befehl | Aliase |
|---|---|
| `/economysystem` | `/es` |
| `/money` | `/geld`, `/balance`, `/coins` |
| `/pay` | `/zahlen`, `/ueberweisen` |
| `/kredit` | `/credit`, `/darlehen` |
| `/economysystem-gui` | `/eco-gui`, `/egui` |
| `/economysystem-admin` | `/esa`, `/ecoadm`, `/ecoadmin` |

`/eco` selbst hat standardmäßig **keine** Aliase. Ein Alias darf nur aus `a-z`, `0-9`, `_` und `-` bestehen und höchstens 32 Zeichen lang sein. Bereits belegte Befehle werden mit einer Warnung übersprungen.

### 5.5 Flags

| Flag | Status | Wirkung |
|---|---|---|
| `--confirm` | **wirksam** | Bestätigt `admin balance set` und `admin balance reset`. Ohne Flag wird der Befehl abgelehnt. |
| `--reason <text>` | geparst, **nicht gespeichert** | Wird akzeptiert und auf `commands.reason-max-length` gekürzt, aber von keinem Modul in einer Transaktion oder im Audit gespeichert. |
| `--silent` | **wirkungslos** | Wird geparst und ignoriert. |
| `--dry-run` | **wirkungslos** | Wird geparst und ignoriert; es wird **nicht** simuliert. |
| `--player <name>` | **wirkungslos** | Gehört zu `admin.audit`, das nicht implementiert ist. |
| `--action <aktion>` | **wirkungslos** | Gehört zu `admin.audit`, das nicht implementiert ist. |
| `--server <name>` | **wirkungslos** | Gehört zu `admin.network`, das nicht implementiert ist. |
| `--scope <scope>` | **wirkungslos** | Gehört zu `admin.network`, das nicht implementiert ist. |

**Wichtig:** `--dry-run` und `--silent` täuschen keine Sicherheit vor. Wer eine Änderung testen will, nutzt eine Sicherung oder einen Testserver.

### 5.6 Argumente

| Argument | Format | Beispiele |
|---|---|---|
| `<betrag>` | Zahl mit `.` oder `,` als Dezimaltrennzeichen; keine Währungssymbole | `10`, `10.50`, `10,50`, `1000` |
| `<konto>` | Spielername (1–16 Zeichen) oder aufgelöster Kontoname | `Notch`, `Spieler_1` |
| `<seite>` | ganze Zahl ab 1 | `1`, `2`, `10` |
| `<tage>` | ganze Zahl im Bereich `credits.min-days` bis `credits.max-days` | `7`, `30`, `365` |
| `<faktor>` | Zahl größer 0 | `1.3`, `0,5`, `2` |
| `<menü>` | Menü-ID aus `gui.yml` | `main`, `credit`, `admin` |
| `<anzahl>` | ganze Zahl 1 bis `tests.max-cycles` | `1`, `10`, `1000` |

Beträge werden auf `currency.decimals` gerundet. Bei `decimals: 2` wird `10.999` nach dem eingestellten Modus (Standard `HALF_UP`) zu `11.00`. Negative Beträge sind bei `/pay`, Krediten und den meisten Admin-Buchungen nicht erlaubt.

### 5.7 Beispiele

```text
/eco balance
/eco balance Notch
/eco balance top 2
/eco pay Notch 25.50
/eco pay Notch 25.50 --reason "Shop-Kauf"
/eco credit take 500 30
/eco credit repay 100
/eco credit info
/eco admin balance add Notch 100
/eco admin balance remove Notch 50
/eco admin balance set Notch 250 --confirm
/eco admin balance reset Notch --confirm
/eco admin interest show
/eco admin interest set savings 1.5
/eco admin interest set credit 1.2
/eco admin reload
/eco admin test interest-cycle add 10
```

### 5.8 Tab-Vervollständigung

Die Vervollständigung ist kontextabhängig:

- Pfade und Unterpfade werden aus dem Befehlsbaum vorgeschlagen.
- Spielernamen werden bei leerer oder einbuchstabiger Eingabe sofort aus allen Online-Spielern vorgeschlagen; ab zwei Zeichen kommen alle bekannten Konten hinzu – auch von offline Spielern.
- Beträge und Tage kommen aus `commands.yml` (`suggestions`) bzw. aus `settings.yml` (`suggestions`).
- Seitenzahlen werden aus der Trefferzahl berechnet.
- Flags werden nur dort vorgeschlagen, wo sie unterstützt werden.
- Bei fehlender Permission werden die zugehörigen Pfade nicht vorgeschlagen.

---

## 6. Berechtigungen

### 6.1 Moderne und alte Knoten

Das Plugin akzeptiert zwei Namensschemata:

- **Modern:** `economysystem.command.<pfad>` (empfohlen)
- **Alt:** `economysystem.<bereich>` (bleibt aus Kompatibilitätsgründen gültig)

Für die meisten Pfade genügt **einer** der beiden Knoten. Die folgende Tabelle zeigt die Entsprechungen.

| Funktion | Moderner Knoten | Alter Knoten | Standard |
|---|---|---|---|
| Eigenes Guthaben | `economysystem.command.balance` | `economysystem.balance` | true |
| Fremdes Guthaben | `economysystem.command.balance.other` | `economysystem.balance.others` | op |
| Bestenliste | `economysystem.command.balance.top` | `economysystem.balance.top` | true |
| Überweisen | `economysystem.command.pay` | `economysystem.pay` | true |
| Kredite | `economysystem.command.credit` | `economysystem.kredit` | true |
| GUI | `economysystem.command.gui` | `economysystem.gui` | true |
| Systeminfo | `economysystem.command.info` | – | op |
| Admin (Sammelrecht) | `economysystem.command.admin` | `economysystem.admin` | op |
| Guthabenverwaltung | `economysystem.command.admin.balance` | über `admin` | op |
| Kreditverwaltung | `economysystem.command.admin.credit` | über `admin` | op |
| Zinsen | `economysystem.command.admin.interest` | über `admin` | op |
| Reload | `economysystem.command.admin.reload` | `economysystem.reload` | op |
| Testbefehle | `economysystem.command.admin.test` | `economysystem.tests` | op |
| Fernüberweisung | `economysystem.pay.remote` | – | op |
| Cooldown umgehen | `economysystem.bypass.cooldown` | – | op |
| Rate-Limit umgehen | `economysystem.bypass.rate-limit` | – | op |

Zusätzlich lassen sich Permissions in der `roles.yml` zu Rollen zusammenfassen; eine Rolle ist selbst ein Permission-Knoten und wird bei allen Prüfungen des Plugins wie die aufgeführten Rechte behandelt (Abschnitt 6.5).

### 6.2 Vererbung und Sammelrechte

- `economysystem.command.admin` schaltet die Admin-Pfade auf Router-Ebene frei: `admin.balance`, `admin.credit`, `admin.interest`, `admin.reload` und `admin.test`. **Ausnahme:** Das Testmodul verlangt zusätzlich `economysystem.tests` oder `economysystem.command.admin.test`.
- `economysystem.command.admin.balance` schaltet `add`, `remove`, `set` und `reset` frei.
- `economysystem.command.admin.interest` schaltet `show` und `set` frei.
- `economysystem.command.credit` schaltet `take`, `repay`, `info` und `list` frei.
- `economysystem.command.balance` schaltet **nicht** `balance.top` frei. Die Bestenliste ist ein eigener Knoten.
- `economysystem.command.balance` schaltet **nicht** `balance.other` frei. Fremdes Guthaben ist ein eigener Knoten.
- Die alten Sammelrechte verhalten sich genauso: `economysystem.admin` öffnet die Admin-Pfade, `economysystem.reload` öffnet den Reload, `economysystem.tests` öffnet die Tests.

### 6.3 Die wichtigsten Stolperfallen

1. **`economysystem.command.balance.others` (Plural) wird nicht ausgewertet.** Der moderne Knoten heißt `economysystem.command.balance.other` (Singular). Der alte Knoten ist `economysystem.balance.others` (Plural). Wer nur den modernen Plural vergibt, bekommt bei fremdem Guthaben „keine Berechtigung“.
2. **`economysystem.tests` allein schaltet den Testpfad frei.** `economysystem.admin` allein genügt dort **nicht**, weil das Testmodul zusätzlich `economysystem.tests` oder `economysystem.command.admin.test` verlangt.
3. **`economysystem.reload` allein schaltet `admin.reload` frei.** Umgekehrt schaltet `economysystem.command.admin.reload` den Reload frei, ohne das volle Admin-Sammelrecht zu benötigen.
4. **`/eco info` braucht `economysystem.command.info`.** Das alte `economysystem.admin` öffnet zwar `/economysystem-admin info`, aber nicht automatisch `/eco info`.
5. **`economysystem.command.balance.top` und `economysystem.command.balance` sind getrennt.** Wer nur das Basisrecht vergibt, sieht keine Bestenliste.
6. **GUI-Items ohne `permission` sind für alle sichtbar.** Die Permission am Item ist optional; fehlt sie, wird das Item jedem angezeigt, der das Menü öffnen darf.
7. **Die GUI-Befehle werden mit den Rechten des Spielers ausgeführt.** Ein Item mit einem Admin-Befehl und ohne passende Permission führt beim Klick zu „keine Berechtigung“.
8. **`economysystem.bypass.*` wirkt nur auf Cooldown und Rate-Limit**, nicht auf fachliche Grenzen wie Maximalguthaben oder Kreditrahmen.

### 6.4 LuckPerms-Beispiele

Normaler Spieler:

```bash
lp group default permission set economysystem.command.balance true
lp group default permission set economysystem.command.balance.top true
lp group default permission set economysystem.command.pay true
lp group default permission set economysystem.command.credit true
lp group default permission set economysystem.command.gui true
```

Supporter, der fremde Guthaben sehen darf:

```bash
lp group supporter permission set economysystem.command.balance.other true
```

Admin:

```bash
lp group admin permission set economysystem.command.admin true
lp group admin permission set economysystem.command.info true
lp group admin permission set economysystem.pay.remote true
```

Nur Reload, ohne volles Admin-Recht:

```bash
lp user Moderator permission set economysystem.command.admin.reload true
```

Nur Testbefehle, ohne volles Admin-Recht:

```bash
lp user Tester permission set economysystem.tests true
```

### 6.5 Rollen aus `roles.yml`

In `config/roles.yml` lassen sich Permissions zu Rollen zusammenfassen. Eine Rolle ist selbst ein Permission-Knoten: Wer sie besitzt, erhält bei allen Rechtenprüfungen des Plugins zusätzlich die aufgeführten Permissions. Die Rollen ersetzen keine Permission-Verwaltung – der Rollen-Knoten muss weiterhin über LuckPerms (oder ein anderes Plugin) vergeben werden.

```yaml
roles:
  economysystem.role.admin:
    permissions:
      - economysystem.command.admin
      - economysystem.command.info
      - economysystem.pay.remote
```

Die Rolle wird wie eine normale Permission vergeben:

```bash
lp group admin permission set economysystem.role.admin true
```

Regeln:

- Groß-/Kleinschreibung wird nicht unterschieden.
- Eine Rolle darf eine andere Rolle aufführen; deren Permissions werden übernommen (maximal 10 Ebenen; Zyklen werden mit einer Warnung übersprungen).
- Wildcards sind erlaubt: `economysystem.command.*` und `*`.
- Statt `permissions:` darf unter einer Rolle auch direkt eine Liste stehen.
- Ungültige oder leere Einträge werden beim Laden gemeldet und übersprungen.
- Änderungen werden mit `/eco admin reload` geladen. Der Rollen-Knoten selbst wird nicht automatisch vergeben.
- Die Rollen gelten bei Befehlen, GUI-Items, der Guthaben-Nachricht beim Beitritt und der Zinsbenachrichtigung.

---

## 7. Konfiguration

### 7.1 Dateien und Reload

| Datei | Inhalt | Reload über `/eco admin reload` |
|---|---|---|
| `settings.yml` | Wirtschaft, Zinsen, Kredite, Speicher, Netzwerk, Limits | ja |
| `messages.yml` | Nachrichten und Kurzhilfe | ja |
| `commands.yml` | Aliase, Kurzbefehle, Ersatzwörter, Vorschläge | ja |
| `roles.yml` | Rollen mit vordefinierten Permissions | ja |
| `help.yml` | Admin-Hilfe | ja |
| `gui.yml` | Menüs und Items | ja |
| `config-version.yml` | Versionen aller Konfigurationsdateien | – |

`/eco admin reload` lädt die Konfiguration neu. Ein laufender Zinszyklus wird nicht abgebrochen; bereits geplante Kredite behalten ihre Werte. Ein vollständiger Serverneustart ist nur nötig, wenn ein Alias oder ein Befehl in `plugin.yml` geändert wurde, denn diese Registrierung erfolgt beim Start.

### 7.2 `settings.yml`

Alle Werte werden beim Laden validiert. Ungültige Werte werden auf den Standard zurückgesetzt und in der Konsole gemeldet. Nach Änderungen immer `/eco admin reload` ausführen und die Konsole prüfen.

#### Allgemein

| Schlüssel | Standard | Bedeutung / Bereich |
|---|---|---|
| `time-zone` | `""` | Zeitzone für Zinsplan und Datumsanzeigen, z. B. `Europe/Berlin`. Leer = Serverzeitzone. Im Netzwerk müssen alle Server dieselbe Zeitzone verwenden. |

#### Tests

| Schlüssel | Standard | Bedeutung / Bereich |
|---|---|---|
| `tests.enabled` | `false` | Schaltet `/eco admin test` und `/economysystem-admin tests` frei. **Verändert echte Kontostände.** |
| `tests.max-cycles` | `1000` | Maximale Anzahl Zyklen pro Testaufruf (1–10000). |

#### Währung

| Schlüssel | Standard | Bedeutung / Bereich |
|---|---|---|
| `currency.name` | `Coins` | Anzeigename, z. B. in `%currency%`. |
| `currency.symbol` | `$` | Symbol für `%symbol%`. |
| `currency.format` | `%symbol%%amount%` | Anzeigeformat; Platzhalter `%symbol%`, `%amount%`, `%name%`. |
| `currency.number-format` | `#,##0.00` | Java-`DecimalFormat`-Muster. |
| `currency.locale` | `de-DE` | Trennzeichen, z. B. `1.234,56`. |
| `currency.decimals` | `2` | Nachkommastellen (0–8). |
| `currency.rounding` | `HALF_UP` | `UP`, `DOWN`, `CEILING`, `FLOOR`, `HALF_UP`, `HALF_DOWN`, `HALF_EVEN`. |
| `currency.starting-balance` | `100.00` | Startguthaben für neue Konten. |
| `currency.max-balance` | `1000000000.00` | Maximalguthaben; `-1` = unbegrenzt. |
| `currency.min-balance` | `-1000.00` | Untergrenze für erzwungene Abbuchungen; `0` = kein Dispo, negative Werte erlauben Dispo. |

**Hinweis zu `max-balance`:** `-1` bedeutet „unbegrenzt“ auf Plugin-Ebene. Bei MySQL begrenzt die Spalte `DECIMAL(30,8)` den Wert dennoch praktisch. Wer größere Werte braucht, muss das Schema anpassen.

#### Zinsen

| Schlüssel | Standard | Bedeutung / Bereich |
|---|---|---|
| `interest.enabled` | `true` | Automatischen Zinszyklus aktivieren. |
| `interest.start-time` | `00:01` | Erster Lauf, Format `HH:mm` in `time-zone`. |
| `interest.interval-minutes` | `1440` | Abstand in Minuten (1–525600); 1440 = täglich, 10080 = wöchentlich. |
| `interest.factors.spar` | `1.3` | Faktor `a` der Sparzinsformel `y = a·√x`; muss > 0 sein. |
| `interest.factors.kredit` | `1.3` | Faktor `a` der Kreditformel `y = x·m + a`; muss > 0 sein. |
| `interest.notify-players` | `true` | Online-Spieler nach der Gutschrift informieren. |
| `interest.executor` | `auto` | `auto` = Server mit der Lease; oder eine konkrete `network.id`. |

**Wichtig:** `interest.executor` wirkt nur mit einer gemeinsamen MySQL-Datenbank wirklich serverübergreifend. Bei SQLite gilt die Lease nur für die eigene Datei, bei YAML gibt es keine gemeinsame Lease-Datenbank; der Zyklus läuft dort lokal. `READ_ONLY` stoppt den geplanten Zinszyklus **nicht** (siehe Abschnitt 10.5).

#### Kredite

| Schlüssel | Standard | Bedeutung / Bereich |
|---|---|---|
| `credits.enabled` | `true` | Kreditsystem aktivieren. |
| `credits.max-loans` | `3` | Maximale gleichzeitige Kredite pro Spieler (1–100). |
| `credits.limit-multiplier` | `3.0` | Kreditrahmen = Multiplikator × (Guthaben − offene Kredite); muss > 0 sein. |
| `credits.slope` | `0.5` | Steigung `m` der Formel `y = x·m + a`; muss > 0 sein. |
| `credits.minimum-amount` | `10.00` | Mindestbetrag eines Kredits. |
| `credits.min-days` | `1` | Minimale Laufzeit in Tagen. |
| `credits.max-days` | `365` | Maximale Laufzeit in Tagen (1–3650). |
| `credits.check-interval-seconds` | `60` | Prüfintervall für fällige Kredite (5–3600). |
| `credits.date-format` | `dd.MM.yyyy HH:mm` | Java-`DateTimeFormatter` für Fälligkeiten. |

#### Bestenliste

| Schlüssel | Standard | Bedeutung / Bereich |
|---|---|---|
| `leaderboard.page-size` | `10` | Einträge pro Seite (1–50). |
| `leaderboard.max-entries` | `100` | Maximal geladene Einträge; mindestens `page-size`, höchstens 1000. |
| `leaderboard.include-zero-balances` | `false` | Konten mit Guthaben 0 aufnehmen. Negative Guthaben werden nie angezeigt. |

#### Placeholder-Cache

| Schlüssel | Standard | Bedeutung / Bereich |
|---|---|---|
| `placeholder.cache-ttl-seconds` | `30` | Lebensdauer gecachter Offline-Guthaben (1–3600). |
| `placeholder.negative-cache-ttl-seconds` | `10` | Lebensdauer für „kein Konto vorhanden“ (1–3600). |
| `placeholder.cache-retention-seconds` | `300` | Aufräumgrenze für alte Cache-Einträge; mindestens `cache-ttl`, höchstens 86400. |
| `placeholder.top-size` | `50` | Plätze der Platzhalter-Top-Liste (1–1000). |
| `placeholder.top-refresh-seconds` | `60` | Aktualisierungsintervall der Top-Liste (5–3600). |

#### Vault

| Schlüssel | Standard | Bedeutung / Bereich |
|---|---|---|
| `vault.enabled` | `true` | Registrierung als Vault-Wirtschaftsanbieter. Ohne Vault wirkungslos. |
| `vault.priority` | `HIGH` | `LOWEST`, `LOW`, `NORMAL`, `HIGH`, `HIGHEST`. |
| `vault.timeout-ms` | `3000` | Wartezeit für noch nicht geladene Konten (500–30000 ms). |
| `vault.currency-name-singular` | `""` | Einzahlungsname für Vault; leer = `currency.name`. |

#### Benachrichtigungen

| Schlüssel | Standard | Bedeutung / Bereich |
|---|---|---|
| `notifications.balance-on-join` | `true` | Guthaben kurz nach dem Beitritt anzeigen. |
| `notifications.balance-on-join-delay-ticks` | `20` | Verzögerung in Ticks (0–200); 20 = 1 Sekunde. |

**Wichtig:** Die Join-Nachricht wird nur gesendet, wenn der Spieler `economysystem.balance` (den alten Knoten) besitzt. Wer nur den modernen Knoten vergibt, sieht die Nachricht nicht. Das ist eine bekannte Einschränkung.

#### Vorschläge

| Schlüssel | Standard | Bedeutung |
|---|---|---|
| `suggestions.amounts` | `1, 10, 100, 1000, 10000` | Vorschläge für `/pay`. |
| `suggestions.credit-amounts` | `100, 500, 1000, 5000` | Vorschläge für Kreditbeträge. |
| `suggestions.repay-amounts` | `10, 50, 100, 500` | Vorschläge für Rückzahlungen. |
| `suggestions.credit-days` | `7, 30, 90` | Vorschläge für Laufzeiten. |

#### Netzwerk

| Schlüssel | Standard | Bedeutung / Bereich |
|---|---|---|
| `network.id` | `default` | Eindeutiger Servername in der Datenbank. |
| `network.group` | `default` | Gruppe für gruppenweite Wirtschaftsräume. |
| `network.economy-scope` | `shared` | `shared`, `group`, `server`. **Teilt heute keine Konten** – nur Scope-Schlüssel für Lease, Transfer und Audit. |
| `network.mode` | `FULL` | `FULL` oder `READ_ONLY`. |
| `network.invalidation` | `TTL` | `NONE`, `TTL`, `PLUGIN_MESSAGE`, `REDIS`; derzeit **ohne Wirkung**. |
| `network.cache-ttl-seconds` | `60` | Cache-TTL (0–86400); derzeit **ohne Wirkung**. |
| `network.lease-ttl-seconds` | `300` | Lease-Laufzeit für den Zinszyklus (60–3600). |
| `network.transfer.mode` | `LOCAL` | `LOCAL`, `PROXY`, `ANY`. |
| `network.transfer.timeout-seconds` | `30` | Timeout (1–600); derzeit **ohne Wirkung**. |

**Wichtig:** `economy-scope` ist **keine** Kontenpartitionierung. Alle Server lesen und schreiben dieselben Konten; der Wert fließt nur in den Lease-/Transfer-/Audit-Scope ein. Wer getrennte Guthaben pro Server will, braucht getrennte Datenbanken oder eine noch nicht implementierte Scope-Funktion.

#### Rate-Limits

`network.limits` ist verschachtelt. Jeder Eintrag hat `count` (erlaubte Aufrufe je Fenster; 0 = unbegrenzt), `seconds` (Fensterlänge) und `cooldown-seconds` (Mindestabstand; 0 = keiner).

| Pfad | Standard |
|---|---|
| `pay` | 10 Aufrufe / 60 s, 3 s Cooldown |
| `credit.take` | 3 Aufrufe / 300 s, 10 s Cooldown |
| `admin.balance` | 60 Aufrufe / 60 s, kein Cooldown |
| `gui` | unbegrenzt / 60 s, 2 s Cooldown |
| `balance.top` | unbegrenzt / 60 s, 2 s Cooldown |

Die Limits werden **vor** der Fachlogik geprüft. `economysystem.bypass.rate-limit` ignoriert `count`, `economysystem.bypass.cooldown` ignoriert `cooldown-seconds`.

#### Audit

| Schlüssel | Standard | Bedeutung / Bereich |
|---|---|---|
| `audit.enabled` | `true` | Audit für Fernbuchungen schreiben. |
| `audit.retention-days` | `0` | Aufbewahrung in Tagen (0–3650); 0 = unbegrenzt. |

**Wichtig:** Audit wird derzeit **nur** für `remoteTransferAsync` geschrieben und **nur** bei SQLite und MySQL. YAML-Audit ist ein No-op. Lokale `/pay`-Buchungen erzeugen keinen Audit-Eintrag.

#### Speicher

| Schlüssel | Standard | Bedeutung / Bereich |
|---|---|---|
| `storage.type` | `SQLITE` | `SQLITE`, `YAML` oder `MYSQL`. |
| `storage.save-interval-seconds` | `300` | Auto-Save-Intervall; 0 = nur beim Verlassen/Stoppen. |
| `storage.shutdown-timeout-seconds` | `60` | Maximale Wartezeit beim Stoppen (5–600). |
| `storage.sqlite.file` | `economy.db` | Datenbankdatei relativ zum `data`-Ordner. |
| `storage.yaml.folder` | `accounts` | Ordner relativ zum `data`-Ordner. |
| `storage.mysql.host` | `localhost` | Datenbankhost. |
| `storage.mysql.port` | `3306` | Port (1–65535). |
| `storage.mysql.database` | `minecraft` | Datenbankname. |
| `storage.mysql.username` | `economysystem` | Datenbankbenutzer. |
| `storage.mysql.password` | `""` | Passwort; Datei schützen. |
| `storage.mysql.table-prefix` | `economy_` | Präfix der Tabellen; nur `A-Z`, `a-z`, `0-9`, `_`; darf leer sein. |
| `storage.mysql.ssl-mode` | `""` | `VERIFY_IDENTITY`, `VERIFY_CA`, `REQUIRED`, `PREFERRED`, `DISABLED`; leer = `use-ssl`. |
| `storage.mysql.use-ssl` | `true` | Veraltet; nur wenn `ssl-mode` leer ist. |
| `storage.mysql.connection-timeout-ms` | `5000` | Verbindungs-Timeout (1000–60000). |
| `storage.mysql.socket-timeout-ms` | `30000` | Socket-Timeout (1000–120000). |
| `storage.mysql.retry-seconds` | `5` | Wartezeit nach Verbindungsfehler (1–300). |

**Sicherheitshinweis:** `storage.yaml.folder` und `storage.sqlite.file` werden ungeprüft als Pfad verwendet. Ein Wert wie `../../` kann aus dem `data`-Ordner ausbrechen. Nur vertrauenswürdige Werte eintragen.

#### Config und Befehle

| Schlüssel | Standard | Bedeutung / Bereich |
|---|---|---|
| `config.backup-count` | `1` | Sicherungen je Datei (1–20); ältere liegen als `.1`, `.2`, ... |
| `commands.reason-max-length` | `160` | Maximale Länge einer `--reason`-Begründung (1–160). |

### 7.3 `commands.yml`

Diese Datei ändert **nur Schreibweisen**, niemals die Befehlsstruktur.

- **`subcommand-words`:** Synonyme für Pfadwörter. `balance: ["guthaben", "kontostand"]` macht `/eco guthaben` zu `/eco balance`. Die Ersetzung erfolgt vor dem Routing. Ein Wort, das selbst ein Pfad ist, sollte nicht gleichzeitig Synonym eines anderen Pfades sein.
- **`commands.<befehl>.aliases`:** Zusätzliche Namen für einen in `plugin.yml` deklarierten Befehl. Erlaubt sind `a-z`, `0-9`, `_`, `-`, maximal 32 Zeichen. Bereits belegte Namen werden übersprungen.
- **`shortcuts`:** Ordnet einem Befehl einen Pfad zu. `money` → `balance`, `kredit` → `credit`, `economysystem-admin` → `admin`. Neue Unterbefehle brauchen **keinen** Eintrag; sie sind Teil des Baums.
- **`suggestions`:** Vorschläge für die Tab-Vervollständigung je Pfad und Argument. Sie greifen nur, wenn das Modul selbst keine Vorschläge liefert. Fehlt ein Eintrag, gelten die Standards aus `settings.yml`.

Der Wurzelbefehl `/eco` wird dynamisch registriert. `commands.eco.aliases` ist standardmäßig leer. `es` bleibt bewusst beim Befehl `economysystem`, damit die bestehende Befehlshistorie und Berechtigungen weiter funktionieren.

Einträge mit unbekanntem Pfad werden beim Laden gemeldet und bleiben wirkungslos. Nach Änderungen `/eco admin reload` ausführen.

### 7.4 `messages.yml`

Alle Texte sind MiniMessage. Unterstützt werden unter anderem `<red>`, `<bold>`, `<gray>`, `<#RRGGBB>` und `<reset>`. `%prefix%` kann überall eingesetzt werden; fehlt er, wird der Präfix automatisch vorangestellt.

- **Fallback:** Alte Schlüssel wie `no-permission`, `usage-...`, `pay-recipient-offline` bleiben gültig. Die neuen Schlüssel unter `command.*` haben Vorrang. Fehlt ein Schlüssel, wird der Schlüsselname angezeigt und eine Warnung geloggt.
- **`command._default`:** Allgemeine Fehler und Syntax. `command._default.error.no-permission`, `player-only`, `unknown-subcommand`, `confirm-required`, `rate-limited`, `read-only`.
- **`command.pay.*`:** Fehler und Erfolg für Überweisungen, inklusive `remote-sent`, `duplicate`, `no-route`.
- **`command.admin.*`:** Admin-spezifische Ausgaben, z. B. `interest.show` und `credit.list.*`.
- **`help` / `help-admin`:** Die Listen für `/eco help` (Seite 1) und `/eco help 2`. **Nicht** die `help.yml`.
- **`info`:** Die Zeilen für `/eco info` und `/eco admin info`.
- **`credit-*`:** Kreditstatus, Aufnahme, Rückzahlung, Zwangsabbuchung, Härtefall.
- **`gui-*`:** Amboss-Eingaben, Bestätigung, Spielerauswahl, Eingabefehler.
- **`tests-*`:** Testbefehle.
- **`vault-status-*`:** Status in `/eco info`.
- **`unlimited`, `disabled`, `enabled`:** Anzeigehilfen.

Die Version von `messages.yml` steht in `config-version.yml` und wird vom Updater gepflegt. Nach Änderungen `/eco admin reload` ausführen.

### 7.5 `help.yml`

`help.yml` wird **nur** von `/eco admin help [<seite>]` und `/economysystem-admin help` gelesen. `/eco help` verwendet die Listen `help` und `help-admin` aus `messages.yml`.

- Jede Seite hat `title` und `lines`.
- `%title%`, `%page%` und `%pages%` werden im Seitentitel und in der Seitenanzeige ersetzt.
- Die Navigation ist klickbar und führt `/label help <seite>` aus.
- Wörtliche spitze Klammern werden mit `\<...>` geschrieben, z. B. `\<Betrag>`.
- Die Anzahl der Seiten ergibt sich aus der Liste. Zu große Seitenzahlen werden auf die letzte Seite begrenzt, zu kleine auf die erste.

### 7.6 `gui.yml`

`gui.yml` definiert Menüs und Items. Wichtige Felder:

| Feld | Bedeutung |
|---|---|
| `settings.enabled` | GUI-System aktivieren. |
| `settings.default-menu` | Menü, das `/eco gui` ohne Argument öffnet. |
| `settings.close-on-click` | Inventar vor dem Ausführen eines Befehls schließen. |
| `settings.click-cooldown-ms` | Mindestabstand zwischen zwei Klicks auf dasselbe Item (0–5000). |
| `menus.<id>.title` | Inventartitel (MiniMessage). |
| `menus.<id>.rows` | 1–6 Reihen. |
| `menus.<id>.permission` | Menü-Permission; fehlt sie, darf jeder das Menü öffnen, der `/eco gui` darf. |
| `menus.<id>.fill` | Füllitem für leere Slots. |
| `items.<id>.slot` | Slot 0 bis `rows * 9 - 1`. |
| `items.<id>.material` | Bukkit-Materialname. |
| `items.<id>.head` | `player` = Kopf des öffnenden Spielers. |
| `items.<id>.name` / `lore` | Anzeige; Platzhalter werden beim Öffnen ersetzt. |
| `items.<id>.permission` | Optional. **Fehlt sie, ist das Item für alle sichtbar.** |
| `items.<id>.commands` | Befehle, die beim Klick ausgeführt werden. |
| `items.<id>.open` | Öffnet ein anderes Menü statt Befehle auszuführen. |
| `items.<id>.inputs` | Eingaben vor dem Ausführen (siehe unten). |

**Platzhalter in `name` und `lore`:** `%player%`, `%balance%`, `%min_balance%`, `%max_balance%`, `%credit_limit%`, `%credit_debt%`, `%credit_loans%`, `%credit_max_loans%`, `%credit_status%`.

**Platzhalter in `commands`:** `{player}`, `{input}` (alle Antworten mit Leerzeichen verbunden), `{input1}`, `{input2}`, ...

**Eingabetypen:**

- `number`: Amboss-Eingabe; `min` und `max` begrenzen den Wert und dürfen Dezimalwerte wie `0.01` sein, harte Obergrenze 1.000.000.000.000. Alle Zeichen außer Ziffern werden entfernt, ein Komma zählt als Dezimalpunkt (`4h5 7.4` → `457.4`, `1+6/d,o84` → `16.84`).
- `text`: Amboss-Eingabe; optional `pattern` (regulärer Ausdruck). Ohne Pattern gilt: ein einzelner Wert ohne Leerzeichen und ohne `--`. Maximale Länge 64 Zeichen.
- `player`: Kopfauswahl mit Suche, Filter (`all`/`online`) und Sortierung (`name-asc`, `name-desc`, `balance-desc`, `balance-asc`). Ein leeres Suchfeld bzw. nur Leerzeichen im Nametag löscht die Suche; ein eigenes Item setzt Suche, Filter und Sortierung zurück. Ein Klick auf einen Kopf öffnet eine Bestätigung mit allen Kontodaten im Tooltip; Abbrechen führt zurück zur Auswahl.

**Bestätigung:** Befehle, die `admin balance set` oder `admin balance reset` enthalten, öffnen vor dem Ausführen einen Bestätigungsdialog. Der Klick auf das Item und die Eingaben davor gelten nicht als Bestätigung. Der Dialog lässt sich abbrechen.

**Befehlsausführung:** Die Befehle werden so ausgeführt, als hätte der Spieler sie selbst eingegeben. Es gelten seine Permissions, Cooldowns und Rate-Limits. Ein GUI-Item kann daher keine Rechte umgehen.

**Layout-Migration:** Liegt die Version von `gui.yml` in `config-version.yml` unter 4, entfernt der Updater die alten Einzel-Items `balance`, `kredit-add`, `kredit-info`, `kredit-pay` und `kredit-pay-part` aus dem Hauptmenü. Das aktuelle Hauptmenü zeigt stattdessen den Spielerkopf und einen Kredit-Button. Eigene Items mit anderen IDs bleiben erhalten.

### 7.7 `plugin.yml`

`plugin.yml` wird mit dem Plugin ausgeliefert und beschreibt:

- Hauptklasse, API-Version (`26.3`), Load-Reihenfolge (`POSTWORLD`).
- optionale Abhängigkeiten `PlaceholderAPI` und `Vault`.
- die Bibliotheken `org.xerial:sqlite-jdbc:3.54.1.0` und `com.mysql:mysql-connector-j:9.1.0`, die Paper automatisch lädt.
- die Kurzbefehle und ihre `usage`-Texte.
- die Berechtigungen mit Beschreibung und Standardwert.

Die eigentlichen Unterbefehle stehen **nicht** in `plugin.yml`, sondern im Befehlsbaum. Wer einen neuen Unterbefehl hinzufügt, muss dort keinen Eintrag ergänzen.

### 7.8 Datenfiles

Alle lokalen Speicherdaten liegen im Ordner `data/` des Plugins; `storage.sqlite.file` und `storage.yaml.folder` sind relativ dazu.

Bei `storage.type: SQLITE` (Standard) liegen alle Daten in einer Datei `data/economy.db`. Die Datei nur bei gestopptem Server kopieren oder zusammen mit `data/economy.db-wal` und `data/economy.db-shm` sichern.

Bei `storage.type: YAML` liegt pro Konto eine Datei unter `data/accounts/<uuid>.yml`. Die Datei enthält die UUID, den zuletzt bekannten Namen, das Guthaben und – falls vorhanden – Kreditdaten. Die Dateien werden beim Start indexiert. Dazu kommen `data/loans.yml`, `data/api-transactions.yml`, `data/audit.yml` und das Journal `data/pending-transaction.yml`.

- **Manuelle Änderungen** an Kontodateien werden erst nach einem Neustart bzw. erneutem Indexieren sichtbar. `/eco admin reload` lädt die Konfiguration, aber nicht den Kontenindex neu.
- **Defekte Dateien** werden beim Indexieren übersprungen und in der Konsole gemeldet. Die Datei bleibt liegen und kann manuell geprüft werden.
- **`pending-transaction.yml`** ist ein Journal für unterbrochene Schreibvorgänge. Sie nicht manuell bearbeiten, außer zur Wiederherstellung nach einem Absturz (siehe Abschnitt 9.2).

---

## 8. Wirtschaft, Zinsen und Kredite

### 8.1 Beträge, Rundung und Grenzen

Alle Beträge werden intern als `BigDecimal` geführt. An der Vault-Grenze und in der GUI werden sie in `double` bzw. Anzeigezeichenketten umgewandelt; dort kann es zu den üblichen Gleitkomma-Rundungen kommen. Innerhalb des Plugins wird mit der konfigurierten Skala und dem konfigurierten Rundungsmodus gearbeitet.

- `currency.decimals` bestimmt die Nachkommastellen (0–8).
- `currency.rounding` bestimmt, wie gerundet wird (Standard `HALF_UP`).
- `currency.starting-balance` gilt nur für neue Konten.
- `currency.max-balance` begrenzt Gutschriften. `-1` bedeutet „unbegrenzt“; bei MySQL ist die Spalte `DECIMAL(30,8)` die praktische Obergrenze, SQLite speichert Beträge als Text ohne diese Grenze.
- `currency.min-balance` ist die Untergrenze für **erzwungene** Abbuchungen (fällige Kredite). Normale Abbuchungen und `/pay` lassen kein negatives Guthaben zu.
- `amount-too-large` wird geprüft, bevor eine Gutschrift erfolgt. Bei einer Überweisung wird das Zielkonto geprüft, nicht nur das Quellkonto.

### 8.2 Zinsen

Der Sparzins folgt der Formel:

```text
y = a · √x
```

- `x` = aktueller Kontostand
- `a` = `interest.factors.spar`
- `y` = Zinsbetrag, der gutgeschrieben wird

Eigenschaften:

- Konten mit Guthaben `<= 0` erhalten keinen Zins.
- Ist `x` bereits `max-balance` oder darüber, wird kein Zins gutgeschrieben.
- Würde die Gutschrift das Maximalguthaben überschreiten, wird sie auf den verbleibenden Betrag gekürzt.
- Der Betrag wird auf `currency.decimals` gerundet.
- Der Zyklus läuft für **alle gespeicherten Konten**, auch für offline Spieler.
- Online-Spieler erhalten bei `interest.notify-players: true` eine Nachricht.

**Zeitplan:** `interest.start-time` ist die erste Ausführung, danach alle `interest.interval-minutes`. Die Zeit wird in `time-zone` interpretiert. Bei einem Neustart wird der nächste Lauf aus dem Zeitplan berechnet; ein verpasster Lauf wird nicht rückwirkend für jeden einzelnen Tag nachgeholt. Für Tests gibt es `/eco admin test interest-cycle add <anzahl>`.

**Lease:** Bei SQLite und MySQL versucht der Zyklus, eine Lease für den Scope zu erwerben. Nur der Halter führt den Lauf aus; `interest.executor` kann zusätzlich einen bestimmten Server festlegen. Bei SQLite gilt sie nur für die eigene Datenbankdatei, bei YAML gibt es keine gemeinsame Lease; der Zyklus läuft dort lokal.

### 8.3 Kredite

**Kreditrahmen:**

```text
Rahmen = limit-multiplier × (Guthaben − offene Kredite)
```

Ist das Eigenkapital `(Guthaben − offene Kredite)` null oder negativ, wird **kein** Kredit vergeben. Offene Kredite umfassen Principal und Zinsen.

**Zinsen:**

```text
y = x · m + a
```

- `x` = Laufzeit in Tagen
- `m` = `credits.slope`
- `a` = `interest.factors.kredit`
- `y` = absoluter Zinsbetrag (nicht Prozent)

Beispiel mit `slope: 0.5`, `kredit: 1.3`, 30 Tagen:

```text
y = 30 · 0.5 + 1.3 = 16.3
```

Bei 100 Principal und 30 Tagen Laufzeit wären also 116.30 zurückzuzahlen.

**Regeln:**

- Maximal `credits.max-loans` gleichzeitige Kredite pro Spieler.
- Laufzeit zwischen `credits.min-days` und `credits.max-days`.
- Betrag mindestens `credits.minimum-amount`.
- Die Auszahlung darf `currency.max-balance` nicht überschreiten.
- Die Auszahlung wird sofort gutgeschrieben; Kredit und Auszahlung werden gemeinsam gespeichert.
- Bei der Rückzahlung wird der **älteste** Kredit zuerst bedient.
- Manuell wird nie mehr abgebucht, als auf dem Konto ist; das Konto kann dadurch nicht ins Minus rutschen.

**Fälligkeit und Tilgung:**

- Die Fälligkeit ist `jetzt + Laufzeit in Tagen`.
- Ein fälliger Kredit wird zwangsweise bedient, bis das Guthaben `currency.min-balance` erreicht.
- Bleibt danach etwas offen, werden die restlichen **Zinsen** erlassen und der Kredit geht in die automatische Tilgung. Alle künftigen Einnahmen (z. B. `/pay`-Eingänge, Zinsgutschriften) werden sofort zur Rückzahlung verwendet.
- Wird der Kredit vollständig bezahlt, wird er gelöscht und der Spieler informiert.
- Bei `credits.enabled: false` können **keine neuen** Kredite aufgenommen werden; bestehende Kredite werden weiter geprüft und getilgt.

### 8.4 Einnahmen und Tilgung

Die automatische Tilgung greift nach jeder Gutschrift auf das Konto. Das betrifft insbesondere:

- Zinsgutschriften,
- eingehende Überweisungen,
- Vault-Einzahlungen,
- Admin-Gutschriften.

Die Tilgung läuft auf dem Haupt-Thread, weil sie Benachrichtigungen auslöst. Bei sehr vielen gleichzeitigen Gutschriften kann das kurzzeitig spürbar sein; in der Praxis ist die Anzahl der Kredite pro Spieler durch `max-loans` begrenzt.

---

## 9. Speicherung

### 9.1 SQLite (Standard)

Aktiv mit:

```yaml
storage:
  type: SQLITE
  sqlite:
    file: "economy.db"
```

**Ablage:**

```text
plugins/EconomySystem/
└── data/
    ├── economy.db
    ├── economy.db-wal
    └── economy.db-shm
```

`storage.sqlite.file` ist relativ zu `data/`. `economy.db-wal` und `economy.db-shm` legt SQLite selbst an; sie gehören zur Datenbank.

**Eigenschaften:**

- Die Datenbank wird beim Start automatisch angelegt. Es ist keine Einrichtung und kein externer Dienst nötig.
- Geldbeträge werden als Text gespeichert und als `BigDecimal` gelesen; es entstehen keine Rundungsfehler.
- Konten, Kredite, Transfers, Idempotenzschlüssel, Audit, Lease und Zinsläufe liegen in derselben Datei. Schreibvorgänge laufen in einer Transaktion.
- SQLite gilt für **einen** Server. Mehrere Server mit eigenen Dateien teilen kein Guthaben; für ein Netzwerk MySQL verwenden.
- Die Datei nur bei gestopptem Server kopieren oder zusammen mit `data/economy.db-wal` und `data/economy.db-shm` sichern.
- `storage.sqlite.file` ist relativ zu `data/` und wird ungeprüft als Pfad verwendet. Keine `..`-Pfade aus nicht vertrauenswürdigen Quellen eintragen.

### 9.2 YAML

Aktiv mit:

```yaml
storage:
  type: YAML
  yaml:
    folder: "accounts"
```

**Ablage:**

```text
plugins/EconomySystem/
└── data/
    ├── accounts/
    │   └── <uuid>.yml
    ├── loans.yml
    ├── api-transactions.yml
    ├── audit.yml
    └── pending-transaction.yml
```

`storage.yaml.folder` ist relativ zu `data/`. `pending-transaction.yml` liegt **nicht** im `accounts/`-Ordner, sondern direkt in `data/`. Es ist das Wiederherstellungsjournal für unterbrochene Schreibvorgänge.

**Eigenschaften:**

- Beim Start werden alle Kontodateien indexiert. Der Index wird für Namenssuche und Bestenliste verwendet.
- Schreibvorgänge werden über eine temporäre Datei und einen atomaren Move ersetzt, damit keine halb geschriebene Kontodatei entsteht.
- Kredit- und Kontosicherungen können über das Journal gemeinsam wiederhergestellt werden.
- Eine defekte Kontodatei wird beim Indexieren übersprungen und in der Konsole gemeldet. Die Datei bleibt erhalten.
- Manuelle Änderungen an Kontodateien werden erst nach einem Neustart bzw. erneutem Indexieren sichtbar. `/eco admin reload` lädt nur die Konfiguration.
- `storage.yaml.folder` ist relativ zu `data/` und wird ungeprüft als Pfad verwendet. Keine `..`-Pfade aus nicht vertrauenswürdigen Quellen eintragen.

**Wiederherstellung nach einem Absturz:**

1. Server stoppen.
2. `plugins/EconomySystem/` sichern.
3. Prüfen, ob `data/pending-transaction.yml` vorhanden ist. Das Plugin versucht beim Start, die dort vermerkten Änderungen anzuwenden.
4. Erst danach den Server starten und die Konsole beobachten.
5. Die Datei nicht löschen, solange unklar ist, ob die Transaktion abgeschlossen wurde.

### 9.3 MySQL

Aktiv mit:

```yaml
storage:
  type: MYSQL
  mysql:
    host: "127.0.0.1"
    port: 3306
    database: "minecraft"
    username: "economysystem"
    password: "***"
    table-prefix: "economy_"
    ssl-mode: "VERIFY_IDENTITY"
```

**Tabellen** (mit `table-prefix: economy_`):

| Tabelle | Zweck |
|---|---|
| `economy_accounts` | UUID, Name, Guthaben, `updated_at`, `revision` |
| `economy_loans` | Kredite inklusive Principal, Zinsen, Restschuld, Fälligkeit, `auto_collect` |
| `economy_transfers` | Fernbuchungen mit Idempotenzschlüssel und Status |
| `economy_audit` | Audit-Einträge für Fernbuchungen |
| `economy_locks` | Lease für Zinszyklus/Aufräumaufgaben |
| `economy_interest_runs` | Bereits ausgeführte Zinszyklen je Scope und Zyklus-Schlüssel |

**Wichtige Spalten:**

- `accounts.balance`, `loans.principal`, `loans.interest`, `loans.principal_remaining`, `loans.interest_remaining`, `transfers.amount`, `audit.amount`, `audit.balance_after` sind `DECIMAL(30,8)`.
- `accounts.uuid` ist `CHAR(36)` und Primärschlüssel.
- `accounts.name` ist `VARCHAR(64)` und indiziert.
- `transfers.idempotency_key` ist eindeutig.
- `audit.tx_id` ist eindeutig; `audit.reason` ist `VARCHAR(160)`.
- `accounts.revision` wird bei älteren Tabellen automatisch ergänzt.

**TLS:**

- `ssl-mode: VERIFY_IDENTITY` prüft Zertifikat **und** Hostname. Das ist die sicherste Variante.
- `VERIFY_CA` prüft nur die CA.
- `REQUIRED` verschlüsselt, prüft aber kein Zertifikat.
- `PREFERRED` versucht TLS, fällt sonst zurück.
- `DISABLED` deaktiviert TLS.
- Ist `ssl-mode` leer, wird `use-ssl` ausgewertet: `true` → `VERIFY_IDENTITY`, `false` → `DISABLED`.

**Verbindung:** Bei einem Verbindungsfehler wartet das Plugin `storage.mysql.retry-seconds`, bevor es erneut versucht. `connection-timeout-ms` und `socket-timeout-ms` begrenzen die Wartezeit.

**Transaktionen:** Lokale Überweisungen und Kreditänderungen werden in Datenbanktransaktionen gebucht. Fernbuchungen verwenden einen Idempotenzschlüssel, damit derselbe Transfer nicht doppelt angewendet wird. Ein erneuter **Befehl** erzeugt jedoch einen neuen Schlüssel und ist damit keine automatische Wiederholungssicherung (siehe Abschnitt 10.9).

### 9.3 Konsistenz und Auto-Save

- Konten werden im Speicher gehalten und asynchron über einen einzelnen Storage-Thread gesichert.
- `storage.save-interval-seconds` bestimmt das Auto-Save-Intervall; `0` deaktiviert das periodische Speichern (Speichern beim Verlassen/Stoppen bleibt).
- Beim Stoppen wartet das Plugin bis `storage.shutdown-timeout-seconds` auf offene Speichervorgänge.
- Ein harter Serverabsturz kann die letzten Sekunden seit dem letzten Save verlieren. Das Journal hilft bei unterbrochenen YAML-Transaktionen, ersetzt aber kein Backup.
- `READ_ONLY` verhindert keine geplanten Zins- oder Kreditbuchungen (siehe 10.5).

### 9.4 Backup und Restore

**YAML:**

```bash
# Server vorher stoppen!
tar -czf economy-backup-$(date +%F).tar.gz plugins/EconomySystem/
```

Restore: Server stoppen, den Ordner `plugins/EconomySystem/` durch die Sicherung ersetzen, Server starten. Darauf achten, dass `data/pending-transaction.yml` und `data/accounts/` zusammenpassen.

**MySQL:**

```bash
mysqldump --single-transaction --routines --triggers \
  -u economysystem -p minecraft > economy-backup.sql
```

Restore:

```bash
mysql -u economysystem -p minecraft < economy-backup.sql
```

Nach einem Restore den Server neu starten, damit der Konten-Cache nicht mit alten Werten weiterarbeitet. Bei laufendem Server nicht einfach die Datenbank zurückspielen.

### 9.5 Migration

Es gibt **keinen** eingebauten Konverter zwischen YAML und MySQL. Ein Wechsel ist ein manueller Vorgang:

1. Server stoppen.
2. Vollständige Sicherung von `plugins/EconomySystem/` und der Zieldatenbank anlegen.
3. Zielspeicher konfigurieren.
4. Konten übertragen. Bei YAML → MySQL müssen UUID, Name und Guthaben in `<prefix>accounts` geschrieben werden; Kredite in `<prefix>loans`.
5. Server starten und mit `/eco balance` sowie `/eco balance top` prüfen.
6. Erst nach erfolgreicher Prüfung die alte Speicherart entfernen.

**Wichtig:** Kein Skript aus diesem Repository verwenden, ohne es vorher an das eigene Schema anzupassen. Die Tabellenstruktur kann sich zwischen Versionen ändern. Bei Unsicherheit zuerst auf einem Testserver migrieren.

---

## 10. Netzwerkbetrieb

### 10.1 Begriffe

- **Scope:** Ein Schlüssel, der Lease, Transfers und Audit gruppiert. Er wird aus `network.economy-scope` und `network.group` gebildet.
- **Lease:** Zeitlich begrenzte Sperre in der Datenbank (SQLite, MySQL), damit nur ein Server den Zinszyklus ausführt.
- **Transfer:** Buchung zwischen zwei Konten. `LOCAL`, `PROXY` oder `ANY`.
- **Audit:** Protokolleintrag für eine Fernbuchung.
- **READ_ONLY:** Servermodus, in dem schreibende Befehle und Vault-Buchungen abgelehnt werden.

### 10.2 `economy-scope` teilt keine Konten

**Das ist die wichtigste Einschränkung:** `network.economy-scope` ist **keine** Partitionierung der Konten. Alle Server mit derselben Datenbank lesen und schreiben dieselben `accounts`-Zeilen. Der Wert beeinflusst nur:

- den Scope-Schlüssel der Lease,
- den Scope-Schlüssel der Fernbuchung,
- den Scope-Schlüssel des Audits.

`shared`, `group` und `server` führen daher **nicht** zu getrennten Guthaben. Wer getrennte Wirtschaftsräume braucht, muss getrennte Datenbanken oder ein noch nicht implementiertes Scope-Feature verwenden.

### 10.3 Überweisungsmodi

| Modus | Verhalten | Voraussetzungen |
|---|---|---|
| `LOCAL` | Ziel darf offline sein; gebucht wird auf das gespeicherte Konto. | Standard; keine Netzwerkfunktion. |
| `PROXY` | Ziel darf auf einem anderen Server online sein. | **Nicht implementiert:** antwortet mit `command.pay.error.no-route`. |
| `ANY` | Ziel darf offline sein; Buchung per UUID über die Datenbank. | `storage.type: MYSQL` oder `SQLITE` und `economysystem.pay.remote`. |

Ablauf bei `ANY`:

1. Das Zielkonto muss bereits existieren. Ein unbekannter Name führt zu `player-not-found`.
2. Der Betrag wird geparst und normalisiert.
3. Es wird ein `TransferData` mit neuem Idempotenzschlüssel erzeugt.
4. `remoteTransferAsync` bucht in der Datenbank (SQLite oder MySQL); das Ergebnis ist `APPLIED`, `DUPLICATE`, `INSUFFICIENT_FUNDS`, `MAX_BALANCE`, `UNSUPPORTED` oder `FAILED`.
5. Bei `APPLIED` erhält der Spieler eine Nachricht mit der tx-id.

**Wichtig:** Bei `ANY` wird die Maximalguthaben-Prüfung vom Speicher durchgeführt. Bei `LOCAL` prüft das Plugin vor der Buchung. Verlässt man sich auf `ANY`, sollte `max-balance` in der Datenbank und im Plugin konsistent sein.

### 10.4 Lease und Zinszyklus

Bei SQLite und MySQL:

- Der Zinszyklus bildet einen Zyklus-Schlüssel aus Zeitplan und Scope.
- Vor der Ausführung wird eine Lease in `<prefix>locks` erworben.
- Nach erfolgreicher Ausführung wird der Lauf in `<prefix>interest_runs` vermerkt.
- Ein anderer Server mit derselben Lease kann den Lauf nicht gleichzeitig ausführen.
- `network.lease-ttl-seconds` bestimmt, wann eine abgelaufene Lease übernommen werden darf.
- Bei SQLite gilt die Lease nur innerhalb dieser Datenbankdatei; mehrere Server mit eigenen Dateien teilen sie nicht.

Bei YAML:

- Es gibt keine gemeinsame Lease-Datenbank. `acquireLease` liefert lokal immer „frei“.
- Mehrere Server mit jeweils eigenen YAML-Dateien teilen kein Guthaben. Ein „Netzwerk“ über YAML ist daher nur eine Sammlung unabhängiger Wirtschaften.

`interest.executor`:

- `auto` = der Server, der die Lease erhält.
- Ein konkreter `network.id` = nur dieser Server darf den Zyklus ausführen. Ist er nicht erreichbar, läuft der Zyklus nicht, bis er wieder da ist.

### 10.5 READ_ONLY

`network.mode: READ_ONLY` bewirkt:

- Der Command-Router lehnt **schreibende** Pfade mit `command._default.error.read-only` ab.
- Vault-`withdrawPlayer` und `depositPlayer` liefern einen Fehler.
- `createPlayerAccount` liefert `false`.

**Nicht** blockiert:

- der geplante Zinszyklus,
- die fällige Kredit-Tilgung,
- automatische Einnahmen-Tilgung,
- direkte Speicherzugriffe außerhalb des Routers.

`READ_ONLY` ist damit **kein** globaler Schreibschutz. Wer wirklich alle Geldbewegungen stoppen will, muss zusätzlich `interest.enabled: false` setzen, Kredite pausieren oder den Server offline nehmen. Das ist eine bekannte Einschränkung.

### 10.6 Audit

Audit-Einträge werden derzeit nur geschrieben für:

- `remoteTransferAsync` (Fernbuchung), und
- `storage.type: SQLITE` oder `MYSQL`.

Nicht geschrieben werden:

- lokale `/pay`-Buchungen,
- Admin-Guthabenänderungen,
- Zinsgutschriften,
- Kreditaufnahmen und -rückzahlungen,
- YAML-Buchungen (No-op).

`audit.retention-days` räumt täglich Einträge des **aktuellen** Scopes auf. `0` bedeutet unbegrenzt. Die Aufräumaufgabe läuft nur, wenn SQLite oder MySQL aktiv ist.

### 10.7 Rate-Limits

Die Limits aus `network.limits` werden vor der Fachlogik geprüft. Ein Limit besteht aus:

- `count`: erlaubte Aufrufe im Fenster; `0` = unbegrenzt,
- `seconds`: Fensterlänge,
- `cooldown-seconds`: Mindestabstand zwischen zwei Aufrufen; `0` = keiner.

Betroffen sind die konfigurierten Pfade `pay`, `credit.take`, `admin.balance`, `gui` und `balance.top`. `economysystem.bypass.rate-limit` ignoriert `count`, `economysystem.bypass.cooldown` ignoriert den Cooldown.

### 10.8 Checkliste für Multi-Server

1. Alle Server verwenden **dieselbe** MySQL-Datenbank und dasselbe `table-prefix`.
2. `network.id` ist auf jedem Server **eindeutig**.
3. `network.group` ist auf Servern derselben Gruppe gleich.
4. `time-zone` ist auf allen Servern gleich; sonst bilden sie unterschiedliche Zyklus-Schlüssel.
5. `storage.type: MYSQL` auf allen Servern.
6. `network.transfer.mode` ist auf allen Servern gleich (`ANY` für Offline-Ziele).
7. `economysystem.pay.remote` ist für die Spieler/Gruppen gesetzt, die fernüberweisen dürfen.
8. `READ_ONLY` ist auf Lobby-Servern gesetzt, aber Zinsen/Kredite sind dort bewusst eingeplant.
9. Firewall erlaubt MySQL vom jeweiligen Server; TLS ist korrekt konfiguriert.
10. Backup läuft zentral auf der Datenbank, nicht pro Server.

### 10.9 Grenzen des Netzwerkbetriebs

- `PROXY` hat keine Serverliste und antwortet mit `no-route`.
- `network.invalidation` wird gelesen, aber nicht angewendet.
- `network.cache-ttl-seconds` wird gelesen, aber der Kontencache hat keine TTL-Prüfung.
- `network.transfer.timeout-seconds` wird gelesen, aber es gibt keine Rückbuchung nach Timeout.
- `economy-scope` partitioniert keine Konten.
- Die Idempotenz schützt denselben Transfer-Datensatz. Ein erneuter `/pay`-Befehl erzeugt einen **neuen** Schlüssel und kann daher doppelt buchen.
- Lokale `/pay`-Buchungen nennen keine tx-id; nur `ANY`-Fernbuchungen tun das.
- `--reason` wird nicht im Audit gespeichert.
- Es gibt keine automatische Konfliktauflösung, wenn zwei Server denselben Spieler gleichzeitig ändern. Die Datenbanktransaktionen verhindern verlorene Updates auf Zeilenebene, aber nicht fachliche Konflikte.

---

## 11. GUI

### 11.1 Aufbau

Das GUI wird über `gui.yml` definiert. Jedes Menü hat:

- `title`, `rows`, optional `permission`,
- optional `fill` für leere Slots,
- `items` mit `slot`, `material`, `name`, `lore`.

`/eco gui [<menü>]` öffnet das Menü. Ohne Argument wird `settings.default-menu` verwendet. Ein unbekanntes Menü führt zu `gui-not-found`.

### 11.2 Sichtbarkeit und Rechte

- Das Menü selbst kann eine `permission` haben. Fehlt sie, reicht die Permission für `/eco gui`.
- Ein Item kann eine `permission` haben. **Fehlt sie, ist das Item für alle sichtbar.** Das ist bewusst so, kann aber dazu führen, dass ein Item angezeigt wird, dessen Befehl der Spieler nicht ausführen darf.
- Beim Klick werden die Befehle mit den Rechten des Spielers ausgeführt. Ein GUI-Item ist **keine** Rechteumgehung.
- Der Bestätigungsdialog für `set`/`reset` ist ebenfalls an die Rechte des Spielers gebunden.

### 11.3 Items und Platzhalter

`head: player` zeigt den Kopf des Spielers, der das Menü öffnet. `%player%`, `%balance%`, `%min_balance%`, `%max_balance%`, `%credit_limit%`, `%credit_debt%`, `%credit_loans%`, `%credit_max_loans%` und `%credit_status%` werden beim Öffnen ersetzt.

`fill` füllt alle leeren Slots; Items mit höherem Slot überschreiben das Füllitem. `slot` muss im Bereich `0` bis `rows * 9 - 1` liegen.

### 11.4 Eingaben

Vor dem Ausführen eines Befehls können Eingaben abgefragt werden:

- **number:** Amboss-Eingabe. Der Wert liegt zwischen `min` und `max`, die auch Dezimalwerte wie `0.01` sein dürfen; maximal 1.000.000.000.000. Beim Bestätigen werden alle Zeichen außer Ziffern entfernt; das erste Komma oder der erste Punkt wird zum Dezimalpunkt. `4h5 7.4` wird zu `457.4`, `1+6/d,o84` zu `16.84`.
- **text:** Amboss-Eingabe. Ohne `pattern` gilt: ein einzelner Wert ohne Leerzeichen und ohne `--`, maximal 64 Zeichen. Mit `pattern` muss die Antwort dem regulären Ausdruck entsprechen.
- **player:** Kopfauswahl mit Suche, Filter (`all`/`online`) und Sortierung (`name-asc`, `name-desc`, `balance-desc`, `balance-asc`). Ein leeres Suchfeld bzw. nur Leerzeichen im Nametag löscht die Suche; ein eigenes Item setzt Suche, Filter und Sortierung zurück. Ein Klick auf einen Kopf öffnet eine Bestätigung mit allen Kontodaten im Tooltip; Abbrechen führt zurück zur Auswahl.

Die Antworten ersetzen `{input1}`, `{input2}`, ...; `{input}` setzt alle Antworten mit Leerzeichen verbunden ein. `{player}` ist der Name des Spielers.

### 11.5 Bestätigung

Enthält ein Befehl `admin balance set` oder `admin balance reset`, öffnet sich vor der Ausführung ein Bestätigungsdialog. Der Klick auf das Item und die Eingaben davor gelten **nicht** als Bestätigung. Erst der Klick auf „Bestätigen“ führt den Befehl aus; „Abbrechen“ schließt den Dialog ohne Änderung.

### 11.6 Klick-Cooldown und Schließen

- `settings.click-cooldown-ms` verhindert Doppelklicks auf dasselbe Item. Der Cooldown gilt pro Spieler und Slot, nicht global.
- `settings.close-on-click` schließt das Inventar, bevor der Befehl ausgeführt wird. Das verhindert, dass Spieler Items aus dem Inventar nehmen.
- Das GUI ist kein echtes Inventar zum Mitnehmen; Items werden nur zur Anzeige und Interaktion verwendet.

### 11.7 Layout-Migration

Liegt die Version von `gui.yml` in `config-version.yml` unter 4, entfernt der Updater die alten Einzel-Items `balance`, `kredit-add`, `kredit-info`, `kredit-pay` und `kredit-pay-part` aus dem Hauptmenü. Das aktuelle Hauptmenü zeigt stattdessen den Spielerkopf und einen Kredit-Button. Eigene Items mit anderen IDs bleiben erhalten.

---

## 12. PlaceholderAPI

PlaceholderAPI muss installiert sein. Die Erweiterung wird automatisch registriert und bleibt über Reloads bestehen (`persist`).

| Platzhalter | Bedeutung |
|---|---|
| `%money_%` | Eigenes Guthaben des Spielers, der den Platzhalter auswertet. |
| `%money_<player>%` | Guthaben eines Spielers. |
| `%eco_top_<rank>%` | Guthaben auf Platz `<rank>` (nur Zahl, ohne Währungssymbol). |
| `%eco_top_<rank>_name%` | Name auf Platz `<rank>`. |
| `%eco_top_<rank>_raw%` | Rohwert (`toPlainString`). |
| `%aktuelle_sparzinsen%` | Sparzins, der für das eigene Guthaben anfallen würde. |
| `%aktuelle_sparzinsen_<player>%` | Sparzins für einen Spieler. |
| `%economysystem_money%` | Wie `%money_%`. |
| `%economysystem_money_<player>%` | Wie `%money_<player>%`. |
| `%economysystem_balance%` | Wie `%money_%`. |
| `%economysystem_balance_<player>%` | Wie `%money_<player>%`. |
| `%economysystem_sparzinsen%` | Wie `%aktuelle_sparzinsen%`. |
| `%economysystem_sparzinsen_<player>%` | Wie `%aktuelle_sparzinsen_<player>%`. |
| `%economysystem_top_<rank>%` | Wie `%eco_top_<rank>%`. |
| `%economysystem_top_<rank>_name%` | Wie `%eco_top_<rank>_name%`. |
| `%economysystem_top_<rank>_raw%` | Wie `%eco_top_<rank>_raw%`. |

**Hinweise:**

- Alle Geld-Platzhalter liefern ausschließlich die Zahl im konfigurierten Zahlenformat, ohne Währungszeichen wie `$` oder `€`.
- Es gibt **keine** Kredit-Platzhalter. `%credit_limit%` usw. funktionieren nur in `gui.yml`, nicht in PlaceholderAPI.
- Offline-Guthaben werden asynchron geladen und kurz gecacht. Der erste Aufruf kann leer sein, bis der Cache gefüllt ist.
- `placeholder.cache-ttl-seconds` und `placeholder.negative-cache-ttl-seconds` steuern die Aktualität.
- Die Top-Liste wird alle `placeholder.top-refresh-seconds` neu berechnet und enthält höchstens `placeholder.top-size` Einträge.
- `%eco_top_...%` liefert leere Zeichenketten, wenn der Rang außerhalb der Liste liegt.

---

## 13. Vault

Vault ist optional. Ist es installiert und `vault.enabled: true`, registriert sich EconomySystem als Economy-Anbieter mit `vault.priority`.

**Unterstützt:**

- `getBalance`, `has`, `withdrawPlayer`, `depositPlayer`,
- `hasAccount` (String und OfflinePlayer),
- `createPlayerAccount` (OfflinePlayer), `format`, `fractionalDigits`,
- `currencyNamePlural`, `currencyNameSingular`.

**Nicht unterstützt:**

- Bankkonten (`createBank`, `bankBalance`, `bankWithdraw`, ...) → `NOT_IMPLEMENTED`.

**Besonderheiten:**

- `hasAccount(OfflinePlayer)` liefert **immer `true`**, solange der Spieler nicht `null` ist. Das ist für Plugins gedacht, die nur prüfen, ob ein Spieler existiert; es bedeutet **nicht**, dass bereits ein Konto gespeichert ist.
- `hasAccount(String)` prüft dagegen, ob ein Konto bekannt ist.
- Lesende Zugriffe legen **keine** Konten an.
- `createPlayerAccount(String)` kann ohne UUID kein neues Konto anlegen und liefert nur `true`, wenn das Konto bereits existiert.
- `createPlayerAccount(OfflinePlayer)` legt bei Bedarf ein Konto mit Startguthaben an.
- Bei `READ_ONLY` schlagen `withdrawPlayer` und `depositPlayer` fehl; `createPlayerAccount` liefert `false`.
- Vault arbeitet mit `double`. Werte werden an der Grenze validiert und auf die konfigurierten Nachkommastellen geprüft. Sehr große Werte können die `double`-Genauigkeit verlieren.
- Schreibende Vault-Zugriffe laufen auf dem Haupt-Thread und warten höchstens `vault.timeout-ms`. Bei Timeout wird die Aufgabe abgebrochen, damit keine verspätete Buchung erfolgt.

---

## 14. Config-Updater und Backups

Beim Start prüft der Updater jede ausgelieferte Konfigurationsdatei:

- Fehlt die Datei, wird sie aus der JAR-Vorlage angelegt.
- Fehlende Schlüssel werden ergänzt.
- Standardkommentare der Vorlage werden übernommen.
- Eigene Werte, Texte, Listen und zusätzliche Schlüssel bleiben erhalten.
- Die Version jeder Datei steht in `config/config-version.yml` und wird auf den größeren Wert aus vorhandener Version und Vorlage gesetzt. Den alten Schlüssel `config-version` in den einzelnen Dateien übernimmt der Updater und entfernt ihn.
- Bei `messages.yml`, `help.yml` und `gui.yml` werden Legacy-Farbcodes (`&a`, `§a`) in MiniMessage umgewandelt. In `gui.yml` sind `permission`, `material`, `commands`, `open`, `head`, `type`, `pattern`, `filter`, `sort` und `path` als Nicht-Text-Schlüssel ausgenommen.
- In `settings.yml` und `commands.yml` findet **keine** Farbcode-Umwandlung statt.

**Backups:** Vor jeder Änderung wird die alte Datei gesichert unter:

```text
plugins/EconomySystem/config/backup/<datei>.vor-config-update.bak
```

Bei `config.backup-count > 1` liegen ältere Sicherungen als `<datei>.vor-config-update.1.bak`, `.2.bak`, ... daneben. Die neueste Sicherung behält den Namen ohne Nummer.

**Wichtig:**

- Ist die vorhandene Version in `config-version.yml` **höher** als die der Vorlage, wird eine Warnung geloggt. Die Datei bleibt erhalten; die Kommentare stammen aus der Vorlage.
- Ist die vorhandene Datei kein YAML-Mapping, wird sie nicht überschrieben; die Konsole meldet den Fehler.
- Der Updater entfernt nur bei `gui.yml` mit einer Version unter 4 die alten Einzel-Items. Andere eigene Schlüssel werden nicht gelöscht.
- Nach einem Update die Backups prüfen und erst dann löschen, wenn die neue Datei funktioniert.

---

## 15. Betrieb und Performance

### 15.1 Auto-Save und Shutdown

- Konten werden asynchron gesichert. Der Storage-Thread ist ein einzelner Thread; dadurch werden Schreibvorgänge serialisiert.
- `storage.save-interval-seconds` steuert das periodische Speichern. `0` bedeutet: nur beim Verlassen des Spielers und beim Stoppen.
- Beim Stoppen wartet das Plugin bis `storage.shutdown-timeout-seconds`; danach werden offene Aufgaben abgebrochen.
- Ein `/stop` ist sauberer als ein harter Kill. Bei einem harten Kill können die letzten Änderungen verloren gehen.

### 15.2 Reload

`/eco admin reload` lädt Einstellungen, Nachrichten, Befehle, Hilfe und GUI neu. Es ist **kein** vollständiger Serverneustart:

- Laufende Zinszyklen werden nicht abgebrochen.
- Bereits geplante Kredite behalten ihre Werte.
- Der Kontenindex bei YAML wird nicht neu aufgebaut; dafür ist ein Neustart nötig.
- Änderungen an `plugin.yml` (neue Befehle/Aliase) benötigen einen Neustart.

### 15.3 Performance

- Die Konten liegen im Speicher; Lesezugriffe sind schnell.
- Schreibzugriffe laufen über einen einzelnen Storage-Thread. Bei sehr vielen gleichzeitigen Änderungen kann die Queue wachsen; die Konsole zeigt keine eigene Queue-Größe.
- Die Bestenliste wird aus dem Speicher und dem Konten-Cache zusammengeführt. `leaderboard.max-entries` begrenzt die Arbeit.
- Placeholder-Tops werden periodisch neu berechnet; zu kurze Intervalle erhöhen die Last.
- Der Zinszyklus läuft über alle gespeicherten Konten. Bei sehr großen Datenbeständen dauert er länger; `interest.interval-minutes` sollte groß genug sein.
- Die GUI-Spielerauswahl lädt Konten asynchron. Bei vielen Konten kann die erste Anzeige kurz leer sein.

### 15.4 Log und Diagnose

- `/eco info` zeigt Speicher, Währung, Zinsen, Vault-Status und Cache-Größe.
- Die Konsole meldet fehlende Nachrichten-Keys, ungültige Konfigurationswerte, defekte YAML-Dateien sowie SQLite- und MySQL-Fehler.
- Bei SQLite zuerst Dateirechte und freien Speicherplatz prüfen; die Datei nie im laufenden Betrieb kopieren.
- Bei MySQL-Verbindungsproblemen zuerst Host, Port, Benutzer, Passwort, Datenbank und TLS prüfen.
- Bei Permission-Problemen zuerst die modernen und alten Knoten aus Abschnitt 6 prüfen.

---

## 16. Fehlerbehebung und FAQ

### 16.1 Start und Installation

**Das Plugin lädt nicht. Was zuerst prüfen?**

1. Paper-Version und API-Version `26.3` prüfen.
2. Java-Version prüfen: Das Plugin benötigt Java 25.
3. Konsole auf den ersten Fehler oberhalb der `EconomySystem`-Meldungen prüfen.
4. Prüfen, ob die JAR wirklich im `plugins/`-Ordner liegt und nicht doppelt (alte und neue Version).
5. Prüfen, ob `Vault`/`PlaceholderAPI` zwar installiert, aber inkompatibel sind. Beide sind optional; zum Testen ohne sie starten.

**`/eco` ist unbekannt.**

Das Plugin ist nicht aktiviert oder die JAR wurde nicht geladen. `/eco` wird dynamisch registriert; es erscheint nicht zwingend in der `plugin.yml`-Befehlsliste. Prüfe die Konsole und `/plugins`. Ein Serverneustart ist nach dem Austausch der JAR nötig.

**Wo liegt die JAR?**

Nach `mvn clean package` unter `target/EconomySystem-v0.2.0-alpha.jar`. Nur diese Datei kopieren.

**Der Config-Ordner wird nicht angelegt.**

Das Plugin wurde nicht aktiviert oder hat keine Schreibrechte im Serververzeichnis. Prüfe die Konsole und die Dateirechte.

**Eine Version in `config-version.yml` ist höher als die Vorlage.**

Die Datei stammt aus einer neueren Version. Der Updater loggt eine Warnung und lässt die Werte stehen; die Kommentare kommen aus der Vorlage. Das ist kein Fehler, aber ein Downgrade kann inkompatible Schlüssel hinterlassen. Sicherung anlegen und die Datei mit der Vorlage vergleichen.

**Der Build scheitert mit „Could not resolve dependencies“.**

Maven braucht Internetzugang zum Paper-Repository oder einen gefüllten lokalen Cache. Prüfe `~/.m2/settings.xml`, Proxy und Firewall. Im Repository liegt kein Maven-Wrapper.

**`mvn test` scheitert, aber `mvn package` läuft?**

`package` führt normalerweise auch die Tests aus. Prüfe den ersten Fehler im Surefire-Report unter `target/surefire-reports/`. Ein fehlendes Offline-Artefakt ist ein Umgebungsproblem, kein Testfehler.

### 16.2 Befehle und Rechte

**Ich habe `economysystem.command.balance`, aber `/eco balance top` sagt „keine Berechtigung“.**

Die Bestenliste ist ein eigener Knoten: `economysystem.command.balance.top` (oder `economysystem.balance.top`).

**Ich habe `economysystem.command.balance.others` (Plural) vergeben, aber fremdes Guthaben geht nicht.**

Der moderne Knoten ist **Singular**: `economysystem.command.balance.other`. Der alte Knoten ist `economysystem.balance.others` (Plural). `economysystem.command.balance.others` wird nicht ausgewertet.

**`/eco info` geht nicht, obwohl ich Admin bin.**

`/eco info` verlangt `economysystem.command.info`. Das alte `economysystem.admin` öffnet nur `/economysystem-admin info`. Vergib zusätzlich `economysystem.command.info` oder nutze die Admin-Altform.

**`/eco admin reload` geht nicht, obwohl ich Admin bin.**

Prüfe `economysystem.command.admin.reload` oder `economysystem.reload`. Das volle `economysystem.command.admin` sollte ebenfalls reichen; wenn nicht, prüfe, ob ein anderes Plugin den Befehl abfängt.

**`/eco admin test ...` geht nicht, obwohl ich `economysystem.admin` habe.**

Das Testmodul verlangt zusätzlich `economysystem.tests` oder `economysystem.command.admin.test`. Außerdem muss `tests.enabled: true` sein und die Konfiguration neu geladen werden.

**`--confirm` wird verlangt, obwohl ich den Befehl doch schon bestätigt habe.**

`set` und `reset` verlangen `--confirm` **im selben Aufruf**. Ein Klick im GUI oder eine vorherige Eingabe zählt nicht. Beispiel: `/eco admin balance set Spieler 100 --confirm`.

**`--reason` wird nicht gespeichert.**

Richtig. Das Flag wird akzeptiert und gekürzt, aber derzeit von keinem Modul in einer Transaktion oder im Audit gespeichert. Es ist nur für zukünftige Audit-Pfade vorbereitet.

**`--dry-run` oder `--silent` tut nichts.**

Richtig. Beide Flags werden geparst, aber nicht ausgewertet. Es gibt keinen Simulationsmodus und keine stille Buchung.

**`#uuid:...`, `#konto:...` oder `@s` funktionieren nicht.**

Diese Schreibweisen sind nicht implementiert. Verwende einen Spielernamen. Die Auflösung erfolgt über Online-Spieler, den Konten-Cache und den Speicher.

**Ein Alias funktioniert nach dem Reload nicht.**

Aliase werden beim Start registriert. `/eco admin reload` reicht nicht; der Server muss neu starten. Prüfe außerdem die Konsole: ungültige oder bereits belegte Aliase werden mit einer Warnung übersprungen.

**`/money` ist unbekannt.**

`/money` ist ein eigener Befehl aus `plugin.yml`, kein Alias von `/eco`. Prüfe, ob die JAR geladen ist und ob ein anderes Plugin den Namen belegt. `/eco balance` funktioniert unabhängig davon.

**`/eco help` zeigt nicht meine `help.yml`.**

`/eco help` liest die Listen `help` und `help-admin` aus `messages.yml`. Nur `/eco admin help` liest `help.yml`. Das ist beabsichtigt.

**`/eco help 2` zeigt Admin-Hilfe, obwohl ich keine Admin-Rechte habe.**

Seite 2 verlangt `economysystem.admin` (oder den alten Admin-Knoten). Ohne dieses Recht wird die Seite abgelehnt.

### 16.3 Guthaben und Überweisungen

**Ein neuer Spieler hat kein Startguthaben.**

Prüfe `currency.starting-balance` und ob das Konto wirklich **neu** angelegt wurde. Bestehende Konten werden nicht angehoben. Bei YAML prüfen, ob eine Kontodatei existiert; bei MySQL, ob die Zeile vorhanden ist.

**`/pay` an einen offline Spieler.**

`network.transfer.mode: LOCAL` bucht auch an offline Spieler, sofern das Konto bereits existiert. Für serverübergreifende Ziele `ANY` verwenden (MySQL + `economysystem.pay.remote`). `PROXY` ist nicht implementiert.

**`/pay` sagt „kein Zielserver bekannt“.**

Das ist der `PROXY`-Modus. Es gibt noch keine Serverliste; `PROXY` antwortet immer mit `command.pay.error.no-route`. Auf `LOCAL` oder `ANY` wechseln.

**`/pay` sagt „Überweisung erfordert economysystem.pay.remote“.**

Der Modus ist `ANY`, aber dem Spieler fehlt `economysystem.pay.remote`. Dieses Recht ist bewusst separat, weil Offline-Buchungen mehr Risiko bedeuten.

**`/pay` sagt „Ziel nicht gefunden“.**

Bei `LOCAL` und `ANY` muss das Zielkonto bereits existieren. Ein unbekannter Name wird nicht automatisch angelegt. Der Spieler muss zuvor ein Konto haben (z. B. durch Join oder eine Admin-Gutschrift).

**`/pay` sagt „maximales Guthaben überschritten“.**

Das Zielkonto würde `currency.max-balance` überschreiten. Prüfe den Wert und das Zielguthaben. Bei `ANY` prüft der Speicher dieselbe Grenze.

**`/pay` sagt „nicht genug Geld“, obwohl genug da ist.**

Prüfe, ob eine andere Buchung (Kredit-Tilgung, Zinsen, paralleler Befehl) das Guthaben zwischen Anzeige und Buchung verändert hat. Prüfe außerdem, ob der Betrag durch `decimals` auf einen höheren Wert gerundet wurde.

**Die lokale `/pay`-Nachricht enthält keine tx-id.**

Richtig. Nur `ANY`-Fernbuchungen nennen eine tx-id. Lokale Buchungen sind synchron und erhalten keine Transaktions-ID.

**Eine Überweisung wurde doppelt gebucht.**

Die Idempotenz gilt nur für denselben Transfer-Datensatz. Ein erneuter Befehl erzeugt einen neuen Schlüssel. Es gibt keinen automatischen Retry-Schutz über Befehlsgrenzen hinweg. Bei Verdacht auf Doppelbuchung die Kontodaten prüfen und ggf. manuell korrigieren.

**Das Guthaben ist negativ.**

Negative Guthaben können durch erzwungene Kreditabbuchungen bis `currency.min-balance` entstehen. Normale Abhebungen und `/pay` lassen kein negatives Guthaben zu. Prüfe `min-balance` und die Kreditfälligkeit.

**`/eco balance top` zeigt negative Konten nicht an.**

Negative Guthaben werden bewusst nie in der Bestenliste angezeigt. `include-zero-balances` betrifft nur Konten mit exakt 0.

**Die Bestenliste ist leer, obwohl es Konten gibt.**

Prüfe `leaderboard.include-zero-balances`, `leaderboard.max-entries` und ob die Konten gespeichert sind. Bei YAML muss der Index aktuell sein (Neustart nach manuellen Änderungen).

### 16.4 Zinsen

**Es werden keine Zinsen gutgeschrieben.**

Prüfe:

1. `interest.enabled: true`.
2. `interest.start-time` und `interest.interval-minutes`.
3. `time-zone`.
4. Guthaben `> 0`.
5. `interest.factors.spar > 0`.
6. Bei MySQL: Lease und `interest_runs`; ein anderer Server könnte den Lauf halten.
7. Bei `READ_ONLY`: Der Zyklus läuft trotzdem, aber prüfe, ob ein anderer Server die Lease hält.

**Konten mit 0 oder negativem Guthaben bekommen keinen Zins.**

Richtig. Die Formel `√x` ist für `x <= 0` nicht sinnvoll; das Plugin liefert 0.

**Konten am Maximalguthaben bekommen keinen Zins.**

Richtig. Ist `x >= max-balance`, wird 0 gutgeschrieben. Andernfalls wird die Gutschrift auf den verbleibenden Betrag gekürzt.

**Die Zinsnachricht kommt nicht an.**

`interest.notify-players` muss `true` sein und der Spieler muss online sein. Prüfe außerdem, ob die Nachricht in `messages.yml` vorhanden ist.

**Der Zinszyklus läuft auf mehreren Servern doppelt.**

Bei MySQL prüfen, ob alle Server dieselbe Datenbank und denselben Scope verwenden und ob `interest.executor` korrekt ist. Bei YAML gibt es keine gemeinsame Lease; jeder Server mit eigenen Dateien verzinst seine eigenen Konten. Bei gemeinsam genutzten YAML-Dateien über ein Netzlaufwerk ist das nicht unterstützt.

**Der Zyklus läuft trotz `READ_ONLY`.**

Das ist bekannt. `READ_ONLY` blockiert den Router und Vault, aber nicht den Scheduler. Für einen echten Stopp `interest.enabled: false` setzen und Kredite pausieren.

**`/eco admin test interest-cycle add 10` verändert echte Konten.**

Richtig. Die Testfunktion führt echte Zinszyklen aus. Nur auf Testservern verwenden und vorher sichern. `tests.enabled` ist standardmäßig `false`.

**Die Testfunktion sagt „deaktiviert“, obwohl `tests.enabled: true` in der Datei steht.**

Nach der Änderung `/eco admin reload` ausführen. Prüfe außerdem, ob die richtige `settings.yml` geladen wurde (ein Server kann mehrere Plugin-Ordner haben).

### 16.5 Kredite

**„Kein verfügbarer Kreditrahmen“.**

Der Rahmen ist `limit-multiplier × (Guthaben − offene Kredite)`. Ist das Eigenkapital `<= 0`, gibt es keinen Rahmen. Prüfe Guthaben und offene Kredite mit `/eco credit info`.

**„Kreditrahmen überschritten“.**

Der Betrag ist größer als der berechnete Rahmen. Der Rahmen wird nicht durch das Maximalguthaben erhöht; die Auszahlung muss zusätzlich unter `max-balance` bleiben.

**„Maximale Anzahl Kredite erreicht“.**

`credits.max-loans` ist erreicht. Erst zurückzahlen, dann neuen Kredit aufnehmen.

**„Laufzeit ungültig“.**

Die Tage müssen eine ganze Zahl zwischen `credits.min-days` und `credits.max-days` sein. Keine Dezimalzahlen, keine Einheiten wie `30d`.

**„Betrag zu klein“.**

Der Betrag liegt unter `credits.minimum-amount`. Prüfe den konfigurierten Mindestbetrag.

**Wie wird der Kreditzins berechnet?**

`y = Tage × credits.slope + interest.factors.kredit`. Das Ergebnis ist der absolute Zinsbetrag, kein Prozentsatz. Beispiel: 30 Tage, `slope 0.5`, `kredit 1.3` → 16.3 Zins.

**Welcher Kredit wird zuerst zurückgezahlt?**

Der älteste. Bei mehreren Krediten werden sie nacheinander bedient, bis der Betrag aufgebraucht ist.

**Was passiert bei Fälligkeit?**

Das Plugin bucht zwangsweise bis `currency.min-balance` ab. Bleibt etwas offen, werden die restlichen Zinsen erlassen und alle Einnahmen zur Tilgung verwendet. Der Spieler erhält eine Nachricht.

**Was ist der Unterschied zwischen „überfällig“ und „Tilgung läuft“?**

„Überfällig“ bedeutet, die Fälligkeit ist erreicht. „Tilgung läuft“ (`auto_collect`) bedeutet, die Zinsen wurden erlassen und alle künftigen Einnahmen fließen in die Rückzahlung.

**Kredite sind deaktiviert, aber alte Schulden bleiben.**

Richtig. `credits.enabled: false` verhindert nur neue Kredite. Bestehende Kredite werden weiter geprüft und getilgt, damit Schulden nicht verschwinden.

**Die Kreditauszahlung überschreitet das Maximalguthaben.**

Das wird verhindert (`credit-max-balance`). Prüfe `currency.max-balance` und das aktuelle Guthaben. Bei MySQL ist `DECIMAL(30,8)` die harte Grenze.

**`/kredit` funktioniert in der Konsole nicht.**

Kredite sind spielergebunden (`player-only`). Die Konsole kann keine Kredite aufnehmen oder zurückzahlen.

### 16.6 GUI

**Das GUI ist deaktiviert.**

`gui.settings.enabled: true` setzen und `/eco admin reload` ausführen. Prüfe außerdem `economysystem.command.gui` bzw. `economysystem.gui`.

**„Menü nicht gefunden“.**

Die Menü-ID existiert nicht in `gui.yml` oder `settings.default-menu` zeigt auf eine falsche ID. Prüfe Groß-/Kleinschreibung und die Einrückung.

**Ein Item wird angezeigt, aber der Befehl sagt „keine Berechtigung“.**

Das Item hat keine `permission` und ist deshalb sichtbar; der Befehl dahinter verlangt aber ein Recht. Trage eine passende `permission` am Item ein oder vergib das Recht.

**Ein Item reagiert nicht.**

Es hat weder `commands` noch `open`; dann ist es eine reine Anzeige. Prüfe außerdem `slot`, `material` und ob das Menü die richtige `rows`-Zahl hat.

**Die Eingabe öffnet sich nicht.**

Prüfe `inputs` und den `type`. `number` braucht `min`/`max`, `text` ggf. `pattern`, `player` keine weiteren Pflichtfelder. Bei `text` darf die Antwort keine Leerzeichen und kein `--` enthalten, wenn kein Pattern gesetzt ist.

**Die Texteingabe wird abgelehnt.**

Ohne Pattern gilt: ein einzelner Wert ohne Leerzeichen, ohne `--`, maximal 64 Zeichen. Mit Pattern muss die Antwort exakt dem regulären Ausdruck entsprechen. Prüfe das Pattern in einem Regex-Tester.

**Die Zahleneingabe geht nicht über 1.000.000.000.000.**

Das ist die harte Obergrenze der GUI-Eingabe. Für größere Werte den Befehl direkt verwenden.

**Der Bestätigungsdialog erscheint nicht.**

Er erscheint nur bei Befehlen, die `admin balance set` oder `admin balance reset` enthalten. Bei anderen Befehlen wird direkt ausgeführt. Prüfe, ob der Befehl exakt so im Item steht.

**Der Klick wird ignoriert.**

`settings.click-cooldown-ms` blockiert Doppelklicks auf dasselbe Item. Kurz warten oder den Wert senken. Bei `close-on-click: true` schließt sich das Inventar nach dem Klick.

**Der Spielerkopf ist leer.**

`head: player` funktioniert nur für Spieler, nicht in der Konsole. Prüfe außerdem, ob das Material `PLAYER_HEAD` ist.

**Die Lore zeigt `%balance%` statt eines Werts.**

Der Platzhalter muss in `name` oder `lore` stehen und das Menü muss von einem Spieler geöffnet werden. Bei der Konsole gibt es kein Guthaben.

**Ein `open`-Item führt zu einer Schleife.**

Ein Menü darf sich nicht selbst öffnen, ohne eine Abbruchmöglichkeit. Prüfe die `open`-Kette und füge ein „Zurück“-Item ein.

### 16.7 PlaceholderAPI

**Platzhalter werden nicht ersetzt.**

PlaceholderAPI muss installiert und aktiviert sein. Prüfe `/papi list`, ob `economysystem`, `money`, `eco` und `aktuelle` registriert sind. Prüfe die exakte Schreibweise.

**`%money_%` ist leer.**

Der Platzhalter braucht einen Spieler-Kontext (Scoreboard, Chat). In der Konsole oder bei einem unbekannten Spieler gibt es kein Guthaben. Bei Offline-Spielern kann der erste Aufruf leer sein, bis der Cache gefüllt ist.

**`%eco_top_1%` ist leer.**

Die Top-Liste wird periodisch neu berechnet. Prüfe `placeholder.top-size`, `placeholder.top-refresh-seconds` und ob Konten vorhanden sind. Negative Guthaben erscheinen nicht.

**Gibt es Kredit-Platzhalter?**

Nein. `%credit_limit%`, `%credit_debt%` usw. funktionieren nur in `gui.yml`. Für PlaceholderAPI gibt es Guthaben, Top und Sparzins.

**Die Platzhalter sind veraltet.**

`placeholder.cache-ttl-seconds` und `placeholder.top-refresh-seconds` bestimmen die Aktualität. Bei Online-Spielern wird der Cache direkt gelesen; bei Offline-Spielern kann er kurz alt sein.

### 16.8 Vault

**Vault registriert EconomySystem nicht.**

Prüfe, ob Vault installiert ist, `vault.enabled: true` gesetzt ist und die Konsole keine Registrierungsfehler zeigt. Prüfe `vault.priority` und ob ein anderes Economy-Plugin mit höherer Priorität läuft.

**`hasAccount` liefert `true`, obwohl der Spieler kein Konto hat.**

`hasAccount(OfflinePlayer)` liefert immer `true`, wenn der Spieler nicht `null` ist. Das ist eine Vault-Besonderheit dieses Plugins. `hasAccount(String)` prüft den Speicher.

**Bankkonten werden nicht unterstützt.**

Richtig. `hasBankSupport()` ist `false`; alle Bankmethoden liefern `NOT_IMPLEMENTED`.

**Vault-Beträge sind ungenau.**

Vault arbeitet mit `double`. Das Plugin validiert an der Grenze und rundet auf `currency.decimals`. Bei sehr großen Beträgen kann `double` trotzdem ungenau sein; für exakte Buchungen die eigenen Befehle verwenden.

**Vault-Buchungen schlagen bei `READ_ONLY` fehl.**

Das ist beabsichtigt. `withdrawPlayer` und `depositPlayer` liefern dann einen Fehler; `createPlayerAccount` liefert `false`.

**Vault-Buchung Timeout.**

`vault.timeout-ms` erhöhen, wenn der Server stark belastet ist. Bei Timeout wird die Aufgabe abgebrochen, damit keine verspätete Buchung erfolgt. Prüfe die Konsole auf `Vault: Buchung ...`.

**`createPlayerAccount(String)` legt kein Konto an.**

Ohne UUID kann das Plugin kein neues Konto sicher anlegen. Die Methode liefert nur `true`, wenn das Konto bereits existiert. `createPlayerAccount(OfflinePlayer)` legt es an.

### 16.9 MySQL und TLS

**„Connection refused“.**

MySQL läuft nicht, der Port ist falsch oder die Firewall blockiert. Prüfe `host`, `port`, den Dienst und `telnet host port`.

**„Access denied for user“.**

Benutzer, Passwort oder Host-Freigabe falsch. Prüfe `storage.mysql.username`, `password` und die MySQL-Grants für den Server-Host.

**„Unknown database“.**

Die Datenbank existiert nicht. Lege sie an oder korrigiere `storage.mysql.database`.

**TLS-Fehler.**

Prüfe `ssl-mode`. `VERIFY_IDENTITY` verlangt ein gültiges Zertifikat für den Hostnamen. Für Testserver kann `REQUIRED` oder `PREFERRED` helfen; `DISABLED` nur in vertrauenswürdigen Netzen.

**Die Tabellen werden nicht angelegt.**

Der Datenbankbenutzer braucht `CREATE`, `ALTER`, `SELECT`, `INSERT`, `UPDATE`, `DELETE` (und ggf. `INDEX`) auf der Datenbank. Prüfe die Konsole auf den ersten SQL-Fehler.

**Das Tabellenpräfix ist leer.**

Das ist erlaubt. Die Tabellen heißen dann `accounts`, `loans`, `transfers`, `audit`, `locks`, `interest_runs`. Achte auf Kollisionen mit anderen Plugins.

**`DECIMAL(30,8)` reicht nicht.**

Dann ist das Schema zu klein für den gewünschten Maximalbetrag. `max-balance: -1` hilft nur auf Plugin-Ebene; die Datenbank bleibt begrenzt. Schema nur mit Backup und Migration ändern.

**Audit bleibt leer.**

Audit wird nur für Fernbuchungen (`ANY`) und nur bei SQLite und MySQL geschrieben. Lokale `/pay`-Buchungen und Admin-Änderungen erzeugen keine Einträge. Prüfe `audit.enabled` und `storage.type`.

**Fernbuchung wird nicht unterstützt.**

`remoteTransferAsync` ist bei YAML ein No-op/`UNSUPPORTED`. Für `ANY` ist SQLite oder MySQL erforderlich.

### 16.10 YAML

**Manuelle Änderungen an Kontodateien werden nicht sichtbar.**

Der Index wird beim Start aufgebaut. `/eco admin reload` lädt nur die Konfiguration. Server neu starten oder die Datei über die API ändern lassen.

**Eine Kontodatei ist defekt.**

Das Plugin überspringt sie beim Indexieren und loggt den Fehler. Datei sichern, YAML-Syntax prüfen (z. B. mit einem YAML-Parser) und erst dann neu starten. Keine halb geschriebene Datei liegen lassen.

**Was ist `pending-transaction.yml`?**

Das Wiederherstellungsjournal für unterbrochene Schreibvorgänge. Es liegt in `data/`, nicht in `data/accounts/`. Nicht löschen, solange unklar ist, ob die Transaktion abgeschlossen wurde.

**Kann ich den Kontenordner ändern?**

Ja, `storage.yaml.folder`. Der Wert ist relativ zu `data/` und wird ungeprüft als Pfad verwendet; keine `..`-Pfade verwenden. Nach der Änderung Server neu starten.

**YAML wird bei vielen Konten langsam.**

Das ist erwartbar. Für einzelne Server mit vielen Konten ist SQLite die bessere Wahl, für Netzwerke MySQL. Die Bestenliste und der Zinszyklus lesen viele Konten; `leaderboard.max-entries` und ein größeres Zinsintervall helfen.

**Ich habe versehentlich eine Kontodatei gelöscht.**

Aus dem Backup zurückholen und Server neu starten. Ohne Backup ist das Guthaben weg; das Plugin kann es nicht rekonstruieren.

### 16.11 Reload und Konfiguration

**Änderungen werden nach `/eco admin reload` nicht übernommen.**

Prüfe die Konsole auf Ladefehler. Einige Änderungen brauchen einen Neustart: neue Befehle/Aliase in `plugin.yml`, der YAML-Kontenindex, und alles, was beim Start registriert wird. Prüfe außerdem, ob du die richtige `settings.yml` bearbeitest.

**Meine Kommentare wurden überschrieben.**

Der Updater übernimmt die Standardkommentare der Vorlage. Eigene Kommentare bleiben nur stehen, wenn die Vorlage an dieser Stelle keinen Kommentar hat. Eigene Werte bleiben erhalten.

**Ein Wert wird ignoriert.**

Prüfe den erlaubten Bereich und die Konsole. Ungültige Werte werden auf den Standard zurückgesetzt. Bei verschachtelten Schlüsseln auf die Einrückung achten.

**Ein Schlüssel fehlt nach dem Update.**

Der Updater ergänzt nur Schlüssel, die in der Vorlage stehen. Eigene Schlüssel bleiben erhalten. Wenn ein Standardschlüssel fehlt, ist die Vorlage älter als die Datei; `config-version.yml` prüfen.

**Wo liegen die Backups?**

Unter `plugins/EconomySystem/config/backup/`. Die neueste Sicherung heißt `<datei>.vor-config-update.bak`, ältere `.1.bak`, `.2.bak` usw.

**`settings.yml` wird nicht angelegt.**

Prüfe Schreibrechte und ob das Plugin aktiviert wurde. Bei einem defekten YAML-Mapping wird die Datei nicht überschrieben; die Konsole zeigt den Fehler.

### 16.12 Multi-Server

**Zwei Server zeigen unterschiedliche Guthaben.**

Prüfe, ob beide dieselbe Datenbank und dasselbe `table-prefix` verwenden. Bei YAML teilen sie nichts. Prüfe außerdem den Kontencache: `network.invalidation` und `cache-ttl-seconds` sind derzeit ohne Wirkung; ein Neustart oder eine neue Abfrage kann nötig sein.

**Der Zinszyklus läuft doppelt.**

Prüfe `interest.executor`, die Lease-Tabelle und ob alle Server dieselbe `time-zone` und denselben Scope verwenden. Bei YAML gibt es keine gemeinsame Lease.

**`PROXY` funktioniert nicht.**

Richtig, `PROXY` ist nicht implementiert und antwortet mit `no-route`. `LOCAL` oder `ANY` verwenden.

**`ANY` findet das Ziel nicht.**

Das Zielkonto muss bereits existieren. Ein unbekannter Name wird nicht angelegt. Der Spieler muss zuvor auf einem Server mit derselben Datenbank gewesen sein.

**`READ_ONLY` verhindert nicht alle Buchungen.**

Richtig. Der Scheduler und die Kredit-Tilgung laufen weiter. Für einen echten Stopp `interest.enabled: false` setzen und Kredite pausieren.

**Audit fehlt auf dem zweiten Server.**

Audit wird nur für Fernbuchungen geschrieben. Prüfe, ob die Buchung wirklich über `ANY` lief und ob beide Server dieselbe Audit-Tabelle verwenden.

### 16.13 Backup und Upgrade

**Wie upgrade ich sicher?**

Server stoppen, `plugins/EconomySystem/` und die Datenbank sichern, JAR austauschen, starten, Konsole prüfen, `/eco info` prüfen. Erst dann den Betrieb freigeben.

**Kann ich downgraden?**

Nur mit Backup. Eine neuere Version in `config-version.yml` kann Schlüssel enthalten, die die alte Version nicht kennt. Die alte JAR kann Daten anders interpretieren. Vorher vollständige Sicherung anlegen.

**Wie stelle ich ein Backup wieder her?**

Server stoppen, den Plugin-Ordner bzw. die Datenbank ersetzen, Server starten. Bei MySQL den Kontencache durch den Neustart verwerfen. Bei YAML `data/pending-transaction.yml` und `data/accounts/` zusammen zurückholen.

**Gehen bei einem Serverabsturz Daten verloren?**

Seit dem letzten erfolgreichen Save können Änderungen fehlen. `storage.save-interval-seconds` bestimmt das Risiko. Das Journal hilft bei unterbrochenen YAML-Transaktionen, aber nicht bei einem verlorenen Puffer.

---

## 17. Bekannte Grenzen und noch nicht umgesetzte Funktionen

Die folgende Tabelle ist bewusst vollständig. Sie beschreibt, was **heute** nicht funktioniert, auch wenn es in der Konfiguration oder in `Commands.md` erwähnt wird.

| Bereich | Einschränkung |
|---|---|
| Netzwerk | `network.transfer.mode: PROXY` ist nicht implementiert; `/pay` antwortet mit `command.pay.error.no-route`. |
| Netzwerk | `network.invalidation` wird gelesen, aber nicht angewendet. |
| Netzwerk | `network.cache-ttl-seconds` wird gelesen, aber der Kontencache hat keine TTL-Prüfung. |
| Netzwerk | `network.transfer.timeout-seconds` wird gelesen, aber es gibt keine Rückbuchung nach Timeout. |
| Netzwerk | `network.economy-scope` partitioniert keine Konten; nur Lease-/Transfer-/Audit-Scope. |
| Netzwerk | Die Idempotenz gilt nur für denselben Transfer-Datensatz; ein neuer Befehl erzeugt einen neuen Schlüssel. |
| Netzwerk | Lokale `/pay`-Buchungen nennen keine tx-id. |
| Netzwerk | `--reason` wird geparst, aber nicht gespeichert. |
| Netzwerk | Audit wird nur für Fernbuchungen und nur bei SQLite/MySQL geschrieben; YAML-Audit ist ein No-op. |
| Netzwerk | `admin.audit`, `admin.network`, `admin.interest.run`, `admin.reload network`, `pay.status` und `credit.forgive` sind nicht implementiert. |
| Flags | `--dry-run` und `--silent` sind wirkungslos. |
| Flags | `--player`, `--action`, `--server`, `--scope` hängen an nicht implementierten Pfaden. |
| Konten | `#uuid:...`, `#konto:...` und `@s` werden nicht unterstützt. |
| Rechte | `economysystem.command.balance.others` (Plural) wird nicht ausgewertet; modern ist `...balance.other`. |
| Rechte | `economysystem.tests` allein schaltet den Testpfad frei; `economysystem.admin` allein genügt dort nicht. |
| Rechte | `economysystem.reload` allein schaltet `admin.reload` frei. |
| Rechte | `economysystem.command.balance` schaltet `balance.top` nicht frei. |
| Rechte | `economysystem.command.info` ist für `/eco info` nötig; der alte Admin-Knoten reicht dort nicht. |
| Betrieb | `READ_ONLY` blockiert den Router und Vault, aber nicht den geplanten Zinszyklus oder die fällige Kredit-Tilgung. |
| Betrieb | `READ_ONLY` erlaubt nur die Positivliste: `balance`, `balance.top`, `credit.info`, `credit.list`, `gui`, `info`, `help`, `admin.reload`, `admin.interest.show`, `admin.network`, `admin.network.scopes`, `admin.audit`. Andere Pfade sind gesperrt, auch `admin.help`. |
| GUI | Items ohne `permission` sind für alle sichtbar; der Befehl kann trotzdem an Rechten scheitern. |
| GUI | `number`-Eingaben sind auf 1.000.000.000.000 begrenzt. |
| GUI | `text`-Eingaben sind ohne Pattern auf einen Wert ohne Leerzeichen/`--` und 64 Zeichen begrenzt. |
| Speicher | YAML-Kontodateien werden beim Start indexiert; manuelle Änderungen brauchen einen Neustart. |
| Speicher | Eine defekte YAML-Kontodatei wird übersprungen, nicht repariert. |
| Speicher | Es gibt keinen automatischen YAML↔MySQL-Konverter. |
| Speicher | `storage.yaml.folder` wird ungeprüft als Pfad verwendet (Pfad-Ausbruch möglich). |
| Speicher | `max-balance: -1` ist bei MySQL durch `DECIMAL(30,8)` praktisch begrenzt. |
| Vault | `hasAccount(OfflinePlayer)` liefert immer `true`; `createPlayerAccount(String)` kann ohne UUID kein Konto anlegen. |
| Vault | Bankkonten werden nicht unterstützt. |
| Placeholder | Es gibt keine Kredit-Platzhalter; `%credit_*%` funktioniert nur in `gui.yml`. |
| Hilfe | `/eco help` liest `messages.yml`; nur `/eco admin help` liest `help.yml`. |
| Config | Der Updater übernimmt Standardkommentare und kann eigene Kommentare an gleichen Stellen ersetzen. |
| Config | Änderungen an `plugin.yml`/Aliasen brauchen einen Neustart. |

---

## 18. Entwicklung

### 18.1 Bauen und Testen

```bash
mvn clean package
mvn test
```

Die CI führt `mvn -B -ntp verify` mit Java 25 aus. Der Build erzeugt `target/EconomySystem-v0.2.0-alpha.jar`.

Vor einem Pull Request:

1. `mvn test` grün.
2. Neue Konfigurationsschlüssel in der passenden YAML-Vorlage **und** in dieser README dokumentieren.
3. Neue Befehle in `Commands.md`, `plugin.yml` (falls Kurzbefehl) und `help.yml` ergänzen.
4. Keine Nachricht hartcodieren; `messages.yml` verwenden.
5. Keine `double`-Wirtschaftslogik einführen; `BigDecimal` bleibt führend.

### 18.2 Projektstruktur

```text
src/main/java/de/Minefalo/economySystem/
├── command/          Router, Parser, Permissions, Module
├── config/           Settings, Messages, ConfigUpdater, GUI-Config
├── credit/           Kredite, Tilgung, Fälligkeit
├── currency/         BigDecimal-Formatierung und Rundung
├── economy/          Konten, Zinsen, Service, Autosave
├── gui/              Menüs, Sessions, Eingaben
├── help/             HelpBook für help.yml
├── placeholder/      PlaceholderAPI-Erweiterung
├── storage/          YAML/MySQL, Transfers, Audit, Lease
├── util/             Text, Zeit, kleine Helfer
└── vault/            VaultEconomy
```

### 18.3 Neues Command-Modul

1. Klasse unter `command/module/` anlegen und `CommandModule` implementieren.
2. `id()`, `path()`, `usage()`, `permission()` und ggf. `playerOnly()` setzen.
3. `execute()` implementieren; `prepare()` nur für asynchrone Ladearbeit verwenden.
4. Modul in `EconomySystem.registerCommands()` bzw. im passenden Gruppenmodul registrieren.
5. Nachrichtenschlüssel in `messages.yml` ergänzen.
6. Tests für Routing/Permissions ergänzen, wenn der Pfad neue Regeln hat.
7. `Commands.md` und diese README aktualisieren.

Ein neuer **Unterbefehl** braucht keinen `plugin.yml`-Eintrag. Nur ein neuer **Kurzbefehl** oder Alias muss dort und in `commands.yml` ergänzt werden.

### 18.4 Projektregeln

- **UUID-first:** Konten werden über UUID geführt; Namen sind nur Anzeige und Auflösungshilfe.
- **BigDecimal:** Geldbeträge niemals als `double` speichern oder berechnen. `double` nur an der Vault-Grenze.
- **MiniMessage:** Nachrichten und Hilfetexte verwenden MiniMessage, keine Legacy-Farbcodes in neuem Code.
- **Keine stillen Fehler:** Speicherfehler loggen und eine verständliche Nachricht senden.
- **Konfiguration dokumentieren:** Jeder neue Schlüssel gehört in die YAML-Vorlage und in die README.
- **Abwärtskompatibilität:** Alte Permission-Knoten und alte Befehlsschreibweisen bleiben gültig, solange sie nicht ausdrücklich entfernt werden.

---

## 19. Lizenz

Dieses Projekt steht unter der **MIT-Lizenz**. Der vollständige Lizenztext mit Copyright-Hinweis steht in der Datei [`LICENSE`](LICENSE).

---

## Anhang A: Befehlsmatrix FULL/READ_ONLY

`READ_ONLY` ist eine Positivliste. Nur die folgenden Pfade sind erlaubt; alle anderen werden vom Router mit `command._default.error.read-only` abgewiesen.

| Pfad | READ_ONLY | Anmerkung |
|---|---|---|
| `help` | ✅ | Hilfe |
| `balance` | ✅ | Eigenes und fremdes Guthaben (Recht separat) |
| `balance.top` | ✅ | Bestenliste |
| `credit.info` | ✅ | Kreditübersicht |
| `credit.list` | ✅ | Kreditliste |
| `gui` | ✅ | GUI öffnen; schreibende Klicks werden vom Router blockiert |
| `info` | ✅ | Systeminfo |
| `admin.reload` | ✅ | Reload |
| `admin.interest.show` | ✅ | Zinsen anzeigen |
| `admin.network` | ✅ | Nicht implementiert |
| `admin.network.scopes` | ✅ | Nicht implementiert |
| `admin.audit` | ✅ | Nicht implementiert |
| `pay` | ❌ | Schreibend |
| `credit` | ❌ | Gruppenpfad; ohne Unterpfad gesperrt |
| `credit.take` | ❌ | Schreibend |
| `credit.repay` | ❌ | Schreibend |
| `admin` | ❌ | Gruppenpfad |
| `admin.help` | ❌ | In READ_ONLY gesperrt |
| `admin.balance.*` | ❌ | Schreibend |
| `admin.credit.list` | ❌ | In READ_ONLY gesperrt (obwohl lesend) |
| `admin.interest.set` | ❌ | Schreibend |
| `admin.test.*` | ❌ | Schreibend |

**Wichtig:** Die Tabelle beschreibt den Router. Der Scheduler (Zinszyklus, Kreditfälligkeit) und direkte Vault-Aufrufe können in `READ_ONLY` weiterhin schreiben. Siehe Abschnitt 10.5.

---

## Anhang B: Konfigurationsschlüssel

Die vollständige Referenz steht in Abschnitt 7.2. Diese Übersicht dient zum schnellen Finden.

| Bereich | Schlüssel |
|---|---|
| Allgemein | `time-zone` |
| Tests | `tests.enabled`, `tests.max-cycles` |
| Währung | `currency.name`, `currency.symbol`, `currency.format`, `currency.number-format`, `currency.locale`, `currency.decimals`, `currency.rounding`, `currency.starting-balance`, `currency.max-balance`, `currency.min-balance` |
| Zinsen | `interest.enabled`, `interest.start-time`, `interest.interval-minutes`, `interest.factors.spar`, `interest.factors.kredit`, `interest.notify-players`, `interest.executor` |
| Kredite | `credits.enabled`, `credits.max-loans`, `credits.limit-multiplier`, `credits.slope`, `credits.minimum-amount`, `credits.min-days`, `credits.max-days`, `credits.check-interval-seconds`, `credits.date-format` |
| Bestenliste | `leaderboard.page-size`, `leaderboard.max-entries`, `leaderboard.include-zero-balances` |
| Placeholder | `placeholder.cache-ttl-seconds`, `placeholder.negative-cache-ttl-seconds`, `placeholder.cache-retention-seconds`, `placeholder.top-size`, `placeholder.top-refresh-seconds` |
| Vault | `vault.enabled`, `vault.priority`, `vault.timeout-ms`, `vault.currency-name-singular` |
| Benachrichtigungen | `notifications.balance-on-join`, `notifications.balance-on-join-delay-ticks` |
| Netzwerk | `network.id`, `network.group`, `network.economy-scope`, `network.mode`, `network.invalidation`, `network.cache-ttl-seconds`, `network.lease-ttl-seconds`, `network.transfer.mode`, `network.transfer.timeout-seconds` |
| Limits | `network.limits.pay`, `network.limits.credit.take`, `network.limits.admin.balance`, `network.limits.gui`, `network.limits.balance.top` |
| Audit | `audit.enabled`, `audit.retention-days` |
| Speicher | `storage.type`, `storage.save-interval-seconds`, `storage.shutdown-timeout-seconds`, `storage.sqlite.file`, `storage.yaml.folder`, `storage.mysql.*` |
| Config | `config.backup-count`, `commands.reason-max-length` |

---

## Anhang C: Nachrichten-Keys

Die wichtigsten Gruppen in `messages.yml`:

| Gruppe | Beispiele |
|---|---|
| Allgemein | `prefix`, `no-permission`, `player-only`, `read-only`, `player-not-found`, `storage-error` |
| Beträge | `invalid-amount`, `amount-too-large`, `insufficient-funds`, `insufficient-funds-other` |
| Guthaben | `balance-self`, `balance-other`, `balance-on-join` |
| Zinsen | `interest-credited`, `interest-set`, `interest-factor-invalid`, `interest-type-savings`, `interest-type-credit` |
| Bestenliste | `top-header`, `top-entry`, `top-empty`, `top-invalid-page` |
| Überweisung | `pay-sent`, `pay-received`, `pay-self`, `pay-recipient-offline`, `command.pay.*` |
| Kredite | `credit-*` (Aufnahme, Rückzahlung, Status, Fälligkeit, Härtefall) |
| Admin | `balance-given`, `balance-taken`, `balance-set`, `balance-reset`, `command.admin.*` |
| GUI | `gui-*` (Amboss, Bestätigung, Spielerauswahl, Eingabefehler) |
| Tests | `tests-*` |
| Vault | `vault-status-missing`, `vault-status-disabled`, `vault-status-failed`, `vault-status-active` |
| Hilfe | `help`, `help-admin`, `help-empty`, `info` |
| Reload | `reload-success`, `settings-error` |

**Fallback:** Alte Schlüssel bleiben gültig. Fehlt ein Schlüssel, wird der Key angezeigt und eine Warnung geloggt. `%prefix%` wird automatisch vorangestellt, wenn er nicht im Text steht.

---

## Anhang D: Glossar

| Begriff | Bedeutung |
|---|---|
| **BigDecimal** | Exakte Dezimalzahl in Java; wird für alle Geldbeträge verwendet. |
| **Konto** | Ein Guthaben-Datensatz, eindeutig über die UUID eines Spielers. |
| **Scope** | Gruppierungsschlüssel für Lease, Transfer und Audit; teilt keine Konten. |
| **Lease** | Zeitlich begrenzte Sperre in MySQL, damit nur ein Server den Zinszyklus ausführt. |
| **Idempotenz** | Mehrfaches Anwenden desselben Transfers führt nicht zu doppelter Buchung. |
| **Audit** | Protokolleintrag für eine Fernbuchung. |
| **READ_ONLY** | Servermodus, in dem der Router schreibende Befehle und Vault-Buchungen ablehnt. |
| **Principal** | Ursprünglicher Kreditbetrag ohne Zinsen. |
| **Auto-Collect** | Automatische Tilgung aus allen künftigen Einnahmen nach einem Härtefall. |
| **MiniMessage** | Moderne Formatierungssprache von Adventure/Paper. |
| **Config-Updater** | Ergänzt fehlende Schlüssel/Kommentare und sichert alte Dateien. |

---

*Stand: v0.2.0-alpha – diese README beschreibt den tatsächlichen Code-Stand, nicht nur die Spezifikation in `Commands.md`.*
