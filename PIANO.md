# JATZ — piano di costruzione

App Android standalone che ogni giorno, entro le 8:00, propone **5 dischi**: 3 dal 1968–1983 e
2 dal 2018–anno corrente, tutti scelti dal motore del **profilo jazz** di digmore. I dischi si
accumulano in una libreria permanente; le tracce si possono mettere in **LOVED TRACKS**.
UI dark neumorfica sul modello del mockup fornito (senza i pulsanti download).

Concept portante: **non un bacino infinito, ma una razione quotidiana.**

---

## 0. Dove vive questo progetto

`E:\jatz\` — **fuori da `E:\sre\`**, con un proprio repo git.

Motivo: GitHub Actions clona l'intero repo a ogni run. Dentro `sre/` trascinerebbe ~70 progetti
e i venv Python a ogni esecuzione notturna. Stessa ragione per cui `samehand/`, `omi-companion/`
e `dillabrain/` sono già stati spostati fuori.

Struttura a due metà nello stesso repo:

```
E:\jatz\
  PIANO.md
  engine/            # Python — gira SOLO su GitHub Actions, mai sul telefono, mai sul PC
    profiles/jazz.npy, jazz.json      # copiati da digmore/engine/prebuilt_profiles/
    engine_libs/                      # sottoinsieme vendorizzato da digmore
    discogs_jatz.py                   # ricerca Discogs a due finestre temporali
    preview.py                        # snippet 30s da iTunes/Deezer (NON da YouTube)
    curate.py                         # orchestratore: produce il drop del giorno
  drops/                              # output del job notturno, servito da GitHub Pages
    index.json
    2026-08-28.json ...
  app/                                # Android — Kotlin + Jetpack Compose
  .github/workflows/
    daily.yml                         # cron notturno → nuovo drop
    build-apk.yml                     # compila l'APK e lo pubblica in Releases
```

Il repo `jatz-daily` è **pubblico** (serve per GitHub Pages e per i minuti Actions illimitati).
Contiene solo metadati di dischi — nessun segreto: `DISCOGS_TOKEN` sta nei GitHub Secrets.

---

## 1. Architettura in tre pezzi

```
┌─ NOTTE (GitHub Actions, 03:30 UTC) ────────────────────────────┐
│  Discogs → candidati (2 finestre: 1968-83 e 2018-oggi)         │
│  iTunes/Deezer → snippet 30s di 1-2 tracce per disco           │
│  CLAP → embedding → coseno vs jazz.npy → ranking               │
│  → drops/YYYY-MM-DD.json  (5 dischi + tracklist Discogs)       │
└────────────────────────────────────────────────────────────────┘
                              │ GitHub Pages (JSON statico)
                              ▼
