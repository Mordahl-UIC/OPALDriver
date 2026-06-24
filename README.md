# OPALDriver

A command-line driver that computes a **call graph** for a JAR using
[OPAL](https://www.opal-project.de/). The output is a JSON file that records,
for every reachable method, its **context string** plus the per-call-site
callees (each with their own context string) — not just bare caller/callee
relationships.

## Usage

```
opaldriver --input <jar> --output <cg.json> --algorithm <cha|rta|cfa> [options]
```

Run via sbt:

```bash
sbt 'run --input app.jar --output cg.json --algorithm rta'
```

### Options

| Option              | Description |
|---------------------|-------------|
| `--input` (req.)    | JAR file (or directory of `.class` files) to analyze. |
| `--output` (req.)   | Path to write the call graph JSON to (parent dirs are created). |
| `--algorithm` (req.)| `cha`, `rta`, or `cfa` (k-CFA). |
| `--k`               | k-CFA only: call-string length `k` (default `1`). |
| `--l`               | k-CFA only: heap/allocation context depth `l` (default `= k`). |
| `--entrypoints`     | `application` (default), `application-without-jre`, `library`, `all`, `configured`. |
| `--no-jdk`          | Do not load the JRE as a library (faster, but less sound). |

### Algorithms

- **CHA** — Class Hierarchy Analysis (`CHACallGraphKey`).
- **RTA** — Rapid Type Analysis (`RTACallGraphKey`).
- **k-CFA** — call-string-sensitive, allocation-site points-to
  (`CFA_k_l_TypeIterator`). `k` is the call-string length; `l` is the heap
  context depth. OPAL ships ready-made keys only for `k = 1`; this driver
  builds a custom `PointsToCallGraphKey` so any `k`/`l` can be requested.

> **Not supported:** OPAL 7.0.0 has no VTA and no object-sensitive method
> contexts, so those were intentionally omitted. The only context-sensitive
> method context OPAL provides is the call string (`CallStringContext`); CHA/RTA
> use the context-insensitive `SimpleContext`.

### Entry points

Selects OPAL's `EntryPointFinder` via
`org.opalj.br.analyses.cg.InitialEntryPointsKey.analysis`:

- `application` — `main` methods (`ApplicationEntryPointsFinder`).
- `application-without-jre` — like `application`, excluding JRE internals.
- `library` — all accessible (public/protected) methods.
- `all` — every project method (`projectMethodsOnly = true`).
- `configured` — only entry points listed in configuration.

## Output format

```json
{
  "algorithm": "cfa",
  "k": 2,
  "l": 2,
  "entrypoints": "application",
  "reachableMethods": [
    {
      "context": "CallStringContext(DefinedMethod(...), List(...))",
      "method": "app.Demo.main([Ljava/lang/String;)V",
      "callSites": [
        {
          "pc": 9,
          "callees": [
            { "context": "...", "method": "app.Demo$Hello.<init>()V" }
          ]
        }
      ]
    }
  ]
}
```

The `context` strings come from OPAL's `Context.toString`: `SimpleContext(...)`
for CHA/RTA, and `CallStringContext(...)` (the call string) for k-CFA. Edges are
extracted context-sensitively via
`callGraph.calleesPropertyOf(method).callSites(callerContext, ...)`.

## Notes

- **Scala version is pinned to 3.7.3** (see `build.sbt`). OPAL 7.0.0 was built
  with Scala 3.7.3 and resolves its analysis schedulers at runtime via
  scala-reflect 2.13.16, which requires the matching scala-library 2.13.16.
  Newer Scala 3 ships an incompatible unified standard library that breaks
  OPAL's runtime reflection.
- With the points-to (`cfa`) algorithms, OPAL logs a benign
  `Cannot find object ...ReflectionAllocationsAnalysisScheduler...` error and
  skips that one optional analysis; the call graph is still produced. This is
  internal to OPAL's allocation-site points-to keys (the shipped `CFA_1_1` key
  behaves identically) and does not affect the driver's output.
