# FoldMail – stav projektu

Prémiový Android email klient (Kotlin, Compose, Material 3) pre Galaxy Z Fold 7.

## Skutočný stav (bez prikrášlenia)

Prostredie, v ktorom vznikol modul `app`, malo sieť povolenú len pre apt/npm/PyPI a GitHub.
Hosty `dl.google.com`, Google Maven, Maven Central, Gradle Plugin Portal a `services.gradle.org`
boli blokované (HTTP 403 z proxy). Preto:

- **Android SDK sa nedal nainštalovať**, `./gradlew` sa nedal spustiť (nemá odkiaľ stiahnuť Gradle ani pluginy).
- **`assembleDebug` sa nespustil, APK nevzniklo.**
- **Overené (mimo Gradle):** `core-domain` (17 testov) a čistá Kotlin vrstva `app`
  (`MailRepository`, `DemoData`, `Presentation`, 5 testov v `AppLogicTest`) som skompiloval
  kompilátorom Kotlin 2.0.21 z distribúcie Gradle a spustil v JUnit 4.13.2: všetko prešlo.
- **Neoverené kompiláciou:** Compose UI v `app/.../ui`, `MainActivity`, `MailViewModel`,
  `core-designsystem` a všetky `build.gradle.kts`. UI súbory prešli len kontrolou syntaxe.
- Verzie v `gradle/libs.versions.toml` sú stále odhady bez prístupu na sieť. Pred prvým buildom ich
  zvýšte na aktuálne stabilné (a AGP musí sedieť s Gradle 8.14.3 z wrappera).

## Hotové

| Modul | Obsah |
|---|---|
| `core-domain` | `PersonConversations` (chat na osobu / skupinu), `ThreadGrouper` (vlákna podľa hlavičiek), `QuoteSplitter` (zbalenie citácií) + 17 testov |
| `core-designsystem` | Svetlá/tmavá paleta, zaoblené tvary, `FoldMailTheme` |
| `app` | Adaptívny shell podľa šírky okna (prah 600 dp): **kompaktný** = spodná lišta *Chaty · Kontakty · Nový (zvýraznený) · Kalendár*, jeden panel naraz; **rozšírený** (rozložený Fold) = `NavigationRail` + zoznam chatov + otvorený chat vedľa seba. Zoznam chatov, chat s bublinami, predelmi predmetu a zbalenými citáciami, rýchla odpoveď, formulár nového emailu, kontakty z poštovej schránky, kalendár ako placeholder. Dáta: `InMemoryMailRepository` s ukážkovými správami (nič sa neposiela po sieti). |

## Zatiaľ nezačaté

`core-database` (Room), `data-gmail`, `data-imap`, `data-smtp`, `data-pop3`, prílohy, autentifikácia
(Keystore, OAuth), synchronizácia (WorkManager), nastavenia, kalendár (funkcia), notifikácie, HTML renderer,
detekcia pozície zloženia (androidx.window / tabletop) – layout sa zatiaľ riadi len šírkou okna.

## Ako to dokončiť u vás

1. Otvorte priečinok v Android Studiu (aktuálna stabilná verzia), nechajte Gradle sync a opravte verzie.
2. Overenie jadra: `./gradlew :core-domain:test` a `./gradlew :app:testDebugUnitTest`
3. Build: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/`

## Google účet (Gmail) – nastavenie

Aplikácia sa prihlasuje cez Google Play services (Authorization API), preto netreba client secret,
len OAuth klienta typu **Android**:

1. console.cloud.google.com → nový projekt → *APIs & Services → Library* → zapnúť **Gmail API**.
2. *OAuth consent screen* (Google Auth platform) → typ **External**, stav **Testing**, pridať svoj Gmail
   medzi **Test users**. (Scope `gmail.modify` je „restricted“; v režime Testing stačí.)
3. *Credentials → Create credentials → OAuth client ID* → typ **Android**:
   - Package name: `com.foldmail.app`
   - SHA-1: `9E:59:CD:56:DC:42:B4:92:A9:59:12:AD:BF:6C:7F:EC:F8:32:EC:5B`
     (fixný debug kľúč `app/debug.keystore`, takže SHA-1 je pri každom builde rovnaký)
4. V aplikácii: potiahnuť doprava → **Pridať Google účet**.

Debug kľúč je verejný v repozitári, použite ho len na testovanie. Vlastný kľúč na vydanie vygenerujte zvlášť.
Aplikácia stiahne posledných 40 správ z Doručených a 40 z Odoslaných, odosiela a označuje prečítané cez Gmail API.
Archív/Kôš/Pripnúť… zatiaľ ostávajú len lokálne (nemenia Gmail).