┌─ MATTINA (telefono, WorkManager ~07:00 locale) ────────────────┐
│  scarica il drop → Room DB → notifica "5 nuovi dischi"         │
│  aggiorna il widget                                            │
└────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─ ASCOLTO (on-device, nessun server) ───────────────────────────┐
│  NewPipeExtractor risolve traccia → URL audio YouTube          │
│  Media3 ExoPlayer + MediaSessionService                        │
│  → background, schermo spento, lockscreen, Bluetooth, widget   │
└────────────────────────────────────────────────────────────────┘
```

Il telefono **non parla mai col tuo PC**. Il PC serve solo a scrivere il codice.

### Le due decisioni non ovvie

**(a) Il job notturno non tocca YouTube.**
digmore scarica lo snippet per CLAP da YouTube via yt-dlp. Dagli IP dei datacenter GitHub questo
fallisce quasi sempre con *"Sign in to confirm you're not a bot"*, ed è esattamente il problema dei
cookie che hai detto di non volere. Soluzione: lo snippet per lo scoring viene dalla
**iTunes Search API** (gratis, nessuna chiave, anteprime M4A da 30s) con **Deezer API** come
fallback. YouTube entra in gioco solo **sul telefono**, dove NewPipeExtractor risolve lo stream
senza chiave API e senza cookie, da IP residenziale.

**(b) L'APK si compila in CI, non sul tuo PC.**
Sul PC non c'è Android SDK né Gradle, e il JDK installato è il 25 (AGP vuole 17 o 21). Invece di
installare ~8GB di toolchain, un secondo workflow compila l'APK firmato e lo pubblica in GitHub
Releases: tu lo scarichi dal telefono e lo installi. Nessun setup locale.

---

## 2. Il motore jazz — cosa cambia rispetto a digmore

Riuso diretto: `jazz.npy` (vettore CLAP 512-dim da 9 reference: Lonnie Liston Smith, Wayne Shorter,
Stanley Clarke, Joe Zawinul, Nancy Wilson…), `clap_model.py`, `features.py`, `kvcache.py`,
la logica di filtro Discogs.

Cambiamenti necessari:

| Aspetto | digmore | JATZ |
|---|---|---|
| Unità di output | 30 **tracce** sparse | 5 **dischi** con tracklist |
| Finestra anni | 1969–1983 fissa | 1968–1983 **e** 2018–`datetime.now().year` |
| Snippet per CLAP | yt-dlp/YouTube | iTunes → Deezer → (yt-dlp solo come 3° tentativo) |
| Trigger | utente preme "genera" | cron notturno |
| Scoring | per traccia | per traccia, poi **aggregato a disco** |

**Aggregazione a disco.** Un disco viene giudicato su 2–3 tracce campionate (non tutte: risparmia
tempo e copertura). Punteggio = media dei coseni CLAP, con penalità se meno di 2 tracce sono
risultate scoreabili. Un album entra nel drop solo se `score ≥ soglia` (parto da 0.76, da tarare —
digmore usa 0.78 per traccia singola, la media di album è naturalmente più bassa).

**Copertura anteprime.** I dischi davvero oscuri del 1968–83 potrebbero non essere su
iTunes/Deezer. Se nessuna traccia è scoreabile, il disco **non viene scartato**: passa a uno
scoring di ripiego basato su CLAP zero-shot testuale (i `GENRE_PROMPTS["jazz"]` già presenti nel
codice) + stili Discogs, e viene marcato `confidence: "low"`. Se dopo la prima settimana di run
vedo che la copertura affonda sotto ~60%, ricalibro (vedi §7, rischio R1).

**Il lato moderno ha bisogno di regole sue.** Il jazz 2018+ (Yussef Dayes, Nubya Garcia,
BADBADNOTGOOD, Makaya McCraven, Sons of Kemet…) su Discogs è taggato diversamente
(Contemporary Jazz, Nu Jazz, Broken Beat) e ha pochi voti community. Quindi:
- `GENRE_MAP` separata per la finestra moderna
- filtro rating rilassato (poche uscite recenti hanno 20+ voti)
- filtro "troppo famoso" di Last.fm **disattivato** sul lato moderno, altrimenti taglia via
  esattamente i dischi buoni degli ultimi anni

**Anti-ripetizione.** `seen_releases.json` e `seen_artists.json` versionati nel repo: un disco non
si ripete mai, e un artista non torna prima di 30 giorni (evita che il drop diventi
"Lonnie Liston Smith ogni martedì").

---

## 3. L'app Android

**Stack.** Kotlin, Jetpack Compose (Material3 come base, tema custom), Room (persistenza),
Media3 ExoPlayer + MediaSessionService (audio), NewPipeExtractor (risoluzione YouTube),
WorkManager (fetch giornaliero), Glance (widget), Coil (copertine), Ktor/OkHttp + kotlinx.serialization.
`minSdk 26`, `targetSdk 35`.

**Schema Room.**
```
Drop(date PK, fetchedAt)
Album(id PK, discogsId, title, artist, year, era[VINTAGE|MODERN], label, country,
      coverUrl, score, confidence, dropDate FK, addedAt)
