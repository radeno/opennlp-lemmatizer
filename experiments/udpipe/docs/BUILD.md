# Native build notes

`scripts/build-native.sh` automates everything here. This doc explains the *why* so the
script is maintainable and the gotchas don't have to be rediscovered.

## What we build and why from source

UDPipe (the C++ morphological tagger/lemmatizer) talks to Java through a **SWIG-generated
JNI binding**: a native library (`libudpipe_java.dylib` / `.so`) plus matching Java wrapper
classes (`udpipe.jar`).

We compile both from source because:

1. The only Maven artifact, `cz.cuni.mff.ufal.udpipe:udpipe:1.1.0`, contains **only the Java
   classes — no native library**. Useless on its own.
2. ÚFAL's prebuilt binary packages predate Apple Silicon → **no macOS arm64** build exists.
   A JNI library must match the JVM architecture, so an x86_64 `.dylib` will not load into an
   arm64 JVM.

So for arm64 (and to keep the Java classes version-matched to the native lib) we build locally.
Pinned to **UDPipe v1.3.1** (latest 1.x; 2.x is a separate Python/TensorFlow tool with no C++/JNI).

## Gotchas (all handled by the script)

- **`javac -source 7` fails on JDK 25.** UDPipe 1.3.1's `bindings/java/Makefile` hardcodes
  `javac -source 7`; JDK 9+ removed source level 7. The script patches it to `-source 8`.
  Symptom: `error: Source option 7 is no longer supported. Use 8 or later.`
- **SWIG is required** to generate the JNI wrapper (the repo ships no pre-generated `.cpp`).
  `brew install swig` / `apt-get install swig`. (Tested with SWIG 4.4.1.)
- **arm64 is automatic.** UDPipe's `Makefile.common` detects the platform and clang targets
  `arm64-apple-macos11` natively — no `-arch` override needed. Verify with
  `file native/libudpipe_java.dylib` → `Mach-O 64-bit dynamically linked shared library arm64`.
- **JNI headers.** The Makefile needs `JAVA_HOME` with `include/jni.h` (+ `include/darwin`).
  Run the script through `mise exec --` so `JAVA_HOME` points at the project JDK 25.
- **Runtime native-access warning on JDK 25.** Loading a JNI lib prints
  *"WARNING: A restricted method in java.lang.System has been called …"*. Harmless; silence it
  with `--enable-native-access=ALL-UNNAMED` (smoke-test.sh already passes it).

## Models

- Models are downloaded by `scripts/fetch-model.sh` from the **jwijffels/udpipe.models.ud.2.5**
  GitHub mirror (raw, stable URLs), tag `udpipe-ud-2.5-191206`.
- The canonical source is **LINDAT** (`handle/11234/1-3131`), but it is now a DSpace-7 Angular
  SPA — the file list is rendered by JavaScript and there are no stable static download links,
  so scraping it does not work. The mirror is what the R `udpipe` package uses.
- Models are **CC BY-NC-SA** (non-commercial).

## Cross-platform / deployment

The artifacts produced are for the **host** OS/arch only. For an OpenSearch cluster you also
need the matching native lib:

- Linux x86_64 / arm64: run `scripts/build-native.sh` on that platform (or in a matching
  container) and ship the resulting `libudpipe_java.so`.
- The OpenSearch plugin will need to bundle the right native lib per target and load it
  (precedent: the k-NN plugin ships native libs).
