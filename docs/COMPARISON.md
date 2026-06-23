# Lemmatizer comparison (Czech / Slovak)

How this repo's two filters compare with the deployed jLemmaGen plugin and UDPipe, for Czech/Slovak
lemmatization in OpenSearch / Elasticsearch.

Engines:

- **jLemmaGen** — context-free RDR rules (`.lem`); the `analysis-lemmagen` plugin. Fast, but mangles
  capitalized words and can't disambiguate by part of speech.
- **`opennlp_lemmatizer`** (this repo) — POS-aware OpenNLP; Apache models from Universal Dependencies.
- **`dictionary_lemmatizer`** (this repo) — flat `form → lemma` lookup. Fast and POS-free; a form
  with several lemmas is resolved to its most likely reading. Two dictionary sources, by language:
  **Slovak** from [michmech/lemmatization-lists](https://github.com/michmech/lemmatization-lists)
  (ODbL, 847k forms), **Czech and other languages** built from the same
  [Universal Dependencies](https://universaldependencies.org/) treebanks the OpenNLP models train on
  (`-ud`, gold lemmas). Rule of thumb: **Slovak → michmech, others → UD.**
- **UDPipe** — full morphological tagger (ÚFAL), native C++/JNI. See [`../experiments/udpipe/`](../experiments/udpipe/).

## Verified: `dictionary_lemmatizer` vs the *deployed* jLemmaGen plugin

The deployed `analysis-lemmagen` plugin (its own `cs.lem` / `sk.lem`) vs this repo's
`dictionary_lemmatizer` (Slovak from michmech, Czech from UD) — run on real nodes, **identical on
OpenSearch 3.7.0 and Elasticsearch 9.4.2**:

Slovak — michmech `sk` is large (847k forms):

| sentence | deployed jLemmaGen | `dictionary_lemmatizer` |
|---|---|---|
| Bratislava **je** krásne mesto | Bratislava **jesť** ✗ krásny mesto | Bratislava **byť** ✓ krásny mesto |
| **Boli** sme v **lese** a videli sme stromy | **Boľ** ✗ byť v **lesa** ✗ a vidieť byť strom | **byť** ✓ byť v **les** ✓ … **as** ✗ … strom |

→ the dictionary wins on Slovak (fixes `je`, `Boli`, `lese`); only `a → as` is wrong.

Czech — `dictionary_lemmatizer` with the UD-derived `cs-ud.txt` (185k gold forms):

| sentence | deployed jLemmaGen | `dictionary_lemmatizer` (`cs-ud`) |
|---|---|---|
| Praha **je** krásné město | Praha **on** ✗ krásný město | Praha **být** ✓ krásný město |
| **Tři** ženy nesly **tři** jablka | **Tř** ✗ žena nést tři jablka | **tři** ✓ žena nést **tři** ✓ jablko |

→ UD wins cleanly: gold lemmas give `je → být`, `tři → tři` and `jablka → jablko` where jLemmaGen
fails. Czech uses UD; Slovak uses michmech.

## Quality vs OpenNLP / UDPipe (discriminating tokens)

| token | jLemmaGen | dictionary | `opennlp_lemmatizer` | UDPipe |
|---|---|---|---|---|
| cs `je` (is) | `on` ✗ | `být` ✓ | `být` ✓ | `být` ✓ |
| cs `Tři` (three) | `Tř` ✗ | `tři` ✓ | `tři` ✓ | `tři` ✓ |
| sk `je` (is) | `jesť` ✗ | `byť` ✓ | `byť` ✓ | `byť` ✓ |
| sk `lese` (forest) | `lesa` ✗ | `les` ✓ | `les` ✓ | `les` ✓ |
| sk `Bratislava` | `Bratislava` ✓ | `Bratislava` ✓ | `bratislav` ✗ | `bratislava` ✓ |

The `dictionary` column uses the recommended per-language source — `cs-ud` for Czech, `sk-michmech`
for Slovak. Only POS (opennlp / UDPipe) resolves homonyms *in context*; a flat dictionary bakes in
one reading. UDPipe (full morphology) is the highest quality but native and CC BY-NC-SA.

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
> dictionary/model every call — the 19 MB michmech dictionary cost ~340 ms/call that way. Always
> run (and benchmark) through a configured index analyzer, where the filter loads once per shard.

## Choosing

- **Speed-first, no native, decent quality:** `dictionary_lemmatizer` with a well-covered dictionary
  — beats jLemmaGen at the same speed (Slovak via michmech, Czech via UD).
- **Best pure-Java quality, POS-aware:** `opennlp_lemmatizer` (Czech especially).
- **Highest quality, can accept native lib + non-commercial models:** UDPipe.
- **Hybrid search (k-NN + BM25):** embeddings absorb most morphology, so fast + decent (dictionary)
  is usually enough on the lexical side.

## Verified

Both filters were installed and exercised on real nodes — **OpenSearch 3.7.0** and
**Elasticsearch 9.4.2** — loading cleanly (no JarHell; Elasticsearch needs no extra entitlements for
`config/opennlp/` reads). `opennlp_lemmatizer`: `_analyze "Děkuji že jsi přišel"` → `děkovat že být
přijít` on both. `dictionary_lemmatizer` is compared against the deployed jLemmaGen plugin above.