Track(id PK, albumId FK, position, title, durationSec, videoId?, resolvedAt?)
Loved(trackId PK, lovedAt)
PlayState(trackId, positionMs, lastPlayedAt)
```
`videoId` è nullable e si popola pigramente: alla prima apertura del disco l'app risolve la
tracklist in background, poi la memorizza per sempre.

**Schermate** (dal mockup):
1. **OGGI** — i 5 dischi del giorno, copertine grandi, marcati vintage/moderno.
2. **DISCO** — mockup di destra: copertina centrale, tracklist numerata, traccia corrente
   evidenziata, cuore per traccia. *Senza i pulsanti download* (resta il cuore, il posto del
   download rimane vuoto o lo rimuovo del tutto — vedi domanda D2).
3. **PLAYER** — mockup di sinistra: copertina, titolo/artista, seek bar con tempi,
   cluster circolare shuffle / prev / next / play-pause.
4. **LIBRERIA** — tutti i dischi accumulati, raggruppati per data del drop.
5. **LOVED TRACKS** — playlist delle tracce col cuore, riproducibile di fila.
6. **Mini-player** — barra in basso persistente, esattamente come nel mockup.

**Widget (Glance).** Due stati: se non stai ascoltando mostra i 5 dischi di oggi; se stai
ascoltando diventa un controllo di riproduzione con copertina.

**Audio in background.** `MediaSessionService` + notifica media = riproduzione a schermo spento,
controlli su lockscreen, Bluetooth, cuffie, Android Auto. Gli URL di stream YouTube scadono in
~6 ore, quindi non si memorizzano: si risolve `videoId → streamUrl` al momento del play, con cache
in memoria a TTL 5h e ri-risoluzione trasparente se il player restituisce 403.

**Nota legale, una volta sola:** estrarre lo stream audio da YouTube viola i ToS di YouTube.
Questa app è quindi **sideload-only**, non pubblicabile sul Play Store. È lo stesso compromesso già
messo per iscritto in `ytsampler-android/PIANO.md`. L'alternativa (IFrame come digmore) non può
suonare a schermo spento, quindi è incompatibile con quello che hai chiesto.

---

## 4. Il look neumorfico

Il mockup è neumorfismo scuro: fondo `#2A2A2A`-ish, superfici che emergono con **due ombre**
(chiara in alto a sinistra, scura in basso a destra), angoli molto arrotondati, nessun bordo.

Compose non ha ombre colorate né inner-shadow native. Serve un `Modifier.neumorphic()` custom che
disegna su canvas nativo con `BlurMaskFilter` per le due ombre esterne, e per gli stati premuti/incassati
(la ghiera del player, la seek bar) usa `RenderEffect` (API 31+) con fallback a un drawable pre-renderizzato
sotto Android 12. **È la parte di UI a rischio più alto**: la fedeltà al mockup si vede solo a
schermo. Prevedo un giro di screenshot-e-correzione dedicato (Fase 5).

Tipografia: sans-serif leggero, tracking largo sulle intestazioni maiuscole (`SWEETENER` nel mockup),
peso normale sui titoli. Font da scegliere in Fase 5 su confronto visivo.

---

## 5. Fasi

