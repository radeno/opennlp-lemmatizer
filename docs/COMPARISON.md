# Lemmatizer comparison (Czech / Slovak)

How this repo's three filters compare with the deployed jLemmaGen plugin and UDPipe, for Czech/Slovak
lemmatization in OpenSearch / Elasticsearch.

Engines:

- **jLemmaGen** — context-free RDR rules (`.lem`); the `analysis-lemmagen` plugin. Fast, but
  case-sensitive, so it mangles capitalized words (`Je → Jy`, `Deti → Deť`), and can't disambiguate by
  part of speech.
- **`opennlp_lemmatizer`** (this repo) — POS-aware OpenNLP; Apache models from Universal Dependencies.
- **`dictionary_lemmatizer`** (this repo) — flat `form → lemma` lookup, stored in a Lucene FST (a few
  MB). Fast and POS-free; a form with several lemmas is resolved to its most likely reading. Two sources:
  **MULTEXT-East** ([nl.ijs.si/ME](http://nl.ijs.si/ME/), `-mte`, CC BY-SA 4.0, commercial OK) — the
  authoritative morphological lexicon michmech itself was derived from, widest coverage (Slovak 922k
  forms); and **Universal Dependencies** (`-ud`, gold treebank lemmas, best for Czech's huge PDT).
  Rule of thumb: **Slovak → MTE, Czech → UD.**
- **`pos_dictionary_lemmatizer`** (this repo) — POS-aware FST dictionary (`form + POS → lemma`,
  MULTEXT-East) consulted first, OpenNLP MaxEnt model as fallback. Resolves homonyms the flat dictionary
  cannot (`hradu → hrad`, not `hrada`) while staying tiny in memory (the FST is ~1 MB). The recommended
  Slovak filter; see [IMPROVEMENTS.md](IMPROVEMENTS.md).
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

→ On these sentences the MTE-backed lookup is right where jLemmaGen's rules misfire (`je → byť`,
`Moji → môj`, `priatelia → priateľ`); the one homonym it can't split flat is `lese → lesa` (only POS /
UDPipe resolves it — see below). The reverse also happens elsewhere: jLemmaGen's rules lemmatise some
inflected forms the flat dictionary is missing.

Czech — `dictionary_lemmatizer` with the UD-derived `cs-ud.txt` (185k gold forms):

| sentence | deployed jLemmaGen | `dictionary_lemmatizer` (`cs-ud`) |
|---|---|---|
| Praha **je** krásné město | Praha **on** ✗ krásný město | Praha **být** ✓ krásný město |
| **Tři** ženy nesly **tři** jablka | **Tř** ✗ žena nést tři jablka | **tři** ✓ žena nést **tři** ✓ jablko |

→ On these Czech examples the UD gold lemmas are right where jLemmaGen fails (`je → být`, `tři → tři`,
`jablka → jablko`). Czech uses UD; Slovak uses MTE.

## Quality on Slovak — discriminating tokens

Real `_analyze` output on the live OpenSearch 3.7 node, all through the recommended chain
(`lowercase` → lemmatizer); `✗` = wrong lemma. `dictionary` uses `sk-mte`, `pos_dictionary` uses
`sk-pos.bin` + `sk-lemmas.bin` + `sk-mte-pos.txt`.

| token | jLemmaGen | `dictionary` | `opennlp` | `pos_dictionary` |
|---|---|---|---|---|
| `je` (is) | `jesť` ✗ | `byť` ✓ | `byť` ✓ | `byť` ✓ |
| `lese` (forest, loc.) | `lesa` ✗ | `lesa` ✗ | `les` ✓ | `les` ✓ |
| `pri` (by/at) | `prieť` ✗ | `prieť` ✗ | `pri` ✓ | `pri` ✓ |
| `hradu` (castle, gen.) | `hrad` ✓ | `hrad` ✓ | `hrada` ✗ | `hrad` ✓ |
| `Bratislava` | `bratislav` ✗ | `Bratislava` ✓ | `bratislav` ✗ | `Bratislava` ✓ |

**`pos_dictionary_lemmatizer` is the only filter correct on every token.** It inherits the dictionary's
wins (`Bratislava`, `hradu`) *and* adds POS disambiguation where a flat list can't: `lese → les`,
`pri → pri` are homonyms the flat dictionary collapses to `lesa`/`prieť` (it mistakes the preposition
`pri` for the verb `prieť`), while the model-only `opennlp` mis-guesses inflected forms like
`hradu → hrada`. jLemmaGen (case-sensitive surface rules) mangles capitalised and irregular forms
(`je → jesť`, `Bratislava → bratislav`). UDPipe (gold, native, CC BY-NC-SA) matches the `pos_dictionary`
column. For **Czech** `pos_dictionary` is not built (no cs POS-dictionary) — use `dictionary` (`cs-ud`)
or `opennlp`; see the Czech examples above.

> **These are selected discriminating tokens, not a ranking.** They show *where* POS-awareness and
> case-folding help — not overall accuracy. On the bulk of common words all four agree, and **jLemmaGen
> is better on some forms**: its RDR rules generalise to inflected/derived forms that an exact-match
> dictionary is simply missing (there ours falls back to the MaxEnt model, which can mis-guess). Which
> engine "wins" depends entirely on the token mix. A definitive verdict needs a large-scale evaluation
> against gold lemmas (thousands of sentences), which has **not** been run here — treat the table as
> illustrative, not a scoreboard.

## Whole-sentence comparison

Full sentences through the **deployed jLemmaGen plugin**, **`opennlp_lemmatizer`**,
**`dictionary_lemmatizer`** and (Slovak only) **`pos_dictionary_lemmatizer`** — real `_analyze` output
on the live OpenSearch 3.7.0 node (`dictionary`/`pos_dict` run after a `lowercase` filter, the
recommended chain; jLemmaGen folds nothing; opennlp lower-cases its own lemmas). Wrong lemmas marked
`✗`. These are a handful of illustrative sentences, not a representative sample (see the caveat above).

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
  jLemmaGen:    Včer✗ byť v lesa✗ vidieť tri veľký medveď
  opennlp:      včera byť v les vidieť tri veľký medveď          (all correct)
  dictionary:   včera byť v lesa✗ vidieť tri veľký medveď        (only lese→lesa homonym)
  pos_dict:     včera byť v les vidieť tri veľký medveď          (all correct)

Moji priatelia čítali zaujímavé knihy o starých hradoch
  jLemmaGen:    Moj✗ priateľ čítať zaujímavý kniha o starý hrad
  opennlp:      môj priateľ čítať zaujímavý kniha o starý hrad   (all correct)
  dictionary:   môj priateľ čítať zaujímavý kniha o starý hrad   (all correct)
  pos_dict:     môj priateľ čítať zaujímavý kniha o starý hrad   (all correct)

Ženy niesli ťažké tašky plné zrelých jabĺk
  jLemmaGen:    Žena niesť ťažký taška plný zrelý jablko         (correct, but keeps the capital)
  opennlp:      žena niesť ťažký taška plný zrelý jablko         (all correct)
  dictionary:   žena niesť ťažký taška plný zrelý jablko         (all correct)
  pos_dict:     žena niesť ťažký taška plný zrelý jablko         (all correct)
```

What these sentences show (illustrative, not a ranking):

- **`pos_dictionary_lemmatizer` and `opennlp_lemmatizer` are correct on every token here.** POS tagging
  does two things context-free rules can't: disambiguate homonyms (`tri` → `tri`, not `trieť`;
  `lese → les`) and normalise case (`Včera` → `včera`). `pos_dict` adds the dictionary's lemma quality
  on top of the same POS tags.
- **jLemmaGen** mangles capitalised and irregular forms here — `Ženy → Žit` ("women" → "to live"),
  `o → on`, `Včera → Včer`, `lese → lesa` — because surface rules have no notion of word class and it
  never lowercases the leading word. (Its rules do, conversely, generalise to forms a fixed dictionary
  lacks — see the caveat above.)
- **`dictionary_lemmatizer`** sits between them: fast and mostly right, but a flat list still has gaps
  (cs `zralých` unchanged) and can't split genuine homonyms (sk `lese → les`/`lesa`) — yet with an
  upstream `lowercase` filter it handles capitalisation (`Včera → včera`, which jLemmaGen mangles) and
  never invents a wrong stem the way a bad rule does.

## Throughput and memory (measured on OpenSearch 3.7)

All four filters configured as *index* analyzers (so the filter loads once per shard, not per request)
and driven with a ~370-token Slovak request; memory is the retained heap of one loaded copy. Measured
2026-06, after the M1/M2 optimisations (see [IMPROVEMENTS.md](IMPROVEMENTS.md)).

| filter | tokens/sec | rel. | ms/call | memory / copy | shared across indices? |
|---|---:|---:|---:|---:|---|
| jLemmaGen (deployed plugin) | ~252,000 | 1.00× | 1.5 | ~18 MB | no (separate plugin) |
| `dictionary_lemmatizer` | ~234,000 | 0.93× | 1.6 | **~2 MB** | **yes** — node-wide cache (M1) |
| `pos_dictionary_lemmatizer` | ~54,000 | 0.22× | 6.8 | ~8 MB | **yes** — node-wide cache (M1) |
| `opennlp_lemmatizer` | ~7,900 | 0.03× | 47 | ~7 MB | **yes** — node-wide cache (M1) |

**Speed.** The flat-lookup filters (`dictionary`, jLemmaGen) are the fastest, near-equal.
`pos_dictionary_lemmatizer` pays the MaxEnt POS tagger but the FST answers ~98 % of tokens, so it is
**~7× faster than the model-only `opennlp_lemmatizer`** (which runs the MaxEnt *lemmatizer* for every
token). POS-awareness costs ~4–5× the throughput of a flat lookup — the price of splitting homonyms and
normalising case.

**Memory.** Every dictionary in this repo is stored as a Lucene FST, which shares key prefixes and lemma
suffixes across the whole dictionary — a few MB where a hash map needs ~100 MB:

| backing store | Slovak retained heap |
|---|---:|
| FST (`dictionary`, flat) | **~2 MB** |
| FST (`pos_dictionary`, form+POS) | **~1 MB** |
| 2 MaxEnt models (POS + lemmatizer) | ~7 MB |
| jLemmaGen RDR rules | ~18 MB |

`dictionary_lemmatizer` originally used a `CharArrayMap` (~106 MB, a zero-allocation lookup straight off
the token buffer). It was **replaced with an FST**: in isolation the FST lookup is ~20× slower (~2M vs
~40M lookups/sec), but that is fully masked by the analysis pipeline — **end-to-end throughput is
unchanged (measured equal on the node) while memory drops ~50×.** The filter encodes the term into a
reused buffer, so the only added cost is the automaton walk.

**De-duplication (M1).** A token-filter factory is built per *(index, filter)*. All three filters cache
their FST node-wide (keyed by path+size+mtime), so N indices on the same file share **one** copy —
measured ~0.4 MB per extra index (4 `dictionary` indices added ~1 MB total). jLemmaGen (a separate
plugin) is not cached, so it still loads per index.

> **Measurement notes.** Throughput is from the live node through a configured index analyzer; the
> CharArrayMap-vs-FST comparison that motivated the switch measured equal end-to-end (~97k vs ~101k
> tokens/sec). Memory is the clean retained heap of each structure, cross-checked on the node. An
> *inline* filter in `_analyze` rebuilds the analyzer per request — always benchmark through an index
> analyzer.

For older steady-state library microbenchmarks (no HTTP): CharArrayMap ~40M, flat FST ~2M,
`opennlp_lemmatizer` ~3,300 lookups/sec, UDPipe ~10k–200k.

## Choosing

- **Slovak, quality-first, pure-Java:** `pos_dictionary_lemmatizer` — POS-aware FST dictionary, tiny
  in memory (~8 MB, shared node-wide), right on the homonyms and toponyms a flat list misses
  (`lese → les`, `pri → pri`, `Bratislava`). The recommended Slovak default. The POS tagger costs ~4–5×
  the throughput of a flat lookup, still ~50k tokens/sec.
- **Speed-first, no POS tagger:** `dictionary_lemmatizer` with a well-covered dictionary (Slovak via
  MTE, Czech via UD) — the fastest option, and **comparable in quality to jLemmaGen rather than strictly
  better**: each wins on different forms (the dictionary on case + known lemmas, jLemmaGen's rules on
  inflections it lacks), so pick by your token mix. A blanket ranking would need a large-scale eval we
  have not run.
- **Best pure-Java quality without a dictionary, POS-aware:** `opennlp_lemmatizer` (Czech especially).
- **Highest quality, can accept native lib + non-commercial models:** UDPipe.
- **Hybrid search (k-NN + BM25):** embeddings absorb most morphology, so fast + decent (dictionary)
  is usually enough on the lexical side.

## Verified

All three filters (`opennlp_lemmatizer`, `dictionary_lemmatizer`, `pos_dictionary_lemmatizer`) were
installed and exercised on real nodes — **OpenSearch 3.7.0** and **Elasticsearch 9.4.2** — loading
cleanly (no JarHell; Elasticsearch needs no extra entitlements for `config/opennlp/` reads).
`opennlp_lemmatizer`: `_analyze "Děkuji že jsi přišel"` → `děkovat že být přijít` on both. The Slovak
comparisons above are live `_analyze` output from the OpenSearch 3.7 node.
