# udpipe-lemmatizer

POS-aware lemmatization for OpenSearch (Czech / Slovak / …) using **[UDPipe](https://ufal.mff.cuni.cz/udpipe)**
(ÚFAL, Charles University) via its JNI binding.

This is the highest-quality of the lemmatizers we evaluated for Slovak/Czech — it
disambiguates by part of speech, which dictionary/rule lemmatizers cannot. See
[docs/COMPARISON.md](docs/COMPARISON.md) for the full evaluation and why we landed here.

> **Status:** native build + Slovak smoke test working on macOS arm64 / JDK 25.
> The OpenSearch token-filter plugin is the next step (see [Roadmap](#roadmap)).

## Why UDPipe (short version)

| token | jLemmaGen | michmech dict | OpenNLP sk | **UDPipe sk** |
|---|---|---|---|---|
| `je` (is) | jesť ✗ | byť/jesť ✗ | byť ✓ | **byť** ✓ |
| `tri` (three) | tri ✓ | trieť ✗ | tri ✓ | **tri** ✓ |
| `lese` (forest) | lesa ✗ | les/lesa ✗ | les ✓ | **les** ✓ |
| `jablká` (apples) | jablko ✓ | jablko ✓ | jablká ✗ | **jablko** ✓ |
| speed | ~5M tok/s | ~fast | ~3K tok/s | ~10–200K tok/s |

UDPipe is the only option that is both **correct on POS-ambiguous words** and **fast**
(native C++). The price is a **native JNI library** (this repo builds it) and **CC BY-NC-SA**
models (non-commercial — fine for research/personal use).

## Requirements

- `mise` (pins JDK 25 — see [mise.toml](mise.toml))
- A C++ toolchain + `make` + **SWIG** (`brew install swig` / `apt-get install swig`)
- `git`, `curl`

## Quickstart

```bash
mise trust && mise install

# 1) build the native JNI binding from source (UDPipe pinned, ~1–2 min)
mise exec -- scripts/build-native.sh
#    -> native/libudpipe_java.<dylib|so>  +  lib/udpipe.jar

# 2) fetch a model (UD 2.5)
mise exec -- scripts/fetch-model.sh slovak-snk      # -> models/slovak-snk.udpipe
# mise exec -- scripts/fetch-model.sh czech-pdt

# 3) smoke test: load model, tokenize, tag, lemmatize
mise exec -- scripts/smoke-test.sh
```

Expected (Slovak):

```
# Bratislava je krásne mesto
    Bratislava   -> bratislava  [NOUN]
    je           -> byť         [AUX]      <- POS disambiguated
    krásne       -> krásny      [ADJ]
    mesto        -> mesto       [NOUN]
```

## Layout

```
scripts/build-native.sh   build libudpipe_java + udpipe.jar from pinned UDPipe source
scripts/fetch-model.sh    download a UD-2.5 model into models/
scripts/smoke-test.sh     compile + run examples/SmokeTest.java
examples/SmokeTest.java   minimal load->tokenize->tag->lemmatize demo
native/  lib/  models/    build outputs (gitignored; reproduce with the scripts)
docs/BUILD.md             native build details + gotchas
docs/COMPARISON.md        full lemmatizer evaluation (jLemmaGen / OpenNLP / dict / UDPipe)
```

Built artifacts are **gitignored** — they are reproduced by the scripts, not committed.

## Roadmap

- [x] Build the UDPipe JNI binding from source (macOS arm64 / JDK 25)
- [x] Slovak model + smoke test (load → tokenize → tag → lemmatize)
- [ ] OpenSearch token-filter plugin wrapping UDPipe (load native lib in plugin sandbox; map Lucene token stream ↔ UDPipe pipeline)
- [ ] Linux x86_64/arm64 native libs for cluster deployment
- [ ] Throughput benchmark vs jLemmaGen/OpenNLP

## License

- This repo's code/scripts: see `LICENSE` (TBD).
- UDPipe library: **MPL 2.0**.
- UDPipe / UD 2.5 models: **CC BY-NC-SA** (non-commercial). Verify before any commercial use.