| # | Cosa | Verifica di uscita |
|---|---|---|
| **0** | Repo `E:\jatz\` + git init + repo GitHub pubblico | `git push` ok |
| **1** | Motore: vendorizza da digmore, `discogs_jatz.py` due finestre, `preview.py` iTunes/Deezer, `curate.py` | Girando in locale produce un `drops/*.json` valido con 3+2 dischi plausibili |
| **2** | `daily.yml` su Actions + GitHub Pages + `index.json` | Un run manuale (`workflow_dispatch`) pubblica un drop e l'URL risponde |
| **3** | Scheletro app: Compose + Room + fetch del drop + seed offline | L'app apre e mostra i 5 dischi del seed senza rete |
| **4** | Audio: NewPipeExtractor + ExoPlayer + MediaSession + notifica | Suona a schermo spento, controlli su lockscreen |
| **5** | UI fedele al mockup: player, disco, libreria, loved, mini-player, neumorfismo | Screenshot affiancati al mockup, iterati |
| **6** | WorkManager 07:00 + notifica mattutina + widget Glance | Il drop del giorno arriva da solo |
| **7** | `build-apk.yml` → APK firmato in Releases | Scarichi e installi dal telefono |

Il **seed** (Fase 3) è un drop completo reale generato dal motore in Fase 1 e impacchettato in
`assets/seed_drop.json` con le copertine dentro l'APK: alla prima apertura l'app è già piena e
testabile anche in aereo. Hai chiesto 3 dischi di default — ne metto 5 (un drop intero), così
testi anche la distinzione vintage/moderno.

---

## 6. Il timing delle 8:00

GitHub Actions usa cron **UTC** e i job schedulati partono con ritardo variabile (spesso 5–20
minuti, occasionalmente di più sotto carico). Quindi:

- job alle **03:30 UTC** = 05:30 ora italiana estiva → ~2h30 di margine anche col ritardo peggiore
- l'app fa fetch alle **07:00 locali** con WorkManager, retry a backoff fino alle 08:00
- se il drop non c'è ancora, l'app mostra l'ultimo disponibile e riprova; non resta mai vuota

**Trappola da non dimenticare:** GitHub disattiva i workflow schedulati dopo **60 giorni di
inattività del repo**, e i commit fatti dal bot con `GITHUB_TOKEN` **non contano** come attività.
Il fix corretto è un PAT fine-grained (solo questo repo, scope contents+workflow) messo come
secret `JATZ_PAT` — il workflow lo usa se presente, altrimenti ricade su `GITHUB_TOKEN` (che
funziona da subito, senza bisogno di crearlo ora). Non ho usato il tuo token `gh` personale per
questo: ha scope troppo ampi (repo+workflow+gist+read:org su *tutti* i tuoi repo) per essere
messo come secret di un singolo progetto. Se vuoi eliminare del tutto il rischio dei 60 giorni,
crea un fine-grained PAT da github.com/settings/tokens limitato al solo repo `jatz` e mandamelo:
lo metto come `JATZ_PAT`. Nel frattempo, ogni push che faccio durante lo sviluppo resetta comunque
il contatore di inattività.

---

## 7. Rischi, con il piano B già deciso

| | Rischio | Piano B |
|---|---|---|
| R1 | Copertura iTunes/Deezer bassa sul materiale oscuro 1968-83 → lo scoring CLAP diventa raro e il profilo si appiattisce | Terzo tentativo con yt-dlp sul runner (a volte passa); e se serve, sposto lo scoring audio **sul telefono** al primo ascolto, usando il punteggio testuale solo come pre-filtro notturno |
| R2 | Discogs ha poca roba jazz 2018+ ben taggata → il lato moderno si ripete | Aggiungo MusicBrainz/Bandcamp come seconda fonte per la sola finestra moderna |
| R3 | NewPipeExtractor si rompe (YouTube cambia spesso) | È open source e si aggiorna spesso: aggiornare la dipendenza e ripubblicare l'APK. Rischio strutturale di qualunque app di questo tipo, non eliminabile |
| R4 | CLAP (~2GB) rallenta il job notturno | `actions/cache` sul checkpoint; il budget è comunque illimitato su repo pubblico |
| R5 | Il neumorfismo in Compose non regge il confronto col mockup | Fase 5 è iterativa per costruzione; in caso estremo, ombre pre-renderizzate come 9-patch |

---

## 8. Domande aperte

**D1 — I 5 dischi sono fissi tutto il giorno?** Cioè: se non ascolto i dischi di lunedì, martedì ne
arrivano altri 5 e quelli di lunedì scivolano in libreria (mia ipotesi, coerente col concept della
razione), oppure vuoi un meccanismo di "arretrati"?

**D2 — Il posto del pulsante download nel mockup.** Nel mockup di destra ci sono due bottoni
circolari ai lati della copertina: cuore a sinistra, download a destra. Tolgo il download e lascio
il cuore asimmetrico, oppure ci metto qualcos'altro (shuffle del disco? info Discogs?).

**D3 — "Mi piace" a livello di disco?** Hai chiesto LOVED TRACKS per traccia. Serve anche un
preferito per disco intero, o la libreria basta com'è?

**D4 — Quanto oscuro deve essere?** digmore ha un filtro Last.fm che scarta i dischi troppo famosi.
Per JATZ lo tengo aggressivo (solo roba veramente da crate-digging) o più morbido, così ogni tanto
passa anche un classico riconoscibile?

**D5 — Confermi il repo pubblico?** Serve per Pages e per i minuti Actions gratuiti illimitati.
Conterrebbe solo metadati di dischi e il codice dell'app — nessun segreto, nessun audio. Se preferisci
privato, l'alternativa è Cloudflare R2/Pages per servire i JSON, un pelo più di setup.
