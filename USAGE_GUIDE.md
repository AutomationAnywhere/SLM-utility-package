# Local AI - Usage Guide

## Overview

This package provides on-device AI inference for Automation Anywhere bots using small language models (SLMs) via llama.cpp and GGUF quantized models.

## Current Actions

### 1. Validate Device

**Purpose**: Download or validate that a Small Language Model is available on the device.

**When to use**:
- Before first use of any SLM action
- To pre-download models for offline use
- To verify model integrity after updates

**How it works**:
1. Checks if model exists locally
2. Downloads from HuggingFace if needed (~669MB-5GB)
3. Validates file completeness
4. Returns true if device is ready

**Model storage locations**:
- **Windows**: `C:\Users\{username}\.aa-slm-models\`
- **macOS**: `/Users/{username}/.aa-slm-models/`

**Parameters**:
- **Model**: Choose from TinyLlama (669MB), Gemma 2B (1.7GB), or Qwen2.5 3B (2.1GB)

**Returns**: Boolean (true if model ready)

**First run**: 5-15 minutes for download depending on internet speed

---

### 2. Delete Model

**Purpose**: Delete a downloaded model from local storage to free disk space.

**When to use**:
- Free up disk space (669MB-5GB per model)
- Remove unused models
- Clean up after testing

**How it works**:
1. Checks if model exists
2. Unloads from memory if currently loaded
3. Deletes model file
4. Cleans up empty directories
5. Reports freed space

**Parameters**:
- **Model**: Choose from TinyLlama (669MB), Gemma 2B (1.7GB), or Qwen2.5 3B (2.1GB)

**Returns**: Boolean (true if deleted or didn't exist)

**Note**: Model can be re-downloaded anytime with Validate Device action

---

### 3. SanitizeJSON

**Purpose**: Sanitizes text for JSON compatibility by escaping special characters.

**When to use**:
- Preparing user input for JSON payloads
- Cleaning error messages before logging to JSON
- Sanitizing API responses before JSON serialization

**How it works**:
1. Attempts to use the selected SLM to intelligently sanitize text
2. Falls back to rule-based escaping if model fails or times out
3. Always returns valid JSON-safe text

**Example usage**:
```
Input:  API Error: "Connection timeout" at line 42
        Stack trace:
            at com.example.Main

Output: API Error: \"Connection timeout\" at line 42\nStack trace:\n\tat com.example.Main
```

**Parameters**:
- **Input Text**: Text to sanitize
- **Model**: Choose from TinyLlama (669MB), Gemma 2B (1.7GB), or Qwen2.5 3B (2.1GB)
- **Timeout**: Maximum wait time in seconds (default: 30)

**First run**: Model will be downloaded automatically (~669MB for TinyLlama)

**Performance**:
- First call: 30-60 seconds (includes model download and loading)
- Subsequent calls: 1-3 seconds (model stays in memory)

---

### 4. Prompt Model

**Purpose**: Send custom prompts to a Small Language Model for general-purpose text generation.

**When to use**:
- Question answering
- Text summarization
- Data extraction
- Format conversion
- Creative writing
- Any custom AI task

**How it works**:
1. Sends your custom prompt to the selected SLM
2. Generates response based on the prompt (model-specific token limits)
3. Returns cleaned text output (automatically removes markdown code fences)

**Example usage**:
```
Input Prompt: "Q: What is the capital of France? A:"
Model: TinyLlama 1.1B (max 1024 tokens)
Temperature: 0.3

Output: Paris
```

**Parameters**:
- **Prompt**: Your instruction or question for the model
- **Model**: Choose from:
  - TinyLlama 1.1B (669MB, max 1024 tokens)
  - Gemma 2B (1.7GB, max 4096 tokens)
  - Qwen2.5 3B (2.1GB, max 4096 tokens, 128K context)
- **Timeout**: Maximum wait time in seconds (default: 30)
- **Temperature**: Controls creativity (0.0 = focused, 1.0 = creative, default: 0.3)

**Max Output Tokens**:
- TinyLlama: 1,024 tokens (~750 words)
- Gemma 2B: 4,096 tokens (~3,000 words)
- Qwen2.5 3B: 4,096 tokens (~3,000 words)

**Performance**:
- First call: 30-60 seconds (includes model download and loading)
- Subsequent calls: 1-5 seconds (model stays in memory)

---

### 5. ClassifyText

**Purpose**: Categorize text into predefined categories using AI.

**When to use**:
- Email triage (urgent, normal, spam)
- Document routing (invoice, receipt, contract)
- Sentiment analysis (positive, negative, neutral)
- Priority classification (high, medium, low)

**How it works**:
1. Analyzes input text against provided categories
2. Determines best matching category
3. Optionally includes confidence score and explanation

**Example usage**:
```
Input: "URGENT: Production server down! Customers cannot access site."
Categories: "urgent, normal, low_priority"
Include Confidence: Yes
Include Explanation: Yes

