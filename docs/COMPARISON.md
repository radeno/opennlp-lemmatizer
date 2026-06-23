# Lemmatizer comparison (Czech / Slovak)

How this repo's two filters compare with the deployed jLemmaGen plugin and UDPipe, for Czech/Slovak
lemmatization in OpenSearch / Elasticsearch.

Engines:

- **jLemmaGen** — context-free RDR rules (`.lem`); the `analysis-lemmagen` plugin. Fast, but mangles
  capitalized words and can't disambiguate by part of speech.
- **`opennlp_lemmatizer`** (this repo) — POS-aware OpenNLP; Apache models from Universal Dependencies.
- **`dictionary_lemmatizer`** (this repo) — flat `form → lemma` lookup, e.g. from
  [michmech/lemmatization-lists](https://github.com/michmech/lemmatization-lists) (ODbL). Fast and
  POS-free; homonyms are resolved to the largest-paradigm lemma.
- **UDPipe** — full morphological tagger (ÚFAL), native C++/JNI. See [`../experiments/udpipe/`](../experiments/udpipe/).

## Verified: `dictionary_lemmatizer` vs the *deployed* jLemmaGen plugin

The deployed `analysis-lemmagen` plugin (its own `cs.lem` / `sk.lem`) vs this repo's
`dictionary_lemmatizer` (michmech) — run on real nodes, **identical on OpenSearch 3.7.0 and
Elasticsearch 9.4.2**:

Slovak — michmech `sk` is large (847k forms):

| sentence | deployed jLemmaGen | `dictionary_lemmatizer` |
|---|---|---|
| Bratislava **je** krásne mesto | Bratislava **jesť** ✗ krásny mesto | Bratislava **byť** ✓ krásny mesto |
| **Boli** sme v **lese** a videli sme stromy | **Boľ** ✗ byť v **lesa** ✗ a vidieť byť strom | **byť** ✓ byť v **les** ✓ … **as** ✗ … strom |

→ the dictionary wins on Slovak (fixes `je`, `Boli`, `lese`); only `a → as` is wrong.

Czech — michmech `cs` is thin (~35k forms):

| sentence | deployed jLemmaGen | `dictionary_lemmatizer` |
|---|---|---|
| Praha **je** krásné město | Praha **on** ✗ krásný město | Praha **být** ✓ krásný město |
| **Tři** ženy nesly **tři** jablka | **Tř** ✗ žena nést tři jablka | **třít** ✗ žena nést **třít** ✗ jablka |

→ mixed: `je → být` is fixed, but the thin Czech list mangles `tři → třít`. **Coverage is everything.**

## Quality vs OpenNLP / UDPipe (discriminating tokens)

| token | jLemmaGen | dictionary | `opennlp_lemmatizer` | UDPipe |
|---|---|---|---|---|
| cs `je` (is) | `on` ✗ | `být` ✓ | `být` ✓ | `být` ✓ |
| cs `Tři` (three) | `Tř` ✗ | `třít` ✗ | `tři` ✓ | `tři` ✓ |
| sk `je` (is) | `jesť` ✗ | `byť` ✓ | `byť` ✓ | `byť` ✓ |
| sk `lese` (forest) | `lesa` ✗ | `les` ✓ | `les` ✓ | `les` ✓ |
| sk `Bratislava` | `Bratislava` ✓ | `Bratislava` ✓ | `bratislav` ✗ | `bratislava` ✓ |

Only POS (opennlp / UDPipe) resolves homonyms *in context*; a flat dictionary bakes in one reading.
UDPipe (full morphology) is the highest quality but native and CC BY-NC-SA.

## Throughput

Czech corpus, steady state (ops built once, `reset()` per sentence):

| engine | tokens/sec | notes |
|---|---:|---|
| `dictionary_lemmatizer` / jLemmaGen | ~5,000,000 | flat lookup, pure-Java |
| `opennlp_lemmatizer` | ~3,300 | MaxEnt POS tagger per token (~1500× slower) |
| UDPipe (native) | ~10,000–200,000 | native C++ |

## Choosing

- **Speed-first, no native, decent quality:** `dictionary_lemmatizer` with a well-covered dictionary
  — beats jLemmaGen at the same speed (great for Slovak via michmech; thin for Czech).
- **Best pure-Java quality, POS-aware:** `opennlp_lemmatizer` (Czech especially).
- **Highest quality, can accept native lib + non-commercial models:** UDPipe.
- **Hybrid search (k-NN + BM25):** embeddings absorb most morphology, so fast + decent (dictionary)
  is usually enough on the lexical side.

## Verified

Both filters were installed and exercised on real nodes — **OpenSearch 3.7.0** and
**Elasticsearch 9.4.2** — loading cleanly (no JarHell; Elasticsearch needs no extra entitlements for
`config/opennlp/` reads). `opennlp_lemmatizer`: `_analyze "Děkuji že jsi přišel"` → `děkovat že být
přijít` on both. `dictionary_lemmatizer` is compared against the deployed jLemmaGen plugin above.
