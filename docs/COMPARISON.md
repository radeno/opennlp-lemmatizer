# Lemmatizer comparison (Czech / Slovak)

How this repo's two filters compare with the deployed jLemmaGen plugin and UDPipe, for Czech/Slovak
lemmatization in OpenSearch / Elasticsearch.

Engines:

- **jLemmaGen** — context-free RDR rules (`.lem`); the `analysis-lemmagen` plugin. Fast, but
  case-sensitive, so it mangles capitalized words (`Je → Jy`, `Deti → Deť`), and can't disambiguate by
  part of speech.
- **`opennlp_lemmatizer`** (this repo) — POS-aware OpenNLP; Apache models from Universal Dependencies.
- **`dictionary_lemmatizer`** (this repo) — flat `form → lemma` lookup. Fast and POS-free; a form
  with several lemmas is resolved to its most likely reading. Two dictionary sources:
  **MULTEXT-East** ([nl.ijs.si/ME](http://nl.ijs.si/ME/), `-mte`, CC BY-SA 4.0, commercial OK) — the
  authoritative morphological lexicon michmech itself was derived from, widest coverage (Slovak 922k
  forms); and **Universal Dependencies** (`-ud`, gold treebank lemmas, best for Czech's huge PDT).
  Rule of thumb: **Slovak → MTE, Czech → UD.**
- **UDPipe** — full morphological tagger (ÚFAL), native C++/JNI. See [`../experiments/udpipe/`](../experiments/udpipe/).

## Verified: `dictionary_lemmatizer` vs the *deployed* jLemmaGen plugin

The deployed `analysis-lemmagen` plugin (its own `cs.lem` / `sk.lem`) vs this repo's
`dictionary_lemmatizer` (Slovak from MTE, Czech from UD; a `lowercase` filter upstream) — run on real
nodes, **identical on OpenSearch 3.7.0 and Elasticsearch 9.4.2**:

Slovak — MTE `sk` is large (922k forms):

| sentence | deployed jLemmaGen | `dictionary_lemmatizer` (`sk-mte`) |
|---|---|---|
| Bratislava **je** krásne mesto | Bratislava **jesť** ✗ krásny mesto | Bratislava **byť** ✓ krásny mesto |
| **Moji** priatelia … starých hradoch | **Moj** ✗ priateľ … starý hrad | **môj** ✓ priateľ … starý hrad |

→ MTE wins on Slovak (`je → byť`, `Moji → môj`, `priatelia → priateľ`); the one homonym it can't split
flat is `lese → lesa` (only POS / UDPipe resolves it — see below).

Czech — `dictionary_lemmatizer` with the UD-derived `cs-ud.txt` (185k gold forms):

| sentence | deployed jLemmaGen | `dictionary_lemmatizer` (`cs-ud`) |
|---|---|---|
| Praha **je** krásné město | Praha **on** ✗ krásný město | Praha **být** ✓ krásný město |
| **Tři** ženy nesly **tři** jablka | **Tř** ✗ žena nést tři jablka | **tři** ✓ žena nést **tři** ✓ jablko |

→ UD wins cleanly: gold lemmas give `je → být`, `tři → tři` and `jablka → jablko` where jLemmaGen
fails. Czech uses UD; Slovak uses MTE.

## Quality vs OpenNLP / UDPipe (discriminating tokens)

| token | jLemmaGen | dictionary | `opennlp_lemmatizer` | UDPipe |
|---|---|---|---|---|
| cs `je` (is) | `on` ✗ | `být` ✓ | `být` ✓ | `být` ✓ |
| cs `Tři` (three) | `Tř` ✗ | `tři` ✓ | `tři` ✓ | `tři` ✓ |
| sk `je` (is) | `jesť` ✗ | `byť` ✓ | `byť` ✓ | `byť` ✓ |
| sk `lese` (forest) | `lesa` ✗ | `lesa` ✗ | `les` ✓ | `les` ✓ |
| sk `Bratislava` | `Bratislava` ✓ | `Bratislava` ✓ | `bratislav` ✗ | `bratislava` ✓ |

The `dictionary` column uses the recommended per-language source — `cs-ud` for Czech, `sk-mte` for
Slovak. `lese` shows the flat-dictionary limit: `les` vs `lesa` is a true homonym only POS (opennlp /
UDPipe) resolves in context. UDPipe (full morphology) is the highest quality but native and CC BY-NC-SA.

## Whole-sentence comparison

Full sentences through the **deployed jLemmaGen plugin**, **`opennlp_lemmatizer`**, and
**`dictionary_lemmatizer`** — real `_analyze` output on the live OpenSearch 3.7.0 node (`dictionary`
runs after a `lowercase` filter, the recommended chain; jLemmaGen folds nothing; opennlp lower-cases
its own lemmas). Wrong lemmas marked `✗`.

**Czech** (dictionary = `cs-ud`):

```
Ženy nesly těžké tašky plné zralých jablek
  jLemmaGen:   Žit✗ nést těžký taška plný zralý jablek✗
  opennlp:     žena nést těžký taška plný zralý jablko          (all correct)
  dictionary:  žena nést těžký taška plný zralých✗ jablko

Moji přátelé četli zajímavé knihy o starých hradech
  jLemmaGen:   Moj✗ přítel číst zajímavý kniha on✗ starý hrad
  opennlp:     můj přítel číst zajímavý kniha o starý hrad      (all correct)
  dictionary:  můj přítel číst zajímavý kniha o starý hrad      (all correct)
```

**Slovak** (dictionary = `sk-mte`, with an upstream `lowercase` filter):

```
Včera sme v lese videli tri veľké medvede
  jLemmaGen:   Včer✗ byť v lesa✗ vidieť tri veľký medveď
  opennlp:     včera byť v les vidieť tri veľký medveď          (all correct)
  dictionary:  včera byť v lesa✗ vidieť tri veľký medveď        (only lese→lesa homonym)

Moji priatelia čítali zaujímavé knihy o starých hradoch
  jLemmaGen:   Moj✗ priateľ čítať zaujímavý kniha o starý hrad
  opennlp:     môj priateľ čítať zaujímavý kniha o starý hrad   (all correct)
  dictionary:  môj priateľ čítať zaujímavý kniha o starý hrad   (all correct)

Ženy niesli ťažké tašky plné zrelých jabĺk
  jLemmaGen:   Žena niesť ťažký taška plný zrelý jablko         (correct, but keeps the capital)
  opennlp:     žena niesť ťažký taška plný zrelý jablko         (all correct)
  dictionary:  žena niesť ťažký taška plný zrelý jablko         (all correct)
```

What it shows:

- **`opennlp_lemmatizer` is correct on every token.** POS tagging does two things context-free rules
  can't: disambiguate homonyms (`tri` → `tri`, not `trieť`) and normalise case (`Včera` → `včera`).
- **jLemmaGen** mangles capitalised and irregular forms — `Ženy → Žit` ("women" → "to live"),
  `o → on`, `Včera → Včer`, `lese → lesa`, `medvědy → medvěda` — because surface rules have no notion
  of word class, and it never lowercases the leading word.
- **`dictionary_lemmatizer`** sits between them: fast and mostly right, but a flat list still has gaps
  (cs `zralých` unchanged) and can't split genuine homonyms (sk `lese → les`/`lesa`) — yet with an
  upstream `lowercase` filter it handles capitalisation (`Včera → včera`, which jLemmaGen mangles) and
  never invents a wrong stem the way a bad rule does.

## Throughput

Two measurements, because *where* you measure changes the number.

**Library microbenchmark** (`core`, steady state, no HTTP) — the pure per-token cost:

| engine | tokens/sec |
|---|---:|
| dictionary / jLemmaGen (flat lookup) | ~5,000,000 |
| `opennlp_lemmatizer` (MaxEnt POS per token) | ~3,300 |
| UDPipe (native) | ~10,000–200,000 |

**Real node** (OpenSearch 3.7, `_analyze` through a configured *index analyzer* so the filter loads
once), ~900-token request:

| filter | tokens/sec | note |
|---|---:|---|
| baseline (no filter) | ~530,000 | HTTP + tokenizer ceiling |
| `dictionary_lemmatizer` | ~410,000 | HTTP-bound — as fast as it gets |
| jLemmaGen (deployed plugin) | ~320,000 | HTTP-bound |
| `opennlp_lemmatizer` | ~12,000 | POS tagger per token |

The flat-lookup filters (dictionary, jLemmaGen) are so fast they hit the request ceiling — equal in
practice. `opennlp_lemmatizer` is ~30× slower *even with HTTP overhead masking the others*; in the
indexing pipeline (no per-doc HTTP) the gap widens toward the library ratio.

> **Pitfall.** An *inline* filter in `_analyze` rebuilds the analyzer per request, so it re-loads the
> dictionary/model every call — the 21 MB MTE dictionary cost ~340 ms/call that way. Always
> run (and benchmark) through a configured index analyzer, where the filter loads once per shard.

## Choosing

- **Speed-first, no native, decent quality:** `dictionary_lemmatizer` with a well-covered dictionary
  — beats jLemmaGen at the same speed (Slovak via MTE, Czech via UD).
- **Best pure-Java quality, POS-aware:** `opennlp_lemmatizer` (Czech especially).
- **Highest quality, can accept native lib + non-commercial models:** UDPipe.
- **Hybrid search (k-NN + BM25):** embeddings absorb most morphology, so fast + decent (dictionary)
  is usually enough on the lexical side.

## Verified

Both filters were installed and exercised on real nodes — **OpenSearch 3.7.0** and
**Elasticsearch 9.4.2** — loading cleanly (no JarHell; Elasticsearch needs no extra entitlements for
`config/opennlp/` reads). `opennlp_lemmatizer`: `_analyze "Děkuji že jsi přišel"` → `děkovat že být
přijít` on both. `dictionary_lemmatizer` is compared against the deployed jLemmaGen plugin above.