Output: urgent|0.95|Contains URGENT keyword and mentions production issue
```

**Parameters**:
- **Input Text**: Text to classify
- **Categories**: Comma-separated list of categories
- **Model**: Choose from TinyLlama (669MB), Gemma 2B (1.7GB), or Qwen2.5 3B (2.1GB)
- **Include Confidence Score**: Add confidence (0.0-1.0) to output
- **Include Explanation**: Add brief reason for classification
- **Timeout**: Maximum wait time in seconds (default: 30)

**Output Format**:
- Simple: `category`
- With confidence: `category|0.95`
- With explanation: `category|0.95|brief explanation`

**Performance**:
- First call: 30-60 seconds (includes model download and loading)
- Subsequent calls: 1-5 seconds (model stays in memory)

---

### 6. NormalizeAndStandardize

**Purpose**: Clean and standardize inconsistent data formats intelligently.

**When to use**:
- Normalize phone numbers: "(555) 123-4567" → "5551234567"
- Standardize dates: "March 15, 2024" → "2024-03-15"
- Format addresses: Various formats → standard format
- Clean names: "SMITH, JOHN Q." → "John Q. Smith"
- Standardize emails: "User@DOMAIN.COM" → "user@domain.com"

**How it works**:
1. Detects or uses specified data type
2. Applies intelligent normalization rules
3. Formats to specified output format
4. Falls back to original if normalization fails (optional)

**Example usage**:
```
Input: "(555) 123-4567"
Data Type: Phone Number
Output Format: "digits only"

Output: 5551234567
```

**Parameters**:
- **Input Text**: Text to normalize/standardize
- **Data Type**: phone, date, address, name, email, auto-detect, or custom
- **Output Format**: Target format (e.g., "YYYY-MM-DD", "E.164", "digits only")
- **Model**: Choose from TinyLlama (669MB), Gemma 2B (1.7GB), or Qwen2.5 3B (2.1GB)
- **Preserve Original On Failure**: Return original text if normalization fails (default: true)
- **Timeout**: Maximum wait time in seconds (default: 30)

**Performance**:
- First call: 30-60 seconds (includes model download and loading)
- Subsequent calls: 1-5 seconds (model stays in memory)

---

### 7. TransformToJSON

**Purpose**: Convert various text formats into valid JSON strings.

**When to use**:
- CSV data → JSON array for REST APIs
- Key-value text → JSON object for logging
- Table data → JSON for database operations
- Unstructured text → structured JSON

**How it works**:
1. Analyzes input format (CSV, TSV, Key-Value, etc.)
2. Converts to JSON structure (object or array)
3. Validates JSON syntax
4. Formats as compact or pretty-printed

**Example usage**:
```
Input:
Name,Age,City
John,30,NYC
Jane,25,LA

Input Format: CSV
Output Style: compact
Output Type: array

Output: [{"Name":"John","Age":"30","City":"NYC"},{"Name":"Jane","Age":"25","City":"LA"}]
```

**Parameters**:
- **Input Text**: Text to transform into JSON
- **Input Format**: CSV, TSV, Key-Value Pairs, Table, List, or Auto-detect
- **Output Style**: compact (no whitespace) or pretty (formatted)
- **Output Type**: object (single {}) or array (multiple [{}])
- **Model**: Choose from TinyLlama (669MB), Gemma 2B (1.7GB), or Qwen2.5 3B (2.1GB)
- **Timeout**: Maximum wait time in seconds (default: 30)

**Output**: Valid JSON string (syntax validated)

**Performance**:
- First call: 30-60 seconds (includes model download and loading)
- Subsequent calls: 1-5 seconds (model stays in memory)

---

## Creating Additional Actions

The package is designed to be extensible. Here's how to add new LLM-powered actions:

### Example: Question Answering Action

If you want to create a general-purpose Q&A action:

1. **Create new action class**: `src/main/java/com/automationanywhere/botcommand/AnswerQuestion.java`

2. **Use the `LlamaInference.generateText()` method**:

```java
@BotCommand
@CommandPkg(
    label = "Answer Question",
    name = "answerQuestion",
    description = "Uses on-device SLM to answer questions"
)
public class AnswerQuestion {

