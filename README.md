# LocalAI - On-Device Small Language Model Package for Automation Anywhere

![Java 11+](https://img.shields.io/badge/Java-11%2B-informational)
![Automation Anywhere](https://img.shields.io/badge/Automation%20Anywhere-A360-blue)
![llama.cpp](https://img.shields.io/badge/llama.cpp-b8648-green)
![Version](https://img.shields.io/badge/version-2.11.0-orange)

Run AI inference **entirely on-device** with no cloud APIs, no GPU, and no internet required at runtime. This Automation Anywhere package brings Small Language Models (SLMs) directly into your bot workflows using [llama.cpp](https://github.com/ggerganov/llama.cpp) and quantized GGUF models.

## Why On-Device AI?

- **Data stays local** - Sensitive data never leaves the machine. No API keys, no cloud endpoints.
- **No GPU required** - Runs on standard CPUs (Intel x64, Apple Silicon ARM64).
- **Zero runtime cost** - No per-token billing or API rate limits.
- **Offline capable** - After initial model download, works completely offline.
- **Cross-platform** - Windows, macOS (Intel + Apple Silicon), and Linux.

## Supported Models

| Model | Parameters | Size | Context | Best For |
|-------|-----------|------|---------|----------|
| `qwen2.5-0.5b` | 0.5B | ~400MB | 128K | Fast classification, simple tasks |
| `qwen2.5-1.5b` | 1.5B | ~900MB | 128K | General purpose (default) |
| `qwen2.5-3b` | 3B | ~1.8GB | 128K | Higher quality text generation |
| `gemma4-e2b` | 5.1B | ~3.1GB | 128K | Best quality, reasoning tasks |
| `deepseek-r1-1.5b` | 1.5B | ~1.1GB | 128K | Chain-of-thought reasoning |

Models are automatically downloaded from HuggingFace on first use and cached at `~/.aa-slm-models/`.

> Gemma 4 support requires a [custom java-llama.cpp fork](https://github.com/micahman33/java-llama.cpp) built against llama.cpp b8648.

## Actions

### Prompt

Send any text prompt to a local SLM and get a response. Use this for question answering, summarization, extraction, rewriting, or any general-purpose text task.

**Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| Prompt Text | Text Area | *required* | Your instruction or question |
| Model | Select | `qwen2.5-1.5b` | Which model to use |
| Timeout (seconds) | Number | 30 | Max generation time |
| Temperature | Number | 0.3 | Randomness (0.0 = deterministic, 1.0 = creative) |

**Returns:** String - the model's response.

---

### Classify Text

Categorize text into one of your predefined categories. Useful for routing tickets, tagging content, or sentiment analysis.

**Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| Input Text | Text Area | *required* | Text to classify |
| Categories | Text | *required* | Comma-separated list (e.g., `positive, negative, neutral`) |
| Model | Select | `qwen2.5-1.5b` | Which model to use |
| Include Confidence | Checkbox | false | Append confidence score (0-1) |
| Include Explanation | Checkbox | false | Append brief explanation |
| Timeout (seconds) | Number | 30 | Max generation time |

**Returns:** String - format: `category` or `category|0.95|explanation` when options enabled.

---

### Transform to JSON

Convert CSV, TSV, key-value pairs, tables, or lists into valid JSON.

**Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| Input Text | Text Area | *required* | Data to convert |
| Input Format | Select | `auto-detect` | csv, tsv, key-value, table, list, auto-detect |
| Output Style | Select | `compact` | compact or pretty |
| Output Type | Select | `array` | object `{}` or array `[{}]` |
| Model | Select | `qwen2.5-1.5b` | Which model to use |
| Timeout (seconds) | Number | 30 | Max generation time |

**Returns:** String - validated JSON output.

---

### Normalize and Standardize

Clean up inconsistent data formats - dates, phone numbers, addresses, names, emails.

**Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| Input Text | Text Area | *required* | Data to normalize |
| Data Type | Select | `auto-detect` | phone, date, address, name, email, auto-detect, custom |
| Output Format | Text | *(varies)* | Target format (e.g., `YYYY-MM-DD`) |
| Model | Select | `qwen2.5-1.5b` | Which model to use |
| Preserve Original on Failure | Checkbox | true | Return input if normalization fails |
| Timeout (seconds) | Number | 30 | Max generation time |

**Returns:** String - normalized/standardized text.

---

### Sanitize JSON

Make arbitrary text safe for embedding in JSON strings. Uses model-based intelligent sanitization with a rule-based fallback.

**Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| Input Text | Text | *required* | Text to sanitize |
| Model | Select | `qwen2.5-1.5b` | Which model to use |
| Timeout (seconds) | Number | 30 | Max generation time |

**Returns:** String - JSON-safe text with properly escaped characters.

---

### Validate Device

Download and validate a model before using it. Run this once per model to ensure the GGUF file is available locally.

**Parameters:**
| Parameter | Type | Description |
|-----------|------|-------------|
| Model | Select | Which model to download/validate |

**Returns:** Boolean - `true` if the model is ready for inference.

---

### Delete Model

Remove a downloaded model from disk to free space.

**Parameters:**
| Parameter | Type | Description |
|-----------|------|-------------|
| Model | Select | Which model to delete |

**Returns:** Boolean - `true` if the model was deleted (or didn't exist).

## Building from Source

### Prerequisites

- Java 11+
- Gradle (wrapper included)

### Build the package

```shell
./gradlew clean build shadowJar
```

The output JAR is at `build/libs/LocalAI-2.11.0.jar`.

### Run tests

```shell
./gradlew test
```

Tests will automatically download models on first run (~1-4GB depending on model).

## Architecture

```
src/main/java/com/automationanywhere/botcommand/
├── Prompt.java                  # General text generation action
├── ClassifyText.java            # Text classification action
├── TransformToJSON.java         # Format-to-JSON conversion action
├── NormalizeAndStandardize.java # Data normalization action
├── SanitizeJSON.java            # JSON text sanitization action
├── ValidateDevice.java          # Model download/validation action
├── DeleteModel.java             # Model cleanup action
└── utils/
    ├── ModelManager.java        # Singleton model lifecycle & registry
    ├── LlamaInference.java      # llama.cpp inference wrapper
    └── ModelDownloader.java     # HuggingFace model downloader
```

**ModelManager** handles the full model lifecycle - downloading from HuggingFace, caching to disk, loading into memory, and cleanup. Models are loaded lazily on first use and cached in a thread-safe `ConcurrentHashMap`.

**LlamaInference** wraps the llama.cpp Java bindings with chat template formatting (ChatML for Qwen/DeepSeek, Gemma 4 format for Gemma 4, raw for others), timeout management via `ExecutorService`, and thinking-block stripping for reasoning models like DeepSeek R1.

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| [java-llama.cpp](https://github.com/micahman33/java-llama.cpp) | 4.2.0 | llama.cpp JNI bindings (custom fork, llama.cpp b8648) |
| [OkHttp](https://square.github.io/okhttp/) | 4.12.0 | Model downloads from HuggingFace |
| [Gson](https://github.com/google/gson) | 2.10.1 | JSON validation and formatting |
| Automation Anywhere SDK | 1.6.0 | Package SDK for A360 |

## License

Proprietary - Automation Anywhere
