# EconomySystem

[![Build](https://github.com/minefalo/EconomySystem/actions/workflows/build.yml/badge.svg)](https://github.com/minefalo/EconomySystem/actions/workflows/build.yml)
[![Lizenz: MIT](https://img.shields.io/badge/Lizenz-MIT-blue.svg)](LICENSE)

BigDecimal-basiertes Wirtschaftssystem für Paper-Server mit YAML- oder
MySQL-Speicher, konfigurierbarem Zinszyklus, Kreditsystem, GUI-Menüs und
Admin-Befehlen.

## Funktionen

- **Geld ohne Rundungsfehler** – alle Beträge sind `BigDecimal`, mit
  konfigurierbaren Nachkommastellen und Rundungsmodus.
- **Spieler-Befehle** – `/money` und `/pay`, inklusive Kontostand fremder
  Spieler (berechtigungsabhängig); Überweisungen nur an Online-Spieler.
- **Bestenliste** – `/money top [Seite]` zeigt die reichsten Spieler mit
  konfigurierbarer Seitengröße.
- **Direktes Feedback** – Online-Spieler sehen nach jedem Zinszyklus ihre
  persönliche Gutschrift und können sich beim Beitritt ihr Guthaben
  anzeigen lassen.
- **Admin-Befehle** – Geld hinzufügen, abziehen, setzen oder auf das
  Startguthaben zurücksetzen.
- **Kreditsystem** – Kredite bis zum 3-fachen Eigenkapital, frei wählbare
  Laufzeit, Zinsen nach `y = x · m + a` und automatische Tilgung bei
  Fälligkeit.
- **GUI-Menüs** – `/economysystem-gui` öffnet ein klickbares Menü. Items
  erscheinen nur mit passender Permission und führen Befehle als Spieler
  aus; Admins erreichen über den Stern ein eigenes Admin-Menü. Argumente
  werden ohne Chat abgefragt: ganze Zahlen per Regler aus Glasscheiben,
  Texte per Amboss-Nametag, Spieler über eine durchsuchbare Kopfauswahl.
- **Zinszyklus** – nach der Blockcraft-Wirtschaftsformel `y = a · √x` für
  **alle gespeicherten Konten**, auch offline. Zeitpunkt und Intervall sind
  konfigurierbar (Standard: täglich um 00:01 Uhr).
- **Zwei Speicherarten** – YAML (`accounts/<uuid>.yml`, Kredite in
  `loans.yml`) oder MySQL (`DECIMAL(30,8)`, Tabellen werden automatisch
  angelegt).
- **Konfiguration mit Kommentaren** – Änderungen an Zinsfaktoren werden
  zeilenweise geschrieben, bestehende Kommentare bleiben erhalten.
- **Automatischer Config-Abgleich** – beim Start und bei jedem Reload
  werden fehlende Schlüssel und aktuelle Standardkommentare ergänzt;
  eigene Werte, Texte, Listen und Zusatzschlüssel bleiben erhalten.
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

2. Die erzeugte Datei `target/EconomySystem-v0.1.3-alpha.jar` in den
   `plugins/`-Ordner des Servers kopieren.
3. Server starten – im Ordner `plugins/EconomySystem/config/` werden
   `settings.yml`, `messages.yml`, `commands.yml`, `help.yml` und `gui.yml`
   angelegt.
4. Anpassen und mit `/economysystem-admin reload` neu laden.

## Befehle

Die maßgebliche Struktur (Pfade, Permissions, Message-Keys) steht in
`Commands.md`, Abschnitt 5. Jeder Pfad ist ein eigenes Modul; die
Kurzbefehle unten sind nur andere Schreibweisen derselben Pfade.

| Befehl | Beschreibung |
| --- | --- |
| `/eco balance [<konto>]` | Zeigt das eigene oder ein fremdes Guthaben. |
| `/eco balance top [<seite>]` | Zeigt die Spieler mit dem höchsten Guthaben. |
| `/eco pay <konto> <betrag> [--reason <text>]` | Überweist Geld an einen online befindlichen Spieler. |
| `/eco credit take <betrag> <tage>` | Nimmt einen Kredit auf. |
| `/eco credit repay [<betrag>]` | Zahlt offene Kredite zurück (ohne Betrag: so viel wie möglich). |
| `/eco credit info` | Zeigt Guthaben, Kreditrahmen und laufende Kredite. |
| `/eco credit list` | Listet laufende Kredite mit kurzer Kredit-ID. |
| `/eco gui [<menü>]` | Öffnet das klickbare Wirtschafts-Menü. |
| `/eco info` | Zeigt Speicher, Währung, Zinsen und Zyklus. |
| `/eco help [<seite>]` | Zeigt die allgemeine Hilfe. |
| `/eco admin help [<seite>]` | Zeigt die mehrseitige Admin-Hilfe. |
| `/eco admin balance add\|remove <konto> <betrag>` | Schreibt Geld gut bzw. zieht es ab. |
| `/eco admin balance set <konto> <betrag> --confirm` | Setzt das Guthaben exakt. |
| `/eco admin balance reset <konto> --confirm` | Setzt auf das Startguthaben zurück. |
| `/eco admin credit list <konto>` | Zeigt die Kredite eines Kontos. |
| `/eco admin interest show` | Zeigt beide Zinsfaktoren. |
| `/eco admin interest set <savings\|credit> <faktor>` | Setzt den Spar- bzw. Kreditzins-Faktor `a`. |
| `/eco admin reload [local]` | Lädt alle Konfigurationen neu. |
| `/eco admin test interest-cycle add <anzahl>` | Führt Zinszyklen sofort aus (nur mit `tests.enabled`). |

Kurzbefehle (unverändert gültig, rufen dieselben Pfade auf): `/money`,
`/pay`, `/kredit`, `/economysystem`, `/economysystem-gui` und
`/economysystem-admin` (veraltet, siehe `Commands.md`, 4.2).

Beträge akzeptieren `.` und `,` als Dezimaltrennzeichen. Das maximale
Guthaben aus der `settings.yml` wird immer beachtet.

### Aliase

Die Aliase stehen in `commands.yml` und können dort erweitert werden:

| Befehl | Standard-Aliase |
| --- | --- |
| `money` | `geld`, `balance`, `coins` |
| `pay` | `zahlen`, `ueberweisen` |
| `kredit` | `credit`, `darlehen` |
| `economysystem` | `es` |
| `economysystem-admin` | `esa`, `ecoadm`, `ecoadmin` |
| `economysystem-gui` | `eco-gui`, `egui` |

## Berechtigungen

| Permission | Standard | Beschreibung |
| --- | --- | --- |
| `economysystem.balance` | alle | Eigenes Guthaben anzeigen. |
| `economysystem.balance.others` | OP | Guthaben anderer Spieler anzeigen. |
| `economysystem.balance.top` | alle | Bestenliste über `/money top` anzeigen. |
| `economysystem.pay` | alle | Geld überweisen. |
| `economysystem.kredit` | alle | Kreditsystem nutzen (`/kredit`). |
| `economysystem.gui` | alle | Wirtschafts-Menü öffnen (`/economysystem-gui`). |
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

Alle Konfigurationsdateien liegen in `plugins/EconomySystem/config/`.
Daten (`accounts/`, `loans.yml`, `pending-transaction.yml`) bleiben direkt
in `plugins/EconomySystem/`.

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
  notify-players: true           # persönliche Gutschrift im Chat
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
  date-format: "dd.MM.yyyy HH:mm"

leaderboard:
  page-size: 10                  # Einträge pro Seite bei /money top
  max-entries: 100               # maximal geladene Einträge

notifications:
  balance-on-join: true
  balance-on-join-delay-ticks: 20

suggestions:
  amounts: ["1", "10", "100", "1000", "10000"]
  credit-amounts: ["100", "500", "1000", "5000"]
  repay-amounts: ["10", "50", "100", "500"]
  credit-days: ["7", "30", "90"]

network:
  id: "default"                  # Name dieses Servers in der Datenbank
  group: "default"               # Gruppe für economy-scope: group
  economy-scope: "shared"        # shared | group | server
  mode: "FULL"                   # FULL | READ_ONLY (nur lesen)
  invalidation: "TTL"            # NONE | TTL | PLUGIN_MESSAGE | REDIS
  cache-ttl-seconds: 60
  transfer:
    mode: "LOCAL"                # LOCAL | PROXY | ANY
    timeout-seconds: 30
  limits:                        # je Spieler und Pfad, im Router erzwungen
    pay: { count: 10, seconds: 60, cooldown-seconds: 3 }
    credit.take: { count: 3, seconds: 300, cooldown-seconds: 10 }
    admin.balance: { count: 60, seconds: 60, cooldown-seconds: 0 }

storage:
  type: YAML                     # YAML oder MYSQL
  save-interval-seconds: 300
```

### Netzwerkbetrieb

Ohne eigene Angaben verhält sich das Plugin wie ein Einzelserver (`shared`,
`FULL`, `transfer.mode: LOCAL`).

- **`mode: READ_ONLY`** (z. B. Lobby): Guthaben, Bestenliste, Kredit- und
  Systeminformationen, Hilfe, GUI sowie `admin reload` und
  `admin interest show` bleiben erlaubt; alles Schreibende antwortet mit
  `error.read-only`. Die Prüfung sitzt im Router vor der Fachlogik.
- **`transfer.mode: ANY`** erlaubt `/eco pay` an ein Ziel, das nicht auf
  diesem Server online ist (Recht `economysystem.pay.remote`). Die Buchung
  läuft als eine Transaktion mit eindeutigem Idempotenzschlüssel: derselbe
  Auftrag bucht nur einmal. Das setzt `storage.type: MYSQL` voraus; im
  YAML-Betrieb antwortet der Befehl mit `command.pay.error.remote-failed`.
  `PROXY` braucht eine Serverliste und antwortet derzeit mit
  `command.pay.error.no-route`.
- **Zinszyklus:** `interest.executor: auto` lässt den Zyklus nur auf dem
  Server laufen, der die Lease für den Scope hält; `economy_interest_runs`
  sorgt dafür, dass jeder Zyklusschlüssel genau einmal gebucht wird.
- **MySQL-Migration:** Beim Start legt das Plugin `accounts.revision`,
  `economy_transfers`, `economy_audit`, `economy_locks` und
  `economy_interest_runs` an, falls sie fehlen.

Noch nicht umgesetzt: `PROXY`-Zustellung mit Serverliste, Cache-Ausbreitung
per `PLUGIN_MESSAGE`/`REDIS`, Audit für alle schreibenden Befehle,
`admin interest run` und `admin reload network`.

### Speicherkonsistenz

Konto- und Kreditänderungen, die zusammengehören (Kreditaufnahme,
Rückzahlung, automatische Tilgung), werden gemeinsam gespeichert:

- **MySQL:** Kontostand, Kredit und Löschungen laufen in einer Transaktion
  inklusive Rollback.
- **YAML:** Vor dem Schreiben entsteht `pending-transaction.yml` mit den
  vollständigen Zielwerten. Bricht der Server dazwischen ab, zieht das
  Plugin das Journal beim nächsten Start nach. Die Einträge sind absolut
  und können gefahrlos wiederholt angewendet werden.

Auszahlung und Kredit bzw. Abbuchung und Tilgung landen dadurch gemeinsam
in der Datenbank bzw. den YAML-Dateien. Fehler beim Schreiben werden
weiterhin protokolliert; bei einem dauerhaft nicht erreichbaren Speicher
kann der Server nur den zuletzt erfolgreich gespeicherten Stand laden.

### MySQL-Sicherheit

Verwende einen eigenen Datenbanknutzer statt `root`. Für die automatische
Tabellenerstellung benötigt er `CREATE`, im Betrieb `SELECT`, `INSERT`,
`UPDATE` und `DELETE` für die Plugin-Tabellen. Das Passwort steht
unverschlüsselt in `settings.yml`: Beschränke den Dateizugriff auf den
Serverprozess und Administratoren.

`storage.mysql.ssl-mode` legt den TLS-Modus fest:

| Wert | Bedeutung |
| --- | --- |
| `VERIFY_IDENTITY` | TLS mit Prüfung von Zertifikat und Hostname (Standard) |
| `VERIFY_CA` | TLS mit Prüfung des Zertifikats, ohne Hostname |
| `REQUIRED` | TLS ohne Zertifikatsprüfung |
| `PREFERRED` | TLS, falls der Server es anbietet, sonst Klartext |
| `DISABLED` | Kein TLS |

Ist der Wert leer, gilt weiterhin `use-ssl` (`true` → `VERIFY_IDENTITY`,
`false` → `DISABLED`), damit bestehende Konfigurationen unverändert
funktionieren. Bei `DISABLED` und `PREFERRED` erlaubt der Treiber den
Abruf des öffentlichen Serverschlüssels, damit `caching_sha2_password`
(MySQL 8) auch ohne TLS funktioniert; bei den übrigen Modi bleibt er
verboten.

`VERIFY_IDENTITY` verlangt ein vertrauenswürdiges Zertifikat, dessen Name
zum konfigurierten Host passt. Schlägt der Start deshalb fehl, nennt die
Fehlermeldung diesen Hinweis; alternativ `ssl-mode` auf `REQUIRED` setzen
(verschlüsselt, aber ohne Zertifikatsprüfung) oder ein passendes
Zertifikat hinterlegen.

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
- Verwendet den aktuellen Sparzins-Faktor und ist nur verfügbar, wenn
  `interest.enabled` aktiviert ist.
- **Verändert echte Kontostände.** Nur auf Test-/Entwicklungsservern
  aktivieren; zusätzlich ist die Permission `economysystem.tests` nötig.
- Ist `tests.enabled` auf `false`, antwortet der Befehl nur mit einem
  Hinweis und ändert nichts.
- Der Testpfad ist ein reguläres Modul (`/eco admin test interest-cycle
  add <Anzahl>`, Kurzform `/economysystem-admin tests zins-zyklus add
  <Anzahl>`) und in jedem Build enthalten. Er bleibt inert, solange
  `tests.enabled` aus ist oder die Permission fehlt — ein gesondertes
  Produktionsprofil gibt es nicht mehr.

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

### GUI-Menü

`/economysystem-gui` (Aliase `/eco-gui`, `/egui`) öffnet ein klickbares
Menü. Items, Slots, Materialien, Permissions, Eingaben und Befehle stehen
in der `gui.yml`. Oben zeigt der Kopf des Spielers Guthaben und
Kreditrahmen, darunter liegen die Aktionen; ein eigener Button öffnet das
Kredit-Menü.

```yaml
menus:
  main:
    title: "<dark_gray><bold>Wirtschaft"
    rows: 6
    items:
      guthaben:
        slot: 4
        material: PLAYER_HEAD
        head: player
        name: "<gold><bold>%player%"
        lore:
          - "<gray>Guthaben: <gold>%balance%"
          - "<gray>Kreditrahmen: <yellow>%credit_limit%"
          - "<gray>Offene Kredite: <red>%credit_debt%"
        permission: "economysystem.balance"
      credit:
        slot: 22
        material: DIAMOND
        name: "<aqua><bold>Kredite"
        permission: "economysystem.kredit"
        open: "credit"
      admin:
        slot: 49
        material: NETHER_STAR
        name: "<red><bold>Admin-Menü"
        permission: "economysystem.admin"
        open: "admin"
```

- Ein Item wird **nur angezeigt**, wenn der Spieler die angegebene
  `permission` besitzt.
- Beim Klick wird der Befehl **als Spieler** ausgeführt (z. B. `/money`,
  `/pay`, `/kredit`); alle normalen Prüfungen und Permissions greifen
  weiterhin.
- `open` öffnet statt Befehlen ein Untermenü. Das Kredit-Menü bündelt
  Aufnahme, Übersicht und Rückzahlung; das Admin-Menü hängt am Stern in der
  unteren Reihe und ist nur mit `economysystem.admin` sichtbar.
- `head: player` zeigt den Kopf des Spielers, der das Menü geöffnet hat.
  In `name` und `lore` stehen dafür `%player%`, `%balance%`,
  `%min_balance%`, `%max_balance%`, `%credit_limit%`, `%credit_debt%`,
  `%credit_loans%`, `%credit_max_loans%` und `%credit_status%` zur
  Verfügung. Items ohne `commands` und ohne `open` sind reine Anzeigen und
  reagieren nicht auf Klicks.
- `settings.default-menu` legt das Menü ohne Argument fest,
  `settings.close-on-click` schließt das Inventar vor dem Ausführen.
- `settings.click-cooldown-ms` drosselt nur wiederholte Klicks auf
  **dasselbe** Item; andere Menüpunkte bleiben sofort bedienbar
  (`0` deaktiviert den Filter).
- `/economysystem-admin reload` lädt auch die `gui.yml` neu.

#### Eingaben ohne Chat

Argumente werden nicht mehr im Chat abgefragt, sondern über eigene
Inventare. Jeder Eintrag unter `inputs` hat einen `type` und einen
`prompt` (Titel des Inventars):

| `type`   | Bedienung |
| -------- | --------- |
| `number` | **Regler** aus Glasscheiben: neun Scheiben als Schiene (Klick springt direkt auf den Wert), zusätzlich `-10×` / `-1×` / `+1×` / `+10×` der Schrittweite sowie Übernehmen, Zurücksetzen und Abbrechen. |
| `text`   | **Amboss**: Nametag im linken Feld benennen und auf das Ergebnis klicken. |
| `player` | **Kopfauswahl**: 36 Spielerköpfe pro Seite, Suche über einen Amboss, Filter (alle / nur online) und Sortierung (Name A–Z, Name Z–A, Guthaben auf- oder absteigend) sowie Seiten blättern. |

```yaml
inputs:
  - type: number          # ganze Zahl
    prompt: "<dark_gray>Betrag"
    min: 1                # kleinster Wert
    max: 1000000          # größter Wert
    step: 10              # Schrittweite der Buttons und der Schiene
    default: 100          # Startwert
  - type: text
    prompt: "<dark_gray>Neuer Faktor"
    pattern: "[0-9.]{1,12}"  # optional: Muster für die Antwort
  - type: player
    prompt: "<dark_gray>Spieler wählen"
    filter: all           # all | online
    sort: name-asc        # name-asc | name-desc | balance-desc | balance-asc
```

- Die Antworten ersetzen in `commands` die Platzhalter `{input1}`,
  `{input2}` usw.; `{input}` enthält alle Antworten mit Leerzeichen
  verbunden, `{player}` den Namen des klickenden Spielers.
- Vor dem Einsetzen wird jede Antwort geprüft: keine Leerzeichen oder
  Steuerzeichen, kein führendes `--`, höchstens 64 Zeichen. `number` muss im
  Bereich `min`–`max` liegen, `player` ein gültiger Spielername sein, `text`
  zusätzlich dem optionalen `pattern` entsprechen. Sonst antwortet die GUI mit
  `gui-input-invalid` und öffnet das Menü erneut, statt den Befehl
  auszuführen.
- Abbrechen ist jederzeit über die Schaltfläche bzw. `ESC` möglich; danach
  öffnet sich das vorherige Menü wieder.
- Ältere Dateien mit `prompts` funktionieren weiter: jeder Eintrag wird als
  Text-Eingabe (Amboss) gelesen.

### Automatischer Konfigurationsabgleich

Beim Start und bei jedem `/economysystem-admin reload` vergleicht das Plugin
jede Datei in `plugins/EconomySystem/config/` mit der mitgelieferten
Standarddatei (`config-version` bleibt als Metadatum erhalten):

- Fehlt eine Datei, wird sie aus dem JAR angelegt.
- Fehlende Schlüssel und Blöcke werden samt ihren Standardkommentaren
  ergänzt; Standardkommentare werden auf den aktuellen Stand gebracht. Hat
  die Standarddatei zu einem Schlüssel keinen Kommentar, bleibt ein eigener
  Kommentar stehen.
- **Eigene Werte, Texte, Listen und Zusatzschlüssel bleiben unverändert.**
  Die Reihenfolge der Schlüssel folgt der Standarddatei; eigene
  Zusatzschlüssel bleiben am Ende ihres Abschnitts.
- Es wird nie etwas gelöscht: nicht mehr verwendete Schlüssel bleiben
  stehen und werden ignoriert. Einzige Ausnahme ist die GUI-Neufassung ab
  `config-version 4`: Sie entfernt die abgelösten Hauptmenü-Items
  `balance`, `kredit-add`, `kredit-info`, `kredit-pay` und
  `kredit-pay-part`, damit die neuen Einträge `guthaben` und `credit`
  greifen.
- Vor jeder Änderung entsteht eine Sicherung unter
  `config/backup/<datei>.vor-config-update.bak` (eine Generation). Ist der
  Inhalt bereits aktuell, wird nicht geschrieben.
- Fehlerhafte oder leere Dateien bleiben unverändert und werden im Log
  gemeldet; das Plugin startet mit den Standardwerten weiter.
- `config-version` wird auf den größeren Wert aus vorhandener Datei und
  Standarddatei gesetzt. Ist die vorhandene Datei neuer als das Plugin,
  bleibt sie erhalten und es erscheint eine Warnung.
- Legacy-Farbcodes (`&a`, `&#RRGGBB`, `§`) in `messages.yml`, `help.yml`
  und den Anzeigetexten der `gui.yml` werden in MiniMessage umgeschrieben.

Weitere Dateien:

- `messages.yml` – alle Nachrichten, Farben im MiniMessage-Markup (z. B. `<red>` und `<#RRGGBB>`).
- `help.yml` – Seiten der Admin-Hilfe inklusive klickbarer Navigation.
- `commands.yml` – Aliase für alle Befehle.
- `gui.yml` – Items, Slots, Eingaben und Befehle der Klick-Menüs.
- `loans.yml` – gespeicherte Kredite (nur im YAML-Modus), liegt weiterhin
  direkt in `plugins/EconomySystem/`.

## Projektstruktur

```
src/main/java/de/Minefalo/economySystem/
├── command/      Befehle und Alias-Registrierung
├── config/       Einstellungen, Nachrichten und Config-Updates
├── credit/       Kredite, Zinsformel und automatische Tilgung
├── currency/     BigDecimal-Währung (Parsen, Formatieren, Runden)
├── economy/      Konten, Service, Zinsberechnung und -scheduler
├── gui/          Konfigurierbare Klick-Menüs, Regler, Amboss- und Spielerauswahl
├── help/         Mehrseitige Hilfe
├── listener/     Join-Handling
├── placeholder/  PlaceholderAPI-Erweiterungen
├── storage/      YAML- und MySQL-Speicher
└── util/         Text-/Farb-Helfer
```

## Entwicklung

```bash
mvn clean package  # JAR (Test-Befehle sind enthalten, aber nur mit tests.enabled aktiv)
mvn test           # Tests ausführen
```

Die Rechte-Matrix der Befehle (`balance.top`, `credit.*`, `admin.*` und
`/money top`) prüft `src/test/java/…/command/PermissionMatrixTest.java`; sie
läuft bei `mvn test` und damit auch bei jedem `package` bzw. `verify` mit.

Der GitHub-Actions-Workflow `.github/workflows/build.yml` baut das Projekt
bei jedem Push und Pull Request gegen Java 25 und lädt das JAR als Artifact
hoch.

## Lizenz

Dieses Projekt steht unter der [MIT-Lizenz](LICENSE).
