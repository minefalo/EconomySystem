# EconomySystem — Befehlsstruktur

**Status:** Spezifikation mit dokumentiertem Umsetzungsstand
**Gültig für:** alle Server eines Netzwerks, die dieses Plugin einsetzen
**Verbindlich für:** jeden neuen Befehl, jede neue Untergruppe und jede Änderung an bestehenden Pfaden

Diese Datei beschreibt, wie Befehle im EconomySystem aufgebaut, benannt,
abgesichert und erweitert werden. Sie ersetzt die verstreute Dokumentation
in `README.md`, `help.yml` und `messages.yml` als **maßgebliche Quelle für
die Struktur**; jene Dateien bleiben die Quelle für Texte und Hilfe-Seiten.

---

## Inhalt

1. [Zweck und Verbindlichkeit](#1-zweck-und-verbindlichkeit)
2. [Begriffe](#2-begriffe)
3. [Designregeln](#3-designregeln)
4. [Namensraum und Wurzelbefehle](#4-namensraum-und-wurzelbefehle)
5. [Kanonischer Befehlsbaum](#5-kanonischer-befehlsbaum)
6. [Grammatik und Argumente](#6-grammatik-und-argumente)
7. [Berechtigungen](#7-berechtigungen)
8. [commands.yml v2](#8-commandsyml-v2)
9. [Nachrichten, Hilfe, Tab-Vervollständigung](#9-nachrichten-hilfe-tab-vervollständigung)
10. [Erweiterbarkeit: der Command-Modul-Vertrag](#10-erweiterbarkeit-der-command-modul-vertrag)
11. [Netzwerkbetrieb](#11-netzwerkbetrieb)
12. [Kompatibilität und Deprecation](#12-kompatibilität-und-deprecation)
13. [Reservierte Pfade](#13-reservierte-pfade)
14. [Definition of Done](#14-definition-of-done)
15. [Umsetzungsplan](#15-umsetzungsplan)

### Statuslegende

| Zeichen | Bedeutung |
| --- | --- |
| ✅ | im aktuellen Code vorhanden und in dieser Form gültig |
| 🟡 | Zielform dieser Spezifikation; heute anders benannt oder anders aufgebaut |
| ⏳ | geplant — **noch nicht implementiert**, ausdrücklich als Erweiterungspunkt dokumentiert |

Alles, was mit ⏳ markiert ist, ist **kein** Versprechen über den aktuellen
Funktionsumfang. Ein Befehl, der hier ⏳ trägt, antwortet heute nicht.

### Umsetzungsstand (maßgeblich für den aktuellen Code)

`README.md` beschreibt das tatsächliche Verhalten. Diese Datei beschreibt
zusätzlich die Zielstruktur. Wo beide abweichen, gilt für den Betrieb die
README; für die Weiterentwicklung bleibt diese Datei die Strukturvorgabe.

| Bereich | Heute umgesetzt | Heute **nicht** umgesetzt |
| --- | --- | --- |
| Wurzel/Router | `/eco`, `/economysystem`, Kurzbefehle, Aliase, dynamischer Baum | — |
| Guthaben | `balance`, `balance.top`, lokale Anzeige | `#uuid:`, `#konto:`, `@s` |
| Überweisung | `pay` mit `LOCAL`; `ANY` über SQLite/MySQL mit `economysystem.pay.remote` | `PROXY`, `pay.status`, Rückbuchung nach Timeout |
| Kredite | `take`, `repay`, `info`, `list`; Fälligkeit/Tilgung | `credit.forgive` |
| GUI | Menüs, Items, Eingaben, Bestätigung | — |
| Info/Hilfe | `info`, `/eco help` aus `messages.yml`, `/eco admin help` aus `help.yml` | Hilfe aus Modulen/Registry |
| Admin | `balance add/remove/set/reset`, `credit list`, `interest show/set`, `reload`, `test` | `interest run`, `reload network`, `network status/scopes`, `audit` |
| Flags | `--confirm` bei `set`/`reset` | `--dry-run`, `--silent`, `--player`, `--action`, `--server`, `--scope`; `--reason` wird nicht gespeichert |
| Netzwerk | SQLite-/MySQL-Lease, `interest_runs`, `ANY`-Transfer, Audit für Fernbuchungen | `PROXY`, Cache-Invalidierung, Cache-TTL, Transfer-Timeout, Scope-Partitionierung |
| Audit | nur `remoteTransferAsync`, nur SQLite/MySQL | Audit für lokale/Admin-/Zins-/Kreditbuchungen; YAML-Audit |
| Idempotenz | gleicher Transfer-Datensatz über `idempotency_key` | Wiederholungssicherheit über neue Befehle/tx-id |

**Konsequenz für Doku und Tests:** Aussagen in den folgenden Abschnitten, die
nicht in dieser Tabelle als umgesetzt stehen, sind Zielbild und dürfen nicht
als aktuelles Verhalten getestet oder zugesagt werden.

---

## 1. Zweck und Verbindlichkeit

Das Plugin war bisher ein **isoliertes Einzelservern-Plugin**: sechs
unabhängige Befehle, je ein `switch` auf Unterbefehle, deutsche Synonyme fest
im Code, ein flaches Permission-Schema und eine Kontomenge ohne Bezug zum
Server, auf dem sie gerade gelesen wird.

Für ein größeres Netzwerk reicht das nicht. Diese Struktur verfolgt drei Ziele:

1. **Erweiterbarkeit** — ein neuer Befehl ist ein neues Modul an einer
   registrierten Stelle. Er braucht keinen neuen Eintrag in `plugin.yml`,
   keine Änderung an der Basisklasse und keine Änderung an bestehenden
   Befehlen.
2. **Vorhersagbarkeit** — Pfad, Permission, Nachricht und Audit-Eintrag
   folgen alle demselben Namen. Wer `/eco admin interest set savings 1.3`
   liest, kennt den Permission-Knoten, den Message-Präfix und die
   Audit-Aktion, ohne nachzusehen.
3. **Netzwerkfähigkeit** — Begriffe wie *Server*, *Gruppe* und *Scope*
   gehören in die Befehlssyntax und in die Datenhaltung, nicht in
   Sonderfälle. Ein Befehl verhält sich auf einem Lobby-Server anders als
   auf einem Wirtschaftsserver, aber er heißt überall gleich.

**Verbindlich heißt:** neue Befehle, die dieser Datei widersprechen, werden
nicht angenommen. Ausnahmen sind nur zulässig, wenn sie hier dokumentiert
werden — mit Begründung.

---

## 2. Begriffe

| Begriff | Bedeutung | Beispiel |
| --- | --- | --- |
| **Wurzelbefehl** | Der in `plugin.yml` registrierte Befehl. Es gibt genau einen pro Namensraum. | `/eco` |
| **Pfad** | Die Folge von Segmenten unterhalb des Wurzelbefehls. Kanonisch, stabil, ASCII. | `admin.interest.set` |
| **Gruppe** | Ein Pfad, der ausschließlich weitere Pfade enthält. | `admin`, `credit`, `bank` |
| **Aktion** | Das letzte Segment eines Pfades; ein Verb aus der geschlossenen Verb-Liste. | `set`, `top`, `run` |
| **Kurzbefehl** | Ein eigener, in `plugin.yml` deklarierter Befehl, der auf einen Pfad zeigt. | `/money` → `balance` |
| **Alias** | Ein zusätzlicher Name für einen Wurzel- oder Kurzbefehl, aus `commands.yml`. | `/geld` → `money` |
| **Modul** | Die Java-Einheit, die genau einen Pfad (und optional dessen Unterpfade) implementiert. | `BalanceModule` |
| **Origin** | Herkunft eines Aufrufs: `PLAYER`, `CONSOLE`, `GUI`, `API`, `SCHEDULER`. | `GUI` |
| **Scope** | Gruppierungsschlüssel für Lease, Transfer und Audit (`shared`, `group`, `server`). Er teilt heute **keine** Konten. | `global`, `survival`, `survival-1` |
| **tx-id** | Transaktionskennung. Heute nur bei `ANY`-Fernbuchungen; lokale Buchungen nennen keine tx-id. | `7f3a91e0` |

---

## 3. Designregeln

Diese Regeln sind der Kern der Struktur. Sie gelten für jeden neuen Befehl.

| Nr. | Regel | Begründung |
| --- | --- | --- |
| **R1** | **Ein Wurzelbefehl, Pfade darunter.** Neue Funktionen werden zu Pfaden unter `/eco`, nicht zu neuen Einzelbefehlen. | Kein Namensraum-Wildwuchs, keine Kollisionen mit anderen Plugins. |
| **R2** | **Kanonische Segmente sind stabil.** Sie sind ASCII, kleingeschrieben und werden nie umbenannt — nur ergänzt. | Permissions, Message-Keys und Audit-Aktionen hängen am Pfad. |
| **R3** | **Deutsch ist Anzeige, nicht Struktur.** Deutsche Schreibweisen sind Aliase aus `commands.yml`, keine `case`-Zweige im Code. | Heute stehen `geld`, `status`, `zinsen`, `aufnehmen` fest im Java-Code. |
| **R4** | **Unterbefehle brauchen keinen `plugin.yml`-Eintrag.** `plugin.yml` deklariert nur Wurzel- und Kurzbefehle; alles darunter routet das Plugin. | Erweitern ohne Serverneustart-Artefakte und ohne `plugin.yml`-Pflege. |
| **R5** | **Befehle sind Adapter.** Sie prüfen, parsen, rufen einen Dienst auf und formatieren das Ergebnis. Wirtschaftslogik liegt in `EconomyService`, `CreditService` und den künftigen Netzwerkdiensten. | Testbarkeit, Wiederverwendung durch GUI und API. |
| **R6** | **Ein Pfad, ein Recht.** Jeder Pfad hat genau einen Permission-Knoten; feinere Rechte sind Unterknoten. | Nachvollziehbare Rechtevergabe in LuckPerms & Co. |
| **R7** | **Ein Ergebnis, ein Message-Key.** Kein Befehl baut Nachrichten aus Textbausteinen im Code. | Übersetzbarkeit, konsistente Formulierungen. |
| **R8** | **Vorladen ist ein Hook, keine Typprüfung.** Module deklarieren selbst, welche Daten sie vorab brauchen. | Die `instanceof`-Kette in `AbstractEconomyCommand` verhindert heute genau das. |
| **R9** | **Geldänderungen sind atomar; Fernbuchungen sind idempotent.** Ein `ANY`-Transfer erzeugt einen Audit-Eintrag und ist bei gleichem `idempotency_key` wirkungslos. Lokale/Admin-/Zins-/Kreditbuchungen haben heute keinen Audit-Eintrag und keine vom Aufrufer steuerbare tx-id. | Absturz, Doppelklick und Retry dürfen kein Geld erzeugen; der aktuelle Stand erfüllt das nur für `ANY`-Transfers. |
| **R10** | **Kein Befehl schreibt ohne Dienst.** Kein Modul öffnet eine Datenbankverbindung, keine Datei und keinen Scheduler. | Sonst umgeht jeder neue Befehl die Konsistenzregeln. |

---

## 4. Namensraum und Wurzelbefehle

### 4.1 Warum ein Wurzelbefehl

`plugin.yml` kennt nur flache Befehlsnamen. Jeder neue Einzelbefehl bedeutet:
neuer Eintrag, neue Permission, neuer Hilfeabschnitt, neuer Alias-Satz — und
eine wachsende Liste von Namen, die mit anderen Plugins kollidieren können.
Ein Wurzelbefehl mit Pfaden verschiebt all das in die Laufzeit und ist damit
die Voraussetzung für R4.

### 4.2 Wurzelbefehle und Kurzbefehle

| Befehl | Rolle | Pfad | `plugin.yml` | Aliase |
| --- | --- | --- | --- | --- |
| `/eco` | **kanonischer Wurzelbefehl** ✅ | — | nein (dynamisch registriert) | — |
| `/economysystem` | bestehender Wurzelbefehl ✅ | — | ja | `es` |
| `/money` | Kurzbefehl ✅ | `balance` | ja | `geld`, `balance`, `coins` |
| `/pay` | Kurzbefehl ✅ | `pay` | ja | `zahlen`, `ueberweisen` |
| `/kredit` | Kurzbefehl ✅ | `credit` | ja | `credit`, `darlehen` |
| `/economysystem-gui` | Kurzbefehl ✅ | `gui` | ja | `eco-gui`, `egui` |
| `/economysystem-admin` | Kurzbefehl ✅ (veraltet) | `admin` | ja | `esa`, `ecoadm`, `ecoadmin` |

Regeln:

- **`/eco` und `/economysystem` sind derselbe Befehl.** Beide zeigen auf
  denselben Baum; `/economysystem` bleibt dauerhaft bestehen (R2).
- **`/eco` wird dynamisch registriert.** Er steht nicht in der `plugin.yml`,
  sondern wird wie ein Alias über die Command-Map eingetragen. Ist der Name
  bereits durch ein anderes Plugin belegt, bleibt `/economysystem` der Zugang
  und das Plugin loggt eine Warnung.
- **Kurzbefehle sind dauerhaft.** Sie sind Bequemlichkeit und
  Verträglichkeit, keine zweite Befehlsstruktur: sie rufen denselben Pfad
  mit denselben Prüfungen auf.
- **Ein Kurzbefehl darf keine Argumente vorbelegen**, außer er ist in
  `commands.yml` ausdrücklich als `shortcut` mit `args` definiert
  (Abschnitt 8).
- **Neue Funktionen erhalten keinen neuen Kurzbefehl.** Ausnahme: eine
  Funktion, die nachweislich täglich von allen Spielern genutzt wird
  (Beispiel: `/money`). Diese Ausnahme wird hier eingetragen, nicht
  ad hoc entschieden.
- **`/economysystem-admin` wird nicht mehr erweitert.** Neue Admin-Funktionen
  erscheinen ausschließlich unter `admin.*`. Der Kurzbefehl bleibt als
  Routing auf `admin` erhalten.

### 4.3 Was `plugin.yml` deklariert — und was nicht

| In `plugin.yml` | Nicht in `plugin.yml` |
| --- | --- |
| Kurzbefehle (`money`, `pay`, `kredit`, `…-gui`) | der Wurzelbefehl `/eco` (dynamisch registriert) |
| Permissions (als Dokumentation) | jeder Pfad darunter, jede Untergruppe (`admin`, `credit`, `bank`) und die daraus abgeleiteten Rechte |

Damit gilt: **eine neue Funktion unterhalb von `/eco` erfordert keine
Änderung an `plugin.yml`** — und damit auch keine Anpassung der dortigen
`usage`-Texte, die heute jeden Unterbefehl doppelt beschreiben.

---

## 5. Kanonischer Befehlsbaum

### 5.1 Übersicht

```
/eco
├── balance [<konto>]                          # ✅ /money
│   └── top [<seite>]                          # ✅ /money top
├── pay <konto> <betrag> [--reason <text>]     # ✅ /pay
│   └── status <tx-id>                         # ⏳ Netzwerk, nicht implementiert
├── credit                                     # ✅ /kredit
│   ├── take <betrag> <tage>
│   ├── repay [<betrag>]
│   ├── info
│   └── list
├── gui [<menü>]                               # ✅ /economysystem-gui
├── info                                       # ✅ /economysystem-admin info
├── help [<seite>]                             # ✅ /economysystem help (messages.yml)
└── admin
    ├── balance
    │   ├── add <konto> <betrag>
    │   ├── remove <konto> <betrag>
    │   ├── set <konto> <betrag> [--confirm]
    │   └── reset <konto> [--confirm]
    ├── credit
    │   ├── list <konto>
    │   └── forgive <kredit-id> [--confirm]     # ⏳
    ├── interest
    │   ├── show
    │   ├── set savings|credit <faktor>
    │   └── run [--dry-run] [--confirm]         # ⏳
    ├── reload [local|network] [--confirm]      # network ⏳
    ├── network
    │   ├── status                            # ⏳
    │   └── scopes                            # ⏳
    ├── audit [<seite>] [--player <name>]     # ⏳
    └── test interest-cycle add <anzahl>      # ✅ (nur mit tests.enabled)
```

### 5.2 Spielerpfade

| Pfad | Syntax | Permission | Message-Präfix | GUI | Netzwerk |
| --- | --- | --- | --- | --- | --- |
| `balance` | `/eco balance [<konto>]` | `economysystem.command.balance` (eigenes), `.balance.other` (fremdes) | `command.balance` | ✅ Item „Guthaben“ | liest Konto; `economy-scope` teilt keine Konten |
| `balance.top` | `/eco balance top [<seite>]` | `economysystem.command.balance.top` | `command.balance.top` | — | Rangliste; eigenes Recht, nicht in `balance` enthalten |
| `pay` | `/eco pay <konto> <betrag> [--reason]` | `economysystem.command.pay` | `command.pay` | ✅ Item „Überweisen“ | `LOCAL` (auch offline)/`ANY`; `PROXY` ⏳; `--reason` wird nicht gespeichert |
| `pay.status` ⏳ | `/eco pay status <tx-id>` | `economysystem.command.pay` | `command.pay.status` | — | Status einer eingereihten Überweisung |
| `credit.take` | `/eco credit take <betrag> <tage>` | `economysystem.command.credit` | `command.credit.take` | ✅ | Kreditrahmen gilt für das Konto; `economy-scope` teilt keine Konten |
| `credit.repay` | `/eco credit repay [<betrag>]` | `economysystem.command.credit` | `command.credit.repay` | ✅ | — |
| `credit.info` | `/eco credit info` | `economysystem.command.credit` | `command.credit.info` | ✅ | — |
| `credit.list` | `/eco credit list` | `economysystem.command.credit` | `command.credit.list` | — | nennt Kredite; keine tx-id |
| `gui` | `/eco gui [<menü>]` | `economysystem.command.gui` | `command.gui` | — | Menüinhalte hängen an den Rechten des Spielers |
| `info` | `/eco info` | `economysystem.command.info` | `command.info` | — | zeigt Speicher, Währung, Zinsen, Vault; **nicht** Scope/Server/Modus |
| `help` | `/eco help [<seite>]` | keine (immer erlaubt) | `help`/`help-admin` (messages.yml) | — | Seite 2 nur mit Admin-Recht; `help.yml` nur über `/eco admin help` |

### 5.3 Adminpfade

| Pfad | Syntax | Permission | Message-Präfix | Netzwerk |
| --- | --- | --- | --- | --- |
| `admin.balance.add` | `/eco admin balance add <konto> <betrag> [--reason]` | `economysystem.command.admin.balance` | `command.admin.balance.add` | schreibt direkt ins Zielkonto; `--reason` wird nicht gespeichert |
| `admin.balance.remove` | `/eco admin balance remove <konto> <betrag> [--reason]` | `economysystem.command.admin.balance` | `command.admin.balance.remove` | dito |
| `admin.balance.set` | `/eco admin balance set <konto> <betrag> --confirm` | `economysystem.command.admin.balance` | `command.admin.balance.set` | dito |
| `admin.balance.reset` | `/eco admin balance reset <konto> --confirm` | `economysystem.command.admin.balance` | `command.admin.balance.reset` | dito |
| `admin.credit.list` | `/eco admin credit list <konto>` | `economysystem.command.admin.credit` | `command.admin.credit.list` | liest Kredite des Kontos; kein Scope-Filter |
| `admin.credit.forgive` ⏳ | `/eco admin credit forgive <kredit-id> --confirm [--reason]` | `economysystem.command.admin.credit` | `command.admin.credit.forgive` | wirkt scope-übergreifend nur mit `admin.network` |
| `admin.interest.show` | `/eco admin interest show` | `economysystem.command.admin.interest` | `command.admin.interest.show` | Faktoren kommen aus der lokalen `settings.yml` |
| `admin.interest.set` | `/eco admin interest set savings\|credit <faktor>` | `economysystem.command.admin.interest` | `command.admin.interest.set` | schreibt nur in die lokale `settings.yml`; kein Scope-Schreibzugriff |
| `admin.interest.run` ⏳ | `/eco admin interest run [--dry-run] --confirm` | `economysystem.command.admin.interest` | `command.admin.interest.run` | nicht implementiert |
| `admin.reload` | `/eco admin reload [local]` | `economysystem.command.admin.reload` | `command.admin.reload` | nur lokal; `network` ist nicht implementiert |
| `admin.network.status` ⏳ | `/eco admin network status` | `economysystem.command.admin.network` | `command.admin.network.status` | Server, Lease, Outbox-Rückstand |
| `admin.network.scopes` ⏳ | `/eco admin network scopes` | `economysystem.command.admin.network` | `command.admin.network.scopes` | Scope → Konten → Server |
| `admin.audit` ⏳ | `/eco admin audit [<seite>] [--player <name>] [--action <aktion>]` | `economysystem.command.admin.audit` | `command.admin.audit` | liest Audit-Einträge aller Server |
| `admin.test.interest-cycle` | `/eco admin test interest-cycle add <anzahl>` | `economysystem.command.admin.test` | `command.admin.test` | nur bei `tests.enabled: true` (Kein Build-Ausschluss mehr, Abschnitt 15.5) |

### 5.4 Verhaltensregeln der Gruppen

**`balance`**

- Ohne Argument zeigt das **eigene** Konto. Die Konsole erhält die Hilfe,
  nicht ein Fehler.
- `top` und `help` sind reservierte Wörter und haben Vorrang vor
  Spielernamen. `#uuid:<uuid>` ist **nicht** implementiert; ein Spieler
  namens `top` ist über diesen Pfad derzeit nicht eindeutig erreichbar.
- Fremdes Guthaben nur mit `.balance.other`; die Tab-Vervollständigung
  schlägt fremde Konten nur mit diesem Recht vor.

**`pay`**

- Betrag muss positiv sein, das Guthaben decken und darf am Zielkonto
  `currency.max-balance` nicht überschreiten (heutiges Verhalten bleibt).
- Selbstzahlung ist ein Fehler (`command.pay.self`), keine stille No-Op.
- Im Modus `LOCAL` wird auch an **offline** Ziele gebucht, sofern das
  Konto bereits existiert; es wird dann gegen das gespeicherte Konto
  gearbeitet. Ein unbekannter Name führt zu `player-not-found`.
- Die Quittung nennt **nur bei `ANY`** eine tx-id. Lokale Buchungen
  nennen keine; `/eco pay status` und `/eco admin audit` sind nicht
  implementiert.

**`credit`**

- `take` prüft in dieser Reihenfolge: System aktiv → Kreditplätze frei →
  Laufzeit gültig → Betrag ≥ Minimum → Rahmen ausreichend →
  Maximalguthaben. Diese Reihenfolge entspricht dem aktuellen Code und
  macht Fehlermeldungen eindeutig.
- `repay` tilgt ohne Betrag so viel wie möglich; `list` und `info` sind
  lesend und brauchen keinen Cooldown.

**`admin`**

- Jeder Adminpfad verlangt `economysystem.command.admin` **oder** den
  spezifischen Unterknoten. Wer nur `…admin.balance` hat, sieht `interest`
  weder in der Hilfe noch in der Tab-Vervollständigung.
- `set` und `reset` sind **bestätigungspflichtig** (Abschnitt 6.4).
  `forgive`, `interest run` und `reload network` sind nicht implementiert.

---

## 6. Grammatik und Argumente

### 6.1 Satzschema

```
<wurzel> <gruppe(n)> <aktion> <subjekt> <wert> [--flags]
```

- **Reihenfolge:** erst Aktion, dann Subjekt, dann Wert. Also
  `admin balance add <konto> <betrag>`, nicht `admin balance <betrag> add`.
- **Maximal eine Aktion pro Aufruf.** Kein `add remove`, keine Ketten.
- **Flags stehen immer am Ende.** Alles nach dem ersten `--` ist Flag.
- **Kein Befehl ändert sein Schema je nach Aufrufer.** Ein Admin sieht
  dieselbe Syntax wie ein Spieler, nur mit mehr Pfaden.

### 6.2 Geschlossene Verb-Liste

Nur diese Aktionen sind zulässig. Synonyme sind Aliase in `commands.yml`,
niemals zusätzliche Pfade.

| Verb | Bedeutung | Beispiel |
| --- | --- | --- |
| `add` | etwas erhöhen oder anlegen | `admin balance add` |
| `remove` | etwas verringern oder entfernen | `admin balance remove` |
| `set` | exakt setzen | `admin interest set savings 1.3` |
| `reset` | auf Standard zurücksetzen | `admin balance reset` |
| `list` | mehrere Einträge auflisten | `credit list` |
| `info` | einen Zustand anzeigen | `credit info` |
| `show` | Konfiguration/Status anzeigen | `admin interest show` |
| `top` | Rangliste | `balance top` |
| `run` | einen Vorgang jetzt ausführen | `admin interest run` |
| `reload` | Konfiguration neu laden | `admin reload` |

Nicht zulässig sind: `give`/`take` (→ `add`/`remove`), `pay`/`zahlen`
innerhalb von `credit` (→ `repay`), `status` als Aktion außerhalb von
`pay.status` und `admin.network.status`, sowie jede deutsche Aktion als
eigenes Pfadsegment.

### 6.3 Argumenttypen

| Typ | Schreibweise | Regeln |
| --- | --- | --- |
| **Betrag** | `100`, `1.234,56`, `1000.5` | `.` und `,` als Dezimaltrennzeichen; keine Tausendertrennzeichen; keine negativen Eingaben; keine Exponentialschreibweise; höchstens `currency.decimals` Nachkommastellen; Obergrenze `currency.max-balance` |
| **Konto** | `<name>` | `@s`, `#uuid:<uuid>` und `#konto:<scope>:<uuid>` sind **nicht** implementiert; siehe 6.5 |
| **Seite** | `1`, `2`, `3` | ganze Zahl ≥ 1; zu große Seiten werden auf die letzte Seite geklemmt, nicht abgelehnt |
| **Faktor** | `1.3`, `0,5` | positiver Dezimalwert, `> 0` |
| **Laufzeit** | `7`, `30`, `90` | ganze Zahl zwischen `credits.min-days` und `credits.max-days` |
| **Menü** | `main`, `credit`, `admin` | muss in `gui.yml` existieren |
| **tx-id** | `7f3a91e0` | 8 hexadezimale Zeichen; heute nur in der `ANY`-Erfolgsnachricht, nicht als Eingabe |
| **Kredit-ID** | `#kredit:<uuid>` ⏳ | nicht implementiert; laufende Nummern aus `credit info` sind nur Anzeige |

### 6.4 Flags

| Flag | Wirkung | Recht | Hinweise |
| --- | --- | --- | --- |
| `--reason <text>` | wird geparst und auf `commands.reason-max-length` gekürzt, aber **nicht gespeichert** | jeder Schreibende | derzeit ohne Wirkung auf Audit/Transaktion |
| `--silent` | wird geparst und **ignoriert** | Admin-Pfade | keine stille Buchung |
| `--dry-run` | wird geparst und **ignoriert** | Admin-Pfade | **keine** Simulation; es wird echt geschrieben |
| `--confirm` | Bestätigung für `admin.balance.set` und `admin.balance.reset` | wie der Pfad selbst | im selben Aufruf erforderlich; GUI-Bestätigung ersetzt es nicht |
| `--player <name>` | gehört zu `admin.audit` ⏳ | `admin.audit` | nicht implementiert |
| `--action <aktion>` | gehört zu `admin.audit` ⏳ | `admin.audit` | nicht implementiert |
| `--server <id>` | gehört zu `admin.network` ⏳ | `admin.network` | nicht implementiert |
| `--scope <id>` | gehört zu `admin.network` ⏳ | `admin.network` | nicht implementiert |

Regeln zu `--confirm`:

- Bestätigungspflichtig sind heute nur `admin.balance.set` und
  `admin.balance.reset`. `forgive`, `interest run` und `reload network`
  sind nicht implementiert.
- Ohne `--confirm` antwortet der Befehl mit
  `command._default.error.confirm-required` und nennt die genaue Syntax
  zum Wiederholen — keine Ja/Nein-Abfrage im Chat.
- Die GUI-Bestätigung ist ein eigener Dialog. Ein GUI-Item mit
  `admin balance set`/`reset` führt den Befehl erst nach dem Dialog aus;
  der Item-Klick allein bestätigt nicht.
- Aufrufe aus der Konsole dürfen `--confirm` verwenden; ein Audit-Eintrag
  entsteht dabei heute nicht.

### 6.5 Kontoauflösung (UUID-first)

Richtungsvorgabe aus `AGENT.md`: **Im Backend gilt ausschließlich die UUID;
Namen sind Ein- und Ausgabe.**

| Eingabe | Auflösung |
| --- | --- |
| `<name>` | zuerst online auf diesem Server, dann Konten-Cache, dann `loadByName` im Speicher |
| `@s` | **nicht implementiert** |
| `#uuid:<uuid>` | **nicht implementiert** |
| `#konto:<scope>:<uuid>` | **nicht implementiert** |

- Ein Name wird ohne UUID-Syntax aufgelöst. Gibt es mehrere Konten mit
  demselben Namen (Namenswechsel), entscheidet der Speicher bzw. der
  zuletzt bekannte Eintrag; eine explizite Mehrdeutigkeitswarnung gibt es
  nicht.
- Namen werden nie gespeichert, ohne dass das Konto die UUID kennt.
- Anzeigen verwenden den zuletzt bekannten Namen.

### 6.6 Antwortformat

Jedes Ergebnis hat dieselbe Form — Chat, GUI-Rückmeldung und API-Antwort
unterscheiden sich nur im Transport, nicht im Inhalt:

```
<Kopfzeile>       Titel + Kontext (z. B. "Guthaben von Minefalo")
<Zeilen>          modulabhängig; Hilfe und Top blättern
<Fußzeile>        nur bei ANY-Fernbuchungen: "tx 7f3a91e0"
```

- Kopf und Zeilen stammen aus `messages.yml` bzw. den Hilfe-Listen.
- Lokale und Admin-Buchungen nennen **keine** tx-id.
- `--silent` hat keine Wirkung und unterdrückt nichts.

### 6.7 Prüfreihenfolge

Der **aktuelle Router** prüft in dieser Reihenfolge:

1. **Registrierung und Pfad** — existiert der Pfad? Bei Fehler:
   `unknown-subcommand` oder `usage`.
2. **Herkunft** — `playerOnly`-Pfade weisen die Konsole ab.
3. **READ_ONLY** — die Positivliste aus `ReadOnlyPolicy`; alle anderen
   Pfade werden abgewiesen.
4. **Permission** — Pfadknoten, alter/neuer Knoten als ODER.
5. **Syntax und Flags** — Argumentanzahl, Flag-Rechte; `usage` mit der
   vollständigen Syntax.
6. **Bestätigung** — `--confirm` oder eine echte GUI-Bestätigung.
7. **Rate-Limit und Cooldown** — vor der Fachlogik; eine Ablehnung
   verbraucht kein Kontingent.
8. **Ausführung** — über das Modul, genau einmal.

Die **Werte- und Fachprüfungen** (Beträge, Seiten, Faktoren, Guthaben,
Rahmen, Maximalbetrag, Kreditplätze) liegen innerhalb des Moduls, also
nach Stufe 8. Das ist der aktuelle Stand; die frühere achtstufige
Zielreihenfolge mit „Systemzustand vor Permission“ ist nicht umgesetzt.

Permission wird **vor** Syntax geprüft: Wer keine Rechte hat, soll die
Syntax nicht erfahren.

---

## 7. Berechtigungen

### 7.1 Ableitung aus dem Pfad

```
economysystem.command.<pfad.mit.punkten>
```

| Pfad | Permission |
| --- | --- |
| `balance` | `economysystem.command.balance` |
| `pay` | `economysystem.command.pay` |
| `credit.*` | `economysystem.command.credit` |
| `gui` | `economysystem.command.gui` |
| `info` | `economysystem.command.info` |
| `admin.balance.*` | `economysystem.command.admin.balance` |
| `admin.credit.*` | `economysystem.command.admin.credit` |
| `admin.interest.*` | `economysystem.command.admin.interest` |
| `admin.reload` | `economysystem.command.admin.reload` |
| `admin.network.*` ⏳ | `economysystem.command.admin.network` |
| `admin.audit` ⏳ | `economysystem.command.admin.audit` |
| `admin.test.*` | `economysystem.command.admin.test` |

**Regel:** Die Permission gehört zur **Gruppe** (`admin.balance`), nicht zur
einzelnen Aktion — außer eine Aktion ist so gefährlich, dass sie ein eigenes
Recht braucht (`admin.network`, `admin.audit`, `admin.test`). Das hält
LuckPerms-Gruppen klein, ohne Schutz zu verlieren.

### 7.2 Vererbung und Wildcards

- `economysystem.command.admin` schaltet die Admin-Pfade auf Router-Ebene
  frei. **Ausnahme:** `admin.test` verlangt zusätzlich
  `economysystem.tests` oder `economysystem.command.admin.test`.
- Ein Unterknoten schaltet **nicht** seinen Elternknoten frei:
  `…admin.balance` erlaubt keine Zinsen und kein Audit.
- Wildcards (`economysystem.command.*`) sind Bukkit-Standard und werden
  nicht zusätzlich im Code ausgewertet. Der Code prüft immer den
  **spezifischen** Knoten; die Wildcard-Auflösung übernimmt die
  Permission-Implementierung des Servers.

### 7.3 Capability-Knoten

Diese Rechte hängen an keiner Aktion, sondern an einer Fähigkeit:

| Knoten | Wirkung |
| --- | --- |
| `economysystem.bypass.cooldown` | ignorieren von `network.limits.*.cooldown` |
| `economysystem.bypass.rate-limit` | ignorieren der Mengenbegrenzung |
| `economysystem.pay.remote` | Überweisung an Ziele, die nicht auf diesem Server online sind (`ANY`; `PROXY` ist nicht implementiert) |
| `economysystem.pay.offline` ⏳ | nicht implementiert; `ANY` nutzt `pay.remote` |
| `economysystem.notify.admin` ⏳ | nicht implementiert |

### 7.4 Mapping: heute → Ziel

| Heute ✅ | Ziel 🟡 | Übergang |
| --- | --- | --- |
| `economysystem.balance` | `economysystem.command.balance` | beide akzeptiert, alte Form veraltet |
| `economysystem.balance.others` | `economysystem.command.balance.other` | beide akzeptiert |
| `economysystem.balance.top` | `economysystem.command.balance.top` | beide akzeptiert |
| `economysystem.pay` | `economysystem.command.pay` | beide akzeptiert |
| `economysystem.kredit` | `economysystem.command.credit` | beide akzeptiert |
| `economysystem.gui` | `economysystem.command.gui` | beide akzeptiert |
| `economysystem.admin` | `economysystem.command.admin` | **bleibt dauerhaft** als Sammelrecht; Testbefehle brauchen zusätzlich `economysystem.tests` |
| `economysystem.reload` | `economysystem.command.admin.reload` | beide akzeptiert |
| `economysystem.tests` | `economysystem.command.admin.test` | beide akzeptiert |

Der Übergang ist ein **ODER**: Der Code akzeptiert alten und neuen Knoten,
bis die alte Form in einer Hauptversion entfernt wird. Die
`plugin.yml`-Permissions bleiben als Dokumentation erhalten und werden um
die neuen Knoten ergänzt.

---

## 8. `commands.yml` v2

Heute enthält `commands.yml` (Version 1, siehe `config-version.yml`) nur `commands.<name>.aliases`.
Deutsche Unterbefehlswörter stehen dagegen fest im Java-Code
(`case "geld"`, `case "zinsen"`, `case "aufnehmen"`). Das ist der zweite
Erweiterungsblocker neben der `instanceof`-Kette.

### 8.1 Zielschema

```yaml
# ==========================================================
#  EconomySystem - Befehle, Kurzbefehle und Aliase
# ==========================================================
# Kanonische Pfade stehen in Commands.md. Diese Datei ändert nur
# Schreibweisen, niemals die Struktur.

# Wörter, die innerhalb eines Pfades als Synonym gelten.
# Sie werden VOR dem Routing ersetzt und sind keine eigenen Pfade.
subcommand-words:
  balance:   [ "guthaben", "kontostand" ]
  top:       [ "rangliste", "bestenliste" ]
  pay:       [ "zahlen", "ueberweisen", "ueberweisung" ]
  credit:    [ "kredit", "darlehen" ]
  take:      [ "aufnehmen", "nehmen" ]
  repay:     [ "bezahlen", "zurueckzahlen", "tilgen" ]
  info:      [ "status" ]
  list:      [ "liste" ]
  add:       [ "give", "hinzufuegen" ]
  remove:    [ "entfernen" ]
  set:       [ "setzen" ]
  reset:     [ "zuruecksetzen" ]
  savings:   [ "spar", "sparen" ]
  interest:  [ "zinsen" ]
  reload:    [ "neu-laden" ]
  run:       [ "trigger", "ausfuehren" ]
  audit:     [ "protokoll" ]
  network:   [ "netzwerk" ]

# Aliase je Wurzel- oder Kurzbefehl (wie bisher, unverändert gültig).
commands:
  eco:
    # Der Wurzelbefehl wird dynamisch registriert. 'es' bleibt beim
    # bestehenden Befehl economysystem (gleicher Baum, gleiches Verhalten).
    aliases: [ ]
  economysystem:
    aliases: [ "es" ]
  money:
    aliases: [ "geld", "balance", "coins" ]
  pay:
    aliases: [ "zahlen", "ueberweisen" ]
  kredit:
    aliases: [ "credit", "darlehen" ]
  economysystem-gui:
    aliases: [ "eco-gui", "egui" ]
  economysystem-admin:
    aliases: [ "esa", "ecoadm", "ecoadmin" ]

# Kurzbefehle: ordnet einem in plugin.yml deklarierten Befehl einen Pfad zu.
# Neue Unterbefehle brauchen hier KEINEN Eintrag (R4).
shortcuts:
  money:
    path: "balance"
  pay:
    path: "pay"
  kredit:
    path: "credit"
  economysystem:
    path: "help"
  economysystem-gui:
    path: "gui"
  economysystem-admin:
    path: "admin"
  # Ein zusaetzlicher Kurzbefehl (z. B. /guthaben-top) braucht weiterhin
  # einen Eintrag in plugin.yml; hier wird nur der Pfad zugeordnet.
  # guthaben-top:
  #   path: "balance.top"
  #   args: [ "1" ]
  #   permission: "economysystem.command.balance.top"

# Vorschläge für die Tab-Vervollständigung je Pfad.
# Fehlt ein Eintrag, gelten die Standardlisten aus settings.yml.
suggestions:
  pay.amount: [ "1", "10", "100", "1000", "10000" ]
  credit.take.amount: [ "100", "500", "1000", "5000" ]
  credit.take.days: [ "7", "30", "90" ]
  credit.repay.amount: [ "10", "50", "100", "500" ]
  admin.interest.set.factor: [ "1.0", "1.3", "2.0" ]
  admin.audit.action: [ "pay", "admin.balance.set", "admin.interest.run" ]
```

### 8.2 Regeln

- **`subcommand-words` ersetzen vor dem Routing.** Kein Modul kennt
  deutsche Wörter. Ein unbekanntes Wort bleibt unverändert und führt zur
  normalen Fehlermeldung mit der kanonischen Syntax.
- **Ein Synonym darf nicht zwei Pfade bedeuten.** Beim Laden wird geprüft;
  bei Doppeldeutigkeit gewinnt der **kanonische** Name, das Synonym wird
  mit Warnung übersprungen (gleiche Politik wie heute bei Alias-Kollisionen).
- **Ein Alias darf keinen Pfad ersetzen.** `subcommand-words` ändern keine
  Struktur, `shortcuts` erzeugen keine neuen Rechte.
- **Validierung wie heute:** Aliase nur `[a-z0-9_-]{1,32}`, keine
  Leerzeichen, keine Kollision mit bestehenden Befehlen.
- **`reload` lädt die Datei neu** und baut den Router neu auf; bereits
  laufende GUI-Sitzungen fallen auf ihre Ausgangsmenü zurück.
- **Kein Modul liest `commands.yml` selbst.** Nur der Router tut das.

### 8.3 Migration config-version 1 → 2

1. `ConfigUpdater` ergänzt `subcommand-words`, `shortcuts` und
   `suggestions` aus der Standarddatei; vorhandene `commands:`-Blöcke
   bleiben unverändert (bestehende Werte bleiben erhalten, Kommentare
   werden aus der Standarddatei aktualisiert).
2. Die bisher hartcodierten deutschen Wörter werden als Standardwerte
   eingetragen — bestehende Aufrufe wie `/kredit aufnehmen` funktionieren
   damit unverändert weiter.
3. Die Version von `commands.yml` wird in `config-version.yml` auf `2` gesetzt.
4. Ein Wechsel zurück ist möglich: fehlt `subcommand-words`, greifen die
   eingebauten Standardwerte.

---

## 9. Nachrichten, Hilfe, Tab-Vervollständigung

### 9.1 Key-Schema

```
command.<pfad>.<ergebnis>
```

| Ebene | Beispiel |
| --- | --- |
| Pfad + Ergebnis | `command.pay.sent` |
| Pfad + Fehlercode | `command.pay.error.insufficient-funds` |
| Gruppe als Fallback | `command.pay.error.*` → `command._default.error.*` |
| Kein Eintrag | eingebauter Standardtext im Code |

- Platzhalter wie heute: `%player%`, `%amount%`, `%balance%`, `%currency%`,
  `%max%`, `%min%`, `%page%`, `%pages%`, `%tx%`.
- `%prefix%` wird weiterhin automatisch vorangestellt, wenn er fehlt.
- Alte Keys (`balance-self`, `usage-pay`, `pay-sent`, …) bleiben als
  **Fallback** lesbar, damit bestehende `messages.yml`-Dateien nicht sofort
  gepflegt werden müssen. Neu geschriebene Keys haben Vorrang.

### 9.2 Standard-Fehlercodes

Jeder Pfad verwendet dieselben Codes für dieselben Situationen:

| Code | Bedeutung | Standardtext (deutsch) |
| --- | --- | --- |
| `error.no-permission` | Recht fehlt | „Dazu hast du keine Berechtigung.“ |
| `error.player-only` | Konsole bei Spielerbefehl | „Dieser Befehl ist nur für Spieler verfügbar.“ |
| `error.player-not-found` | Konto unbekannt | „Der Spieler %player% wurde nicht gefunden.“ |
| `error.unknown-subcommand` | Pfad unbekannt | „Unbekannter Befehl %input%. Meintest du %suggestion%?“ |
| `error.invalid-amount` | Betrag unparsbar | „%input% ist kein gültiger Betrag.“ |
| `error.amount-too-large` | über `max-balance` | „Der Betrag überschreitet das Maximum von %max%.“ |
| `error.insufficient-funds` | Guthaben zu klein | „Du hast nicht genug Geld. Guthaben: %balance%“ |
| `error.rate-limited` | zu viele Aufrufe | „Bitte warte einen Moment.“ |
| `error.confirm-required` | `--confirm` fehlt | „Wiederhole den Befehl mit --confirm: %syntax%“ |
| `error.read-only` | Server im Modus `READ_ONLY` | „Dieser Server ist schreibgeschützt.“ |
| `error.not-enabled` | Funktion aus | „Diese Funktion ist derzeit deaktiviert.“ |
| `error.out-of-scope` ⏳ | Konto in fremdem Scope | „Das Konto gehört zum Bereich %scope%.“ |
| `error.storage` | Speicherfehler | „Es gab einen Fehler beim Datenzugriff.“ |
| `error.internal` | unerwarteter Fehler | „Der Vorgang ist fehlgeschlagen. Siehe Konsole.“ |
| `usage` | Syntax falsch | vollständige, korrekte Syntax |
| `dry-run` ⏳ | Vorschau; **nicht implementiert** | keine Ausgabe, weil `--dry-run` ignoriert wird |

`usage` nennt **immer** die vollständige kanonische Syntax des aufgerufenen
Pfades — der Grund, warum `plugin.yml` die `usage`-Texte nicht mehr doppelt
pflegen muss.

### 9.3 Hilfe

**Aktueller Stand:**

- `/eco help [<seite>]` liest die Listen `help` und `help-admin` aus
  `messages.yml`. Seite 1 ist immer erlaubt; Seite > 1 verlangt
  `economysystem.admin` (oder den alten Admin-Knoten).
- `/eco admin help [<seite>]` liest die Seiten aus `help.yml`.
- Module liefern **keine** Hilfe-Beiträge; es gibt keine Registry-basierte
  Hilfe und keine automatische Permission-Filterung der Abschnitte.
- `help.yml` ist eine eigenständige, gepflegte Datei. Sie überschreibt
  nichts aus `messages.yml`.
- Ein neuer Befehl muss in `help.yml`, `messages.yml` und `README.md`
  dokumentiert werden (Abschnitt 14).

### 9.4 Tab-Vervollständigung

- Vervollständigt wird nur, was der Aufrufer auch ausführen darf.
- Spielernamen erscheinen sofort: bei leerer oder einbuchstabiger Eingabe
  alle Online-Spieler, ab zwei Zeichen alle bekannten Konten, auch von
  offline Spielern.
- Reihenfolge: Unterpfade alphabetisch, dann Konten (Name), dann Zahlen.
- Keine Vorschläge für `--reason` (Freitext) und keine Vorschläge für
  Argumente, deren vorheriges Argument ungültig ist.
- Maskierte Namen (`#uuid:`-Form) sind nicht implementiert und werden
  weder vorgeschlagen noch akzeptiert.
- Vorschlagslisten stehen in `commands.yml → suggestions` (Abschnitt 8);
  `settings.yml → suggestions` bleibt als Standard für die Spielerpfade.
- Ein Modul liefert seine Vorschläge selbst (`complete()`), der Router
  filtert nach Prefix und Permission.

---

## 10. Erweiterbarkeit: der Command-Modul-Vertrag

### 10.1 Gründe

Heute entscheidet `AbstractEconomyCommand` per `instanceof`-Kette, welche
Konten vorgeladen werden — und wartet dabei pauschal mindestens einen Tick.
Jeder neue Befehl müsste dort eingetragen werden; wird er vergessen, endet
er in einer irreführenden `storage-error`-Meldung (vgl. `SICHERHEITSAUDIT.md`,
NEU-3). Genau das verhindert der Modul-Vertrag.

### 10.2 Interface-Skizze

```java
public interface CommandModule {

    /** Stabile Kennung, identisch mit dem letzten Pfadsegment. */
    String id();

    /** Kanonischer Pfad ohne Wurzel, z. B. "admin.balance.add". */
    String path();

    /** Permission-Knoten; Standard: economysystem.command.<path>. */
    default String permission() {
        return "economysystem.command." + path().replace('.', '.');
    }

    /** Attribute, die im Baum hängen (Kurzbefehle, Unterpfade). */
    default List<String> aliases() { return List.of(); }

    /** true = Konsole wird mit error.player-only abgewiesen. */
    default boolean playerOnly() { return false; }

    /** Mindestabstand zwischen zwei Aufrufen in ms; 0 = kein Cooldown. */
    default long cooldownMillis() { return 0L; }

    /** Audit-Aktionen, die dieses Modul schreiben kann. */
    default Set<String> auditActions() { return Set.of(); }

    /** Unterpfade anmelden; Standard: keine. */
    default void register(CommandTree tree) { }

    /**
     * Lädt vorab, was execute() braucht (Konten, Kredite, Rangliste).
     * Läuft im Storage-Executor, niemals auf dem Tick-Thread.
     */
    default CompletableFuture<?> prepare(CommandContext context) {
        return CompletableFuture.completedFuture(null);
    }

    /** Führt aus. Wird erst nach erfolgreichem prepare() aufgerufen. */
    CommandResult execute(CommandContext context);

    /** Vorschläge für das aktuelle Argument. */
    default List<String> complete(CommandContext context) { return List.of(); }
}
```

```java
public record CommandContext(
        CommandSender sender,
        UUID actorId,          // null bei CONSOLE
        String label,          // wie aufgerufen ("eco", "money", "geld")
        List<String> path,     // ["admin", "balance", "add"]
        Arguments args,        // typisierte Argumente, Flags getrennt
        Origin origin          // PLAYER | CONSOLE | GUI | API | SCHEDULER
) { }
```

```java
public record CommandResult(
        Status status,               // OK | REJECTED | DRY_RUN
        String code,                 // "sent", "error.no-permission", ...
        Map<String, String> values,  // Platzhalter
        List<String> lines           // Zeilen für Listen-Ausgaben
) { }
```

**Wichtig:** `execute()` liefert ein Ergebnis, es sendet keine Nachrichten
selbst. Der Router formatiert. Damit kann derselbe Pfad von Chat, GUI,
Konsole und einer künftigen API genutzt werden, ohne Textduplikate.

### 10.3 Registrierung an genau einer Stelle

```java
// EconomyCommandRegistry#modules()
return List.of(
        new BalanceModule(economy),
        new PayModule(economy, network),
        new CreditModule(economy, creditService),
        new GuiModule(guiManager),
        new InfoModule(economy, settings),
        new AdminBalanceModule(economy),
        new AdminCreditModule(creditService),
        new AdminInterestModule(settings, interestScheduler),
        new AdminReloadModule(plugin),
        new AdminNetworkModule(network),
        new AdminAuditModule(audit),
        new AdminTestModule(plugin, economy)
);
```

- **Eine Liste, kein `plugin.yml`-Eintrag, keine Änderung an bestehenden
  Modulen.**
- Der Router baut aus den Modulen den Baum, prüft auf doppelte Pfade und
  bricht bei Kollisionen mit klarer Meldung ab — nicht mit stiller
  Überschreibung.
- `prepare()` läuft vor `execute()`, beides im Router; der Router sorgt für
  Tick-Sicherheit (Ergebnis wird auf dem Haupt-Thread formatiert) und dafür,
  dass `execute()` **einmal** läuft.

### 10.4 Checkliste: neuen Befehl anlegen

1. **Pfad wählen** — gegen Abschnitt 5 und 13 prüfen; keine Kollision.
2. **Modul implementieren** — `path()`, `permission()`, `prepare()`,
   `execute()`, `complete()`.
3. **Registrieren** — ein Eintrag in `EconomyCommandRegistry#modules()`.
4. **Permission dokumentieren** — `plugin.yml`-Kommentarblock und Abschnitt 7
   dieser Datei; nur bei neuen Capabilities ein neuer Knoten.
5. **Nachrichten** — `messages.yml` (Version in `config-version.yml` erhöhen) mit Keys nach
   Abschnitt 9.1; Standardtexte im Code als Fallback.
6. **Hilfe und Vorschläge** — Eintrag in `messages.yml` und ggf.
   `help.yml`, `suggestions` in `commands.yml` (optional), Kurzbefehl nur
   mit Begründung (Abschnitt 4.2). Eine Modul-Hilfe/Registry gibt es nicht.
7. **Audit und Test** — Audit wird heute nur für `ANY`-Fernbuchungen und
   nur bei SQLite/MySQL geschrieben; `--dry-run` ist nicht implementiert. Test
   über einen Dev-Build (`tests.enabled: true`) oder Unit-Test.

### 10.5 Durchgespieltes Beispiel: `/eco bank deposit`

Angenommen, ein Netzwerk möchte Einzahlungen auf ein Bankkonto:

1. **Pfad:** `bank.deposit` — `bank` ist in Abschnitt 13 reserviert, also
   frei. Keine Kollision mit `credit`, `pay`, `admin`.
2. **Modul:**

   ```java
   public final class BankDepositModule implements CommandModule {
       private final BankService bank;

       @Override public String id() { return "deposit"; }
       @Override public String path() { return "bank.deposit"; }
       @Override public String permission() { return "economysystem.command.bank"; }
       @Override public boolean playerOnly() { return true; }
       @Override public long cooldownMillis() { return 2_000L; }
       @Override public Set<String> auditActions() { return Set.of("bank.deposit"); }

       @Override
       public CompletableFuture<?> prepare(CommandContext context) {
           return bank.prepare(context.actorId());
       }

       @Override
       public CommandResult execute(CommandContext context) {
           BigDecimal amount = context.args().amount("betrag", false);
           return bank.deposit(context.actorId(), amount)
                   .toResult("command.bank.deposit")
                   .with("amount", amount.toPlainString());
       }

       @Override
       public List<String> complete(CommandContext context) {
           return context.args().index() == 1 ? List.of("10", "100", "1000") : List.of();
       }
   }
   ```
3. **Registrieren:** `new BankDepositModule(bankService)` in der Modulliste.
4. **Permission:** `economysystem.command.bank` (Gruppe, wie `credit`), in
   `plugin.yml` und Abschnitt 7 ergänzt.
5. **Nachrichten:** `command.bank.deposit.ok`, `command.bank.deposit.error.no-account`,
   `command.bank.deposit.error.insufficient-funds`.
6. **Hilfe/Vorschläge:** Eintrag „Bank – Einzahlung“ in `messages.yml`
   und ggf. `help.yml`, Vorschläge `bank.deposit.amount` in `commands.yml`.
   Eine Modul-Hilfe/Registry gibt es nicht.
7. **Audit:** Aktion `bank.deposit` mit `actor`, `amount`, `balance_after`
   ist Zielbild. Heute schreibt nur `remoteTransferAsync` Audit-Einträge;
   eine tx-id in der Antwort gibt es nur bei `ANY`-Fernbuchungen.

Kein Eintrag in `plugin.yml` für einen Befehl, keine Änderung an
`AbstractEconomyCommand`, keine Änderung an `pay` oder `credit`.

---

## 11. Netzwerkbetrieb

**Stand nach Schritt 5** (Details je Unterabschnitt):

| Punkt | Stand |
| --- | --- |
| 11.1 `network:`-Block, Scope-Kennung | ✅ gelesen, Scope-Kennung abgeleitet; **keine** Kontenpartitionierung; Scope-Migration ⏳ |
| 11.2 Transfer `ANY` (idempotent, eine Transaktion) | ✅; `PROXY` ohne Serverliste → `command.pay.error.no-route`; Rückbuchung nach Timeout ⏳ |
| 11.3 `accounts.revision` | ✅ vorhanden; Cache-Invalidierung/TTL und Ausbreitung per `PLUGIN_MESSAGE`/`REDIS` ⏳ |
| 11.4 Lease + `economy_interest_runs` | ✅ für den geplanten Lauf; `admin interest run` ⏳ |
| 11.5 Netzwerk-Reload | ⏳ |
| 11.6 Tabellen und Speicher-Schnittstelle, Audit der Fernbuchung | ✅; Audit für alle schreibenden Befehle und YAML-Audit ⏳ |
| 11.7 Rate-Limits | ✅ im Router erzwungen |
| 11.8 `READ_ONLY` im Router | ✅ |
| 11.9 Proxy/`--server` | ⏳ |

### 11.1 Server-Identität und Scopes

```yaml
network:
  # Eindeutiger Name dieses Servers in der Datenbank.
  id: "survival-1"

  # Gruppe für gruppenweite Wirtschaftsräume (z. B. "survival").
  group: "survival"

  # Wer teilt sich Guthaben?
  #   shared -> alle Server des Netzwerks (Standard, entspricht heute)
  #   group  -> nur Server derselben group
  #   server -> jeder Server für sich
  economy-scope: "shared"

  # FULL = lesen und schreiben, READ_ONLY = nur lesen (z. B. Lobby)
  mode: FULL

  # Wie erfahren Server von Änderungen anderer Server?
  #   NONE | TTL | PLUGIN_MESSAGE | REDIS
  invalidation: "TTL"
```

| `economy-scope` | Scope-Kennung | Wirkung heute |
| --- | --- | --- |
| `shared` | `global` | alle Server mit derselben Datenbank nutzen dieselben Konten |
| `group` | `group:<group>` | **keine** Kontenpartitionierung; nur Scope-Schlüssel |
| `server` | `server:<id>` | **keine** Kontenpartitionierung; nur Scope-Schlüssel |

Regeln:

- `economy-scope` ist **keine** Kontenpartitionierung. Alle Server mit
  derselben Datenbank lesen und schreiben dieselben `accounts`-Zeilen.
- Der Wert fließt nur in Lease, Transfer und Audit ein.
- `--scope` ist nicht implementiert.
- Die Umstellung `shared → group` ist **keine** Datenmigration, weil keine
  Scope-Spalte an den Konten existiert. Getrennte Guthaben brauchen
  getrennte Datenbanken.

### 11.2 Serverübergreifende Überweisung

```yaml
network:
  transfer:
    mode: "LOCAL"        # LOCAL | PROXY | ANY
    timeout-seconds: 30
```

| Modus | Verhalten von `/eco pay` |
| --- | --- |
| `LOCAL` | Ziel darf offline sein; gebucht wird gegen das gespeicherte Konto. Heutiges Verhalten ✅ |
| `PROXY` | Ziel darf auf einem anderen Server dieses Netzwerks online sein. **Nicht implementiert:** `no-route`. |
| `ANY` | Ziel darf offline sein; das Konto muss bereits existieren. |

**Aktueller Ablauf bei `ANY`:**

1. Das Zielkonto muss bereits existieren (`findAsync`); ein unbekannter
   Name führt zu `player-not-found`.
2. Der Betrag wird geparst und normalisiert.
3. `TransferData.create` erzeugt einen neuen Idempotenzschlüssel; ein
   erneuter Befehl erzeugt einen **neuen** Schlüssel.
4. `remoteTransferAsync` bucht synchron in der Datenbank (SQLite oder
   MySQL) und liefert `APPLIED`,
   `DUPLICATE`, `INSUFFICIENT_FUNDS`, `MAX_BALANCE`, `UNSUPPORTED` oder
   `FAILED`.
5. Bei `APPLIED` nennt die Nachricht die tx-id. Einen Statusbefehl gibt es
   nicht.

**Nicht implementiert:** Reservierung vor der Zustellung, Outbox/Retry,
Rückbuchung nach Timeout, `PROXY`-Zustellung und `/eco pay status`.
`timeout-seconds` hat keine Wirkung.

```
economy_transfers
  id                CHAR(36)      PK
  idempotency_key   CHAR(36)      UNIQUE
  scope             VARCHAR(32)
  from_uuid         CHAR(36)
  to_uuid           CHAR(36)
  amount            DECIMAL(30,8)
  source_server     VARCHAR(32)
  target_server     VARCHAR(32)   NULL
  state             ENUM(pending, applied, failed, expired)
  created_at        BIGINT
  applied_at        BIGINT        NULL
```

Regeln:

- Die Befehlssyntax bleibt in allen Modi identisch. Was sich ändert, ist
  die Fehlermeldung: `command.pay.error.no-route` (PROXY ohne Zielserver)
  bzw. `player-not-found` (LOCAL/ANY ohne Konto).
- Ohne `economysystem.pay.remote` ist ein Ziel auf einem anderen Server ein
  Fehler, keine stille Umleitung.
- `--dry-run` wird bei `pay` zwar geparst, aber **ignoriert**; es gibt keine
  Vorschau. Die Struktur erlaubt das Flag technisch, die Fachlogik wertet
  es nicht aus.

### 11.3 Cache-Kohärenz

Heute hält jeder Server seine Konten im Speicher und schreibt asynchron.
Im Netzwerk bedeutet das: Server A sieht Guthaben von Server B erst, wenn
B gespeichert hat — und niemand merkt, dass der Wert veraltet ist.

**Umsetzungsstand:** `network.invalidation` und
`network.cache-ttl-seconds` werden gelesen, aber **nicht angewendet**. Die
folgende Beschreibung ist Zielstruktur, nicht aktuelles Verhalten.

Zielstruktur:

- `accounts` erhält eine **Revision** (`revision BIGINT`, `updated_at`).
- Jede schreibende Aktion erhöht `revision`.
- Lesen: Cache gilt nur, wenn `jetzt − cache-zeit < network.cache-ttl-seconds`.
  Andernfalls wird aus dem Speicher gelesen (asynchron, nie auf dem
  Tick-Thread).
- Eigene, gerade geschriebene Werte werden nie aus einem fremden Cache
  gelesen (Read-through nach jeder Mutation).
- `network.invalidation` ist der Verbreitungsweg:
  `NONE` (nur TTL), `TTL` (kurze Lebensdauer, Standard),
  `PLUGIN_MESSAGE` (Bukkit-Messaging zwischen Servern desselben Proxys),
  `REDIS` (Pub/Sub, optional).
- **Kein Befehl verlässt sich auf Cache-Werte für Geldentscheidungen.**
  Prüfungen wie „reicht das Guthaben?“ laufen gegen den aktuellen Stand im
  Dienst, nicht gegen eine Anzeige aus dem Speicher.

### 11.4 Genau ein Zinszyklus je Scope

Ohne Regel würde jeder Server im Netzwerk denselben Zinszyklus ausführen —
das Geld würde vervielfacht.

```yaml
interest:
  executor: "auto"     # auto | <server-id>
```

- `auto`: Der Zyklus läuft nur auf dem Server, der die **Lease** für den
  Scope hält.
- Lease-Tabelle `economy_locks` mit Zeile pro Scope (`scope`, `holder`,
  `leased_until`). Verlängert sich während des Laufs; nach einem Absturz
  läuft sie ab und der nächste Server übernimmt.
- Der Zyklus selbst ist idempotent: `economy_interest_runs` mit eindeutigem
  Schlüssel `(scope, cycle_key)`. Ein doppelt gestarteter Lauf schreibt
  nichts.
- `/eco admin interest run` ist **nicht implementiert**. Es gibt keinen
  manuellen Lauf, keine Lease-Konfliktmeldung und kein `--dry-run`.
- Der automatische Zyklus nutzt die Lease und `interest_runs`; das ist der
  einzige implementierte Weg.

### 11.5 Reload

| Aufruf | Wirkung |
| --- | --- |
| `/eco admin reload` ✅ | lädt `settings.yml`, `messages.yml`, `help.yml`, `gui.yml`, `commands.yml` auf **diesem** Server |
| `/eco admin reload network --confirm` ⏳ | sendet einen Reload-Auftrag an alle Server des Scopes |

- Ein Wechsel der Speicherart (`storage.type`) bleibt ein Neustart-Fall und
  wird weiterhin nur gewarnt, nicht angewendet.
- Der Netzwerk-Reload verlangt `admin.reload` **und** `admin.network`; die
  Antwort listet bestätigende und fehlende Server.
- Ein Reload ändert **nie** die Befehlssyntax. Neue Pfade werden erst mit
  einer neuen Plugin-Version wirksam; `commands.yml` ändert nur
  Schreibweisen.

### 11.6 Audit und Idempotenz

```
economy_audit
  id            BIGINT AUTO_INCREMENT  PK
  tx_id         CHAR(36)               UNIQUE
  occurred_at   BIGINT
  server_id     VARCHAR(32)
  scope         VARCHAR(32)
  actor_uuid    CHAR(36)   NULL
  actor_name    VARCHAR(64) NULL
  origin        ENUM(PLAYER, CONSOLE, GUI, API, SCHEDULER)
  action        VARCHAR(48)            -- "pay", "admin.balance.set"
  target_uuid   CHAR(36)   NULL
  target_name   VARCHAR(64) NULL
  amount        DECIMAL(30,8) NULL
  balance_after DECIMAL(30,8) NULL
  reason        VARCHAR(160) NULL
  result        ENUM(APPLIED, REJECTED, DRY_RUN)
```

- **Nur `remoteTransferAsync`** erzeugt heute einen Eintrag, und nur bei
  `storage.type: SQLITE` oder `MYSQL`. Lokale `/pay`-, Admin-, Zins- und
  Kreditbuchungen schreiben kein Audit.
- SQLite bildet dasselbe Schema mit `TEXT`-Spalten ab (Beträge als
  BigDecimal-Zeichenkette) und führt es in der eigenen Datei.
- Abgelehnte Aktionen werden nicht protokolliert; es gibt keinen
  `REJECTED`-Pfad für Admin-Buchungen.
- Einen YAML-Audit gibt es **nicht**; `audit/<yyyy-MM>.yml` ist nicht
  implementiert.
- `/eco admin audit` ist nicht implementiert.
- Die Audit-Daten sind **keine** Quelle für Kontostände — sie sind Spur,
  nicht Wahrheit.

### 11.7 Rate-Limits

```yaml
network:
  limits:
    pay:
      count: 10
      seconds: 60
      cooldown-seconds: 3
    credit.take:
      count: 3
      seconds: 300
      cooldown-seconds: 10
    admin.balance:
      count: 60
      seconds: 60
```

- Limits gelten **pro Spieler und Pfad** (Gruppe: `pay`, `credit.take`,
  `admin.balance.*` teilt sich ein Fenster); Administratoren mit
  `bypass.rate-limit` sind ausgenommen, `bypass.cooldown` hebt den
  Mindestabstand `cooldown-seconds` auf.
- `count: 0` hebt die Mengenbegrenzung auf, `cooldown-seconds: 0` den
  Mindestabstand; `seconds` ist die Fensterlänge. Beide Prüfungen laufen
  vor der Fachlogik, und eine Ablehnung verbraucht kein Kontingent.
- Zusätzlich gilt eine Empfangsgrenze pro Zielkonto (⏳), damit viele
  Kleinstüberweisungen kein Guthaben an einem Konto vorbeischleusen.
- Ablehnung ist ein normaler Fehler mit `error.rate-limited` und nennt die
  verbleibende Wartezeit — keine stille Verzögerung.
- Limits sind zentral konfiguriert, nicht pro Modul hartcodiert.

### 11.8 Betriebsmodi und Befehlsmatrix

| Pfad | `FULL` | `READ_ONLY` |
| --- | --- | --- |
| `balance`, `balance.top` | ✅ | ✅ |
| `credit.info`, `credit.list` | ✅ | ✅ |
| `gui`, `info`, `help` | ✅ | ✅ |
| `pay` | ✅ | `error.read-only` |
| `credit.take`, `credit.repay` | ✅ | `error.read-only` |
| `admin.balance.*` | ✅ | `error.read-only` |
| `admin.credit.forgive` ⏳ | nicht implementiert | nicht implementiert |
| `admin.interest.show` | ✅ | ✅ |
| `admin.interest.set` | ✅ | `error.read-only` |
| `admin.reload` (lokal) | ✅ | ✅ |
| `admin.reload network` ⏳ | nicht implementiert | nicht implementiert |
| `admin.network.*`, `admin.audit` ⏳ | nicht implementiert | laut Positivliste erlaubt, aber ohne Funktion |

`READ_ONLY` ist für Lobby-, Proxy- und Anzeige-Server gedacht: Sie zeigen
Guthaben an, schreiben aber nie. Die Prüfung sitzt im Router **vor** der
Fachlogik, damit kein Modul sie vergessen kann.

### 11.9 Proxy und Konsole

- Das Plugin läuft auf dem Paper-Backend. `/eco` wird **nicht** als
  Proxy-Befehl (Velocity/BungeeCord) registriert; ein Proxy-Befehl würde
  die Permission-Prüfung des Backends umgehen.
- `--server <id>` wählt den Zielserver für Netzwerkaktionen, es wechselt
  nicht den Ausführungsserver.
- Die Konsole darf alle Pfade außer `playerOnly`-Pfaden ausführen; bei
  `playerOnly` antwortet sie mit `error.player-only`.
- Ein `CommandSender`, der kein `Player` ist, hat keine UUID. Module dürfen
  deshalb nie `sender.getUniqueId()` aufrufen, sondern `context.actorId()`
  prüfen.

---

## 12. Kompatibilität und Deprecation

### 12.1 Was unverändert bleibt

Alle in `README.md` dokumentierten Aufrufe bleiben gültig:

| Aufruf heute | bleibt gültig als |
| --- | --- |
| `/money`, `/money <Spieler>`, `/money top [Seite]` | Kurzbefehl auf `balance` |
| `/pay <Spieler> <Betrag>` | Kurzbefehl auf `pay` |
| `/kredit add|bezahlen|info` | Kurzbefehl auf `credit` (deutsche Wörter über `subcommand-words`) |
| `/economysystem help` | Alias auf `help` |
| `/economysystem-gui [Menü]` | Kurzbefehl auf `gui` |
| `/economysystem-admin help|info|reload|money|zinsen|tests` | Kurzbefehl auf `admin` |
| Aliase aus `commands.yml` | unverändert |

> **Altform und Bestätigung:** `/economysystem-admin money <konto> set|reset`
> wird auf `/eco admin balance set|reset` übersetzt und verlangt seit dem
> ROT-1-Fix ebenfalls `--confirm`; ohne Flag wird nichts verändert.

### 12.2 Umbenennungspolitik

- **Pfade werden nie umbenannt** (R2). Wird ein Pfad falsch gewählt, bleibt
  er bestehen und bekommt einen neuen, besseren Pfad daneben; der alte wird
  als *veraltet* markiert und bleibt mindestens **zwei Hauptversionen**
  erreichbar.
- **Deutsche Schreibweisen** sind Aliase und dürfen jederzeit ergänzt
  werden; entfernt werden sie nur mit Ankündigung in den Release-Notes.
- **Permissions:** alte und neue Knoten werden parallel akzeptiert
  (Abschnitt 7.4). Ein Entfernen ist ein Breaking Change und wird in der
  `README.md` angekündigt.
- **Message-Keys:** alte Keys bleiben als Fallback lesbar; neue Keys haben
  Vorrang. Kein Key wird umbenannt, ohne dass der alte weiter funktioniert.

### 12.3 Was Serverbetreiber tun müssen

| Situation | Aufwand |
| --- | --- |
| Bestehende Installation, Einzelserver | keiner — Kurzbefehle, Aliase und Berechtigungen funktionieren weiter |
| Umstieg auf `/eco` | keiner — optional, beide Wurzeln funktionieren |
| Umstieg auf neue Permissions | optional, `economysystem.*` bleibt gültig |
| Netzwerkbetrieb | `network:`-Block in `settings.yml`; **keine** Scope-Datenmigration (Scope teilt keine Konten). Proxy-Messaging/Redis sind nicht implementiert. |

### 12.4 Was Entwickler tun müssen

| Situation | Aufwand |
| --- | --- |
| Neuer Befehl | Modul + ein Eintrag in der Registry (Abschnitt 10.4) |
| Neue Untergruppe | ein Gruppenmodul mit `register(CommandTree)` |
| Neue Permission | nur bei neuer Capability; sonst aus dem Pfad abgeleitet |
| Neuer Message-Key | `messages.yml` + Version in `config-version.yml` erhöhen |

---

## 13. Reservierte Pfade

Diese Segmente sind für künftige Module reserviert und dürfen nicht für
andere Zwecke belegt werden. Eine Reservierung ist keine Ankündigung, dass
die Funktion kommt — sie verhindert nur, dass Namen später kollidieren.

| Pfad | Vorgesehene Bedeutung | Status |
| --- | --- | --- |
| `bank` | Einzahlen, Auszahlen, Kontoauszug (Beispiel in 10.5) | reserviert |
| `shop` | Plugingestützte Shops, Preise pro Item | reserviert |
| `tax` | Steuern und Abgaben auf Überweisungen | reserviert |
| `auction` | Auktionshaus | reserviert |
| `market` | Spielerhandel | reserviert |
| `stats` | Wirtschaftskennzahlen (Geldmenge, Umlauf) | reserviert |
| `link` | Verknüpfung von Netzwerk-Identitäten | reserviert |

Nicht reserviert und damit **nicht** zulässig sind Pfade, die nur eine
Schreibweise eines bestehenden Pfades sind (`geld`, `zinsen`, `kontostand`)
sowie Pfade mit Großbuchstaben, Umlauten oder Leerzeichen.

---

## 14. Definition of Done

Ein neuer oder geänderter Befehl ist fertig, wenn alle Punkte zutreffen:

**Struktur**

- [ ] Pfad folgt Abschnitt 5 und kollidiert mit keinem reservierten Pfad.
- [ ] Segmente sind ASCII, kleingeschrieben, aus der Verb-Liste (6.2) bzw.
      einer Gruppe.
- [ ] Keine `instanceof`-Prüfung, keine Sonderbehandlung in der Basisklasse.
- [ ] Keine neue Zeile in `plugin.yml` für einen Unterbefehl.

**Rechte**

- [ ] Permission aus dem Pfad abgeleitet, in `plugin.yml` und Abschnitt 7
      dokumentiert.
- [ ] `admin`-Wildcard und Unterknoten verhalten sich wie in 7.2.
- [ ] Tab-Vervollständigung zeigt nur, was erlaubt ist.

**Verhalten**

- [ ] Prüfreihenfolge aus 6.7 eingehalten.
- [ ] `usage` nennt die vollständige kanonische Syntax.
- [ ] `playerOnly` korrekt gesetzt, Konsole getestet.
- [ ] Cooldown/Rate-Limit gesetzt, wo Geld fließt.
- [ ] `--dry-run` ist derzeit **nicht implementiert** (⏳); nicht als
      vorhanden dokumentieren.
- [ ] `--confirm` wird bei `set` und `reset` verlangt. `forgive`, `run` und
      `reload network` sind nicht implementiert (⏳).
- [ ] `READ_ONLY`-Modus berücksichtigt.

**Daten**

- [ ] Änderung läuft ausschließlich über einen Dienst (R10).
- [ ] Atomar: Konto und abhängige Datensätze in einem Aufruf.
- [ ] Idempotenz über `idempotency_key` gilt heute nur für `ANY`-Transfers.
- [ ] Audit wird heute nur für `remoteTransferAsync` und nur bei
      SQLite/MySQL geschrieben; andere Pfade haben keinen Audit-Eintrag.
- [ ] Keine Geldentscheidung auf Basis eines Cache-Werts.

**Dokumentation**

- [ ] Message-Keys nach 9.1, Version in `config-version.yml` erhöht.
- [ ] `/eco help`-Listen in `messages.yml` und ggf. `help.yml` ergänzt;
      eine Modul-/Registry-Hilfe gibt es nicht.
- [ ] `README.md`-Befehlstabelle ergänzt (oder auf diese Datei verwiesen).
- [ ] Änderung in `Commands.md` eingetragen, falls die Struktur betroffen ist.

**Test**

- [ ] Dev-Build mit `tests.enabled: true` oder Unit-Test deckt den
      Erfolgsfall und mindestens zwei Fehlerfälle ab.
- [ ] Bei `ANY`: derselbe Transfer-Datensatz mit gleichem
      `idempotency_key` ändert nur einmal. Ein neuer Befehl erzeugt einen
      neuen Schlüssel und ist **kein** Retry-Schutz.
- [ ] Auf einem zweiten Server geprüft, wenn der Pfad Netzwerkbezug hat.

---

## 15. Umsetzungsplan

Die Abschnitte 1–14 beschreiben das Ziel. Dieses Kapitel beschreibt den Weg
dorthin in fünf Schritten. Jeder Schritt ist für sich lauffähig, testbar und
abnehmbar. Die Schritte 1–4 sind reine Einzelserver-Arbeit, Schritt 5 ist der
Netzwerkausbau (⏳) und setzt die Schritte 1–4 voraus. Ein Schritt wird nicht
übersprungen.

### 15.1 Übersicht

| Schritt | Ergebnis | Hauptdateien | abhängig von |
| --- | --- | --- | --- |
| 1 | Modul-Vertrag und Baum-Router; alle heutigen Befehle laufen über den Router | `command/CommandModule.java`, `CommandContext.java`, `CommandResult.java`, `CommandTree.java`, `CommandRegistry.java`, `AbstractEconomyCommand.java` | — |
| 2 | Grammatik, Prüfreihenfolge, Permissions, Ausgabe | `command/CommandParser.java`, `command/CommandRegistry.java`, `messages.yml`, `plugin.yml` | 1 |
| 3 | `commands.yml` v2 mit Migration | `config/CommandsConfig.java`, `commands.yml` | 2 |
| 4 | Fachmodule, Altlasten entfernt | alle `command/*Command.java`, `plugin.yml`, `README.md` | 1–3 |
| 5 | Netzwerkbetrieb (teilweise ✅, siehe 11 und 15.6) | `storage/MySqlStorage.java`, `economy/EconomyService.java`, `settings.yml` | 1–4 |

Nach jedem Schritt wird Abschnitt 14 für die in diesem Schritt betroffenen
Pfade geprüft, nicht erst am Ende.

### 15.2 Schritt 1 — Fundament: Modul-Vertrag und Baum-Router

Ziel: die Befehlsschicht so umbauen, dass ein neuer Befehl ein Modul ist und
keine Änderung an einer Basisklasse braucht.

- Die drei Typen aus Abschnitt 10 anlegen (`CommandModule`,
  `CommandContext`, `CommandResult`) und die Signatur aus 10.1 übernehmen.
- `AbstractEconomyCommand` von der `instanceof`-Kette
  (`MoneyCommand`/`PayCommand`/`KreditCommand`/`AdminCommand`, Befund NEU-3
  im `SICHERHEITSAUDIT.md`) auf den Hook `prepare(context)` umstellen.
  Danach enthält die Basisklasse keinen Klassennamen mehr.
- `CommandTree` als Baum aus Knoten (Segment, Modul, Kinder, `playerOnly`,
  `permission`, `usage`); der Router in `CommandRegistry` löst
  `/eco <pfad…>` dagegen auf.
- Unterbefehle sind Knoten, keine `plugin.yml`-Einträge (Regel R4).
- Brücke für die sechs heutigen Befehle, damit nichts bricht:

| heute | Zielpfad |
| --- | --- |
| `/money [Spieler]` | `balance` |
| `/pay <Spieler> <Betrag>` | `pay` |
| `/kredit …` | `credit` |
| `/economysystem-gui [Menü]` | `gui` |
| `/economysystem-admin …` | `admin` |
| `/economysystem help` | `help` |

**Fertig, wenn:** `/eco balance` und `/money` dasselbe Ergebnis liefern,
`/eco pay` und `/pay` dasselbe, `plugin.yml` unverändert ist und ein
Testmodul ohne Änderung an `AbstractEconomyCommand` registriert werden kann.

### 15.3 Schritt 2 — Grammatik, Rechte, Ausgabe

Ziel: eine Stelle im Router entscheidet über Syntax, Rechte, Flags und
Ausgabe — nicht jedes Modul für sich.

- Parser für Pfad, Argumente und Flags (`--reason`, `--silent`,
  `--dry-run`, `--confirm`, `--player`, `--action`, `--server`, `--scope`)
  nach Abschnitt 6.4; unbekannter Pfad → `error.unknown-subcommand` mit
  Vorschlag aus den Nachbarschaftssegmenten.
- Die Prüfreihenfolge aus 6.7 genau einmal implementieren
  (Pfad → `playerOnly` → `READ_ONLY` → Permission → Syntax/Flags →
  Bestätigung → Rate-Limit/Cooldown → Ausführung), nicht in den Modulen.
- Permission `economysystem.command.<pfad>` ableiten; alt und neu werden
  mit ODER akzeptiert (7.4). `economysystem.admin` bleibt Wildcard.
- `messages.yml` um die neuen Keys ergänzen (alte bleiben als Fallback,
  12.2). `/eco help` nutzt die Listen `help`/`help-admin` aus
  `messages.yml`; `/eco admin help` nutzt `help.yml`. Eine Registry-Hilfe
  ist nicht implementiert. Tab-Vervollständigung permission-gefiltert.

**Fertig, wenn:** ein Dummy-Modul ohne Sonderbehandlung korrekt antwortet,
`usage` die vollständige kanonische Syntax nennt, Tab nichts Unerlaubtes
zeigt und ein Spieler ohne Permission nur `error.no-permission` sieht.

### 15.4 Schritt 3 — `commands.yml` v2

- Schema aus Abschnitt 8: `subcommand-words` (deutsche Wörter wie
  `bezahlen`, `geld`, `zinsen`), `shortcuts`, `suggestions`;
  Version 2 in `config-version.yml`.
- Migration v1 → v2 beim Laden: v1 kennt nur `commands.<name>.aliases`,
  diese werden als `aliases` übernommen, dazu ein Log-Hinweis auf die neue
  Version. Kein Serverbetreiber muss eine Datei anfassen.
- Validierung beim Start: Zeigt ein `shortcuts`- oder
  `subcommand-words`-Eintrag auf einen unbekannten Pfad, wird das geloggt
  (nicht still ignoriert); der Eintrag bleibt wirkungslos.

**Fertig, wenn:** eine unveränderte v1-Datei weiter funktioniert,
`/kredit bezahlen` und `/eco credit repay` dasselbe Modul treffen und ein
absichtlich falscher Pfad in der Konfiguration eine Warnung erzeugt.

### 15.5 Schritt 4 — Fachmodule und Altlasten

Ziel: jeder Pfad aus Abschnitt 5 ist ein eigenes Modul, kein `switch` und
kein Reflective-Loading mehr.

- Module für `balance` (+ `top`), `pay` (ohne `status`),
  `credit.{take,repay,info,list}`, `gui`, `info`, `help` und
  `admin.{balance,credit,interest,reload,test}`. `pay.status`,
  `admin.audit`, `admin.network` und `admin.interest.run` sind nicht
  implementiert.
- `switch` mit den hartcodierten deutschen Synonymen (`geld`, `status`,
  `zinsen`, `test`) in `EconomySystemAdminCommand` auflösen — diese Wörter
  gehören ab jetzt in `subcommand-words` (Schritt 3).
- Reflective-Loading des Interesse-Tests (`InterestCycleTestCommand`)
  durch ein reguläres Modul ersetzen.
- `plugin.yml` um die neuen Knoten ergänzen, alte bleiben; `README.md`
  verweist für die Struktur auf diese Datei.

**Fertig, wenn:** Abschnitt 14 für jeden migrierten Pfad abhakbar ist,
der Build läuft und ein Lauf mit `tests.enabled: true` grün ist.

### 15.6 Schritt 5 — Netzwerkbetrieb (teilweise ✅)

Dieser Schritt ist der einzige, der die Einzelserver-Grenze verlässt. Der
Stand ist in der Tabelle am Anfang von Abschnitt 11 festgehalten; offen sind
insbesondere `PROXY`-Zustellung, Cache-Ausbreitung, Audit für alle
schreibenden Befehle, `admin interest run` und `admin reload network`.
Das Einzelserververhalten bleibt mit den Standardwerten
(`shared`/`FULL`/`LOCAL`) unverändert.

- `network:`-Block in `settings.yml` (id, group, economy-scope, mode,
  invalidation, transfer, limits) nach 11.2; `READ_ONLY` wird im Router
  durchgesetzt, nicht in Modulen.
- Tabellen und Spalten nach 11.6: `economy_transfers` (Outbox,
  `idempotency_key`), `economy_audit` (`tx_id` UNIQUE, `origin`,
  `result`), `economy_locks` (Lease pro Scope), `economy_interest_runs`
  (`(scope, cycle_key)` UNIQUE), `accounts.revision`; die DDL läuft als
  Migration im `MySqlStorage`.
- `ANY`-Transfer ist verfügbar, wenn `network.transfer.mode: ANY` und
  `economysystem.pay.remote` gesetzt sind. `PROXY` und
  Cache-Invalidierung sind **nicht** implementiert.

**Fertig, wenn:** das Einzelserververhalten identisch zu Schritt 4 ist,
zwei Server mit derselben Datenbank denselben Kontostand sehen (der Scope
selbst teilt keine Konten), ein Transfer mit gleichem `idempotency_key`
nur einmal bucht und die Lease einen parallelen Zinslauf verhindert.

### 15.7 Regeln für die Abarbeitung

- Ein Schritt wird erst begonnen, wenn der vorige abgeschlossen ist.
- Nach jedem Schritt: Abschnitt 14 prüfen, nicht erst vor dem Release.
- Ändert die Umsetzung die Struktur (Pfad, Permission, Message-Key), wird
  diese Datei im selben Commit nachgezogen — sie ist die maßgebliche Quelle
  für die Struktur.
- ⏳-Markierungen werden erst entfernt, wenn der Code die Funktion hat.
- Die Kompatibilitätszusage aus Abschnitt 12 gilt in jedem Schritt: kein
  bestehender Aufruf und keine bestehende Permission darf zwischen zwei
  Schritten brechen.
