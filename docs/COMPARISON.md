# Lemmatizer comparison (Czech / Slovak)

Why this repo uses OpenNLP — and where it sits versus the alternatives we evaluated for
Czech/Slovak lemmatization in OpenSearch/Elasticsearch.

Engines compared:

- **jLemmaGen** — context-free RDR rules (`.lem`), the existing `analysis-lemmagen` plugin.
- **OpenNLP** — POS-aware (this repo). Apache models trained from Universal Dependencies.
- **flat dictionary** — `word→lemma` lookup (e.g. [michmech/lemmatization-lists](https://github.com/michmech/lemmatization-lists), 858k SK pairs, ODbL).
- **UDPipe** — full morphological tagger (ÚFAL), native C++/JNI. See [`../experiments/udpipe/`](../experiments/udpipe/).

## Quality — the discriminating tokens

Czech:

| token | jLemmaGen | flat dict | **OpenNLP** | UDPipe |
|---|---|---|---|---|
| `je` (is) | `on` ✗ | `byť`/`jesť` ✗ | **`být`** ✓ | `být` ✓ |
| `jablka` (apples) | `jablka` ✗ | `jablko` ✓ | **`jablko`** ✓ | `jablko` ✓ |
| `Tři` (three) | `Tř` ✗ | `tři` ✓ | **`tři`** ✓ | `tři` ✓ |

Slovak (note: the OpenNLP `sk` model is small — weaker than `cs`):

| token | jLemmaGen | flat dict | OpenNLP | **UDPipe** |
|---|---|---|---|---|
| `je` (is) | `jesť` ✗ | `byť`/`jesť` ✗ | `byť` ✓ | **`byť`** ✓ |
| `tri` (three) | `tri` ✓ | `trieť` ✗ | `tri` ✓ | **`tri`** ✓ |
| `lese` (forest) | `lesa` ✗ | `les`/`lesa` ✗ | `les` ✓ | **`les`** ✓ |
| `jablká` (apples) | `jablko` ✓ | `jablko` ✓ | `jablká` ✗ | **`jablko`** ✓ |
| `Bratislava` | `Bratislava` ✓ | (not found) | `bratislav` ✗ | **`bratislava`** ✓ |

Takeaways:

- **POS matters.** `je → byť` vs `je → on/jesť` is only resolvable with a part-of-speech tag —
  the whole reason this plugin runs an OpenNLP POS tagger before the lemmatizer.
- **jLemmaGen** is fast but brittle: it mangles capitalized/sentence-initial words (`Tři → Tř`)
  and can't disambiguate. Pre-lowercasing fixes some cases but breaks others (`Bratislava → bratislav`).
- **A flat dictionary** is safe (returns the word unchanged if unknown) but, lacking POS, leaves
  ambiguities unresolved (`je → byť/jesť`, `tri → trieť`).
- **OpenNLP** is the best *pure-Java* option; quality tracks the underlying treebank size
  (Czech PDT is large → strong; Slovak SNK is small → weaker, see `Bratislava`, `jablká`).
- **UDPipe** is the highest quality (full MorfFlex-style morphology) but needs a native JNI
  library and CC BY-NC-SA models — see the experiment.

## Throughput

Measured on one Czech corpus, steady state (ops built once, `reset()` per sentence):

| engine | tokens/sec | µs/token |
|---|---:|---:|
| jLemmaGen | ~5,100,000 | 0.20 |
| OpenNLP (POS + lemma) | ~3,300 | ~307 |
| UDPipe (native) | ~10,000–200,000 | — |

OpenNLP is ~**1500×** slower than jLemmaGen — inherent to running a MaxEnt POS tagger per token.
For most indexing volumes this is fine; for very high throughput it can become a bottleneck.

## Choosing

- **Hybrid search (semantic k-NN + BM25):** the embeddings already absorb most morphological
  variation, so the lexical lemmatizer doesn't need to be top-tier. Fast + decent is the right
  call — jLemmaGen, or OpenNLP if you want POS-aware quality and the throughput is acceptable.
- **Lexical-only, quality matters, pure-Java:** OpenNLP (this repo).
- **Best Slovak/Czech quality, can accept a native lib + non-commercial models:** UDPipe.
- **Maximum speed, quality secondary:** jLemmaGen.

## Verified

Both plugins built here were installed and exercised on real nodes — OpenSearch 3.7.0 and
Elasticsearch 9.4.1 — `_analyze "Děkuji že jsi přišel"` → `děkovat že být přijít` on both,
loading cleanly (no JarHell; Elasticsearch needs no extra entitlements for `config/opennlp/` reads).