    @Execute
    public Value<String> execute(
        @Pkg(label = "Question") String question,
        @Pkg(label = "Model") String modelName,
        @Pkg(label = "Timeout (seconds)") Double timeoutSeconds
    ) {
        ModelManager.ModelType modelType = ModelManager.ModelType.fromId(modelName);
        LlamaInference inference = new LlamaInference(modelType);

        // Format prompt with chat template
        String prompt = String.format(
            "<|system|>\nYou are a helpful assistant.\n" +
            "<|user|>\n%s\n" +
            "<|assistant|>\n",
            question
        );

        // Generate answer with custom parameters
        String answer = inference.generateText(
            prompt,
            150,           // maxTokens
            0.7f,          // temperature (higher = more creative)
            timeoutSeconds.intValue()
        );

        return new StringValue(answer);
    }
}
```

3. **Create corresponding test class**: `src/test/java/com/automationanywhere/botcommand/TestAnswerQuestion.java`

### Other Use Cases

The `LlamaInference.generateText()` method can power:

- **Text summarization**: "Summarize the following text: [text]"
- **Data extraction**: "Extract the email address from: [text]"
- **Format conversion**: "Convert this CSV to JSON: [csv]"
- **Code generation**: "Write a Python function that: [description]"
- **Translation**: "Translate to Spanish: [text]"

---

## Supported Models

| Model | Size | RAM Required | Context Window | Max Output | Best For |
|-------|------|--------------|----------------|------------|----------|
| TinyLlama 1.1B (Q4) | 669MB | 2-3GB | 2,048 tokens | 1,024 tokens | Fast responses, simple tasks |
| Gemma 2B (Q4) | 1.7GB | 4-5GB | 8,192 tokens | 4,096 tokens | Better accuracy, still fast |
| **Qwen2.5 3B (Q4)** | 2.1GB | 5-6GB | **128K tokens** | 4,096 tokens | **Structured data, JSON, tables** |

**Quantization**: All models use Q4_K_M quantization for optimal size/quality balance.

**Token Limits**: Max output tokens leave room for input prompts within the context window.

**Qwen2.5 3B Highlights**:
- **128K context window** - 16x larger than other models, handles massive documents
- **Optimized for structured data** - Specifically trained for JSON generation, table understanding, and data extraction
- **Best for automation** - Ideal for TransformToJSON, SanitizeJSON, and structured output tasks
- **Recommended default** for most data-processing workflows

---

## Platform Support

✅ **Windows** (x64)
✅ **macOS** (Intel x64 and Apple Silicon ARM64)
✅ **Linux** (x64, aarch64)

**No GPU required** - All inference runs on CPU.

---

## Model Storage

**Storage Locations**:
- **Windows**: `C:\Users\{username}\.aa-slm-models\`
- **macOS**: `/Users/{username}/.aa-slm-models/`

```
.aa-slm-models/
├── tinyllama-1.1b-q4/
│   └── tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf (669MB)
├── gemma-2b-q4/
│   └── gemma-2-2b-it-Q4_K_M.gguf (1.7GB)
└── qwen2.5-3b-q4/
    └── qwen2.5-3b-instruct-q4_k_m.gguf (2.1GB)
```

**Management**:
- Use **Validate Device** action to download models
- Use **Delete Model** action to free disk space
- Models persist between bot runs for faster startup

---

## Testing

### Run all tests (fast, no model download):
```bash
./gradlew test
```

### Run full inference tests:

1. Edit `src/test/java/com/automationanywhere/botcommand/TestSanitizeJSON.java`
2. Change `@Test(enabled = false)` to `@Test(enabled = true)` for inference tests
3. Run: `./gradlew test`

**Test report**: `build/reports/tests/test/index.html`

---

## Architecture

```
SanitizeJSON.java (Action)
    ↓
LlamaInference.java (Inference Engine)
    ↓
ModelManager.java (Model Lifecycle)
    ↓
llama.cpp via java-llama.cpp (Native Inference)
```

**Key components**:

- **ModelManager**: Singleton that manages model loading/unloading, caching models in memory
- **LlamaInference**: Wraps llama.cpp for text generation with timeout support
- **ModelDownloader**: Downloads GGUF models from HuggingFace on first use
- **SanitizeJSON**: Example action showing how to use the inference engine

---

## Performance Tips

1. **Model stays loaded**: After first use, model remains in memory for faster subsequent calls
2. **Choose smallest sufficient model**: TinyLlama is often good enough for simple tasks
3. **Adjust timeout**: Complex generations may need 60+ seconds
4. **Temperature control**: Lower (0.1-0.3) for consistent output, higher (0.7-1.0) for creativity

---

## Limitations

1. **Task-specific actions**: Each action designed for specific use case (use Prompt Model for custom tasks)
2. **Context windows**: TinyLlama (2048 tokens), Gemma models (8192 tokens)
3. **Max output tokens**: Model-dependent (1024-4096 tokens)
4. **No streaming**: Full response generated before returning
5. **CPU only**: No GPU acceleration (by design for compatibility)

---

## Why Not General Q&A in SanitizeJSON?

The issue you encountered ("What's the capital of Florida?" echoing back) happens because:

1. **SanitizeJSON sends a sanitization prompt**: "You are a JSON sanitizer. Remove or escape characters..."
2. **This prompt doesn't ask for Q&A**: The model is told to sanitize, not answer
3. **Result**: Model processes input as text to sanitize, not a question to answer

**Solution**: Create separate actions for different tasks, each with appropriate prompts.

---

## Build and Deploy

```bash
# Build package
./gradlew clean build shadowJar

# Output
build/libs/LocalAI-2.11.0-*.jar
```

Deploy this JAR to Automation Anywhere Control Room as a custom package.

---

## Support

For issues related to:
- **llama.cpp**: https://github.com/ggerganov/llama.cpp
- **java-llama.cpp**: https://github.com/kherud/java-llama.cpp
- **TinyLlama model**: https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF
- **Gemma models**: https://huggingface.co/bartowski
