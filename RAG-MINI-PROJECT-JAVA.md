# RAG Mini-Project (Java) — "Interview Guide Chatbot"
**Goal:** 5–6 hrs (Week 5) to build a real RAG system in **Java + Spring Boot + LangChain4j**.

> Java equivalent of the Python project. Uses **LangChain4j** (the Java-native LLM framework), **Spring Boot** (your stack), and your choice of vector store. Tailored for a 15-yr Java dev.

---

## Why Java Here?

You think in Spring Boot. LangChain4j gives you the same RAG primitives (embeddings, vector stores, chat models) with idiomatic Java APIs, Spring Boot starters, and type safety. **Talking point:** *"I built a production-pattern RAG service in Spring Boot using LangChain4j — same architecture I'd ship at work."*

---

## Architecture

```
                    ┌─────────────────────┐
                    │  Interview Guides   │
                    │  (your .md files)   │
                    └──────────┬──────────┘
                               │
                  ┌────────────▼────────────┐
                  │ DocumentSplitter         │  (LangChain4j)
                  │ 500-token, 50 overlap    │
                  └────────────┬────────────┘
                               │
                  ┌────────────▼────────────┐
                  │ EmbeddingModel           │  AllMiniLmL6V2 (local, in-process)
                  │ → 384-dim float[]        │  OR Azure OpenAI embeddings
                  └────────────┬────────────┘
                               │
                  ┌────────────▼────────────┐
                  │ EmbeddingStore           │  InMemory / PgVector / Redis
                  └────────────┬────────────┘
                               │
  REST: POST /ask {"question": "Design a chat system"}
                               │
        ┌──────────────────────┼──────────────────────┐
        │ 1. embed(query)       │                      │
        │ 2. store.findRelevant(top 3)                 │
        │ 3. ChatModel.chat(context + question)        │
        └──────────────────────┼──────────────────────┘
                               │
                    ┌──────────▼──────────┐
                    │ Azure OpenAI / Claude│
                    │ via LangChain4j      │
                    └──────────────────────┘
```

---

## Stack Choice

### Option A: Spring Boot + LangChain4j + In-Memory store (Recommended, 3–4 hrs)
- **Spring Boot 3.2+**, **Java 21**
- **LangChain4j** with the `spring-boot-starter`
- **In-memory embedding store** (zero infra)
- **Local embedding model** (`all-minilm-l6-v2`, runs in-process, no API)
- **Azure OpenAI** OR **Anthropic Claude** as the chat model

✅ **Start here.** No external DB, no embedding API cost. Only the chat LLM needs a key.

### Option B: Spring Boot + LangChain4j + PgVector (Production-like, +1 hr)
- Same as A, but swap the store for **PostgreSQL + pgvector** (you already run Postgres)
- Persistent embeddings, survives restarts, realistic

✅ **Do this in a second pass** to show "I used my actual Postgres stack as a vector DB."

### Option C: Spring Boot + LangChain4j + Redis (Your stack, alternative)
- Redis as vector store (`redis-stack` with RediSearch)
- You already run Redis — credible talking point

---

## Step 0: Prerequisites (15 min)

```bash
java -version    # Need 17+ (21 recommended)
mvn -version     # Need 3.8+

# Get a chat model key — pick one:
# Azure OpenAI:
export AZURE_OPENAI_ENDPOINT="https://<your-resource>.openai.azure.com"
export AZURE_OPENAI_KEY="<key>"
# OR Anthropic:
export ANTHROPIC_API_KEY="sk-ant-..."
```

---

## Step 1: Project Setup (30 min)

```bash
# Scaffold via Spring Initializr (web, or CLI)
curl https://start.spring.io/starter.zip \
  -d dependencies=web \
  -d type=maven-project \
  -d javaVersion=21 \
  -d bootVersion=3.3.0 \
  -d groupId=com.prep \
  -d artifactId=rag-chatbot \
  -d name=rag-chatbot \
  -d packageName=com.prep.rag \
  -o rag-chatbot.zip
unzip rag-chatbot.zip -d rag-chatbot
cd rag-chatbot
```

### `pom.xml` — add LangChain4j dependencies

```xml
<properties>
    <java.version>21</java.version>
    <langchain4j.version>0.34.0</langchain4j.version>
</properties>

<dependencies>
    <!-- Spring Web (already present) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- LangChain4j core + Spring Boot starter -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j</artifactId>
        <version>${langchain4j.version}</version>
    </dependency>

    <!-- Local embedding model (in-process, no API) -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-embeddings-all-minilm-l6-v2</artifactId>
        <version>${langchain4j.version}</version>
    </dependency>

    <!-- Chat model: pick ONE -->
    <!-- (A) Azure OpenAI -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-azure-open-ai</artifactId>
        <version>${langchain4j.version}</version>
    </dependency>
    <!-- (B) Anthropic Claude -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-anthropic</artifactId>
        <version>${langchain4j.version}</version>
    </dependency>

    <!-- Optional Option B: PgVector store -->
    <!--
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-pgvector</artifactId>
        <version>${langchain4j.version}</version>
    </dependency>
    -->
</dependencies>
```

---

## Step 2: Ingestion + Indexing (60 min)

Create `src/main/java/com/prep/rag/RagConfig.java`:

```java
package com.prep.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static dev.langchain4j.data.document.loader.FileSystemDocumentLoader.loadDocument;

@Configuration
public class RagConfig {

    // Local, in-process embedding model (384-dim). No API key, no cost.
    @Bean
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }

    // In-memory store (swap for PgVectorEmbeddingStore in Option B)
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    // On startup: load .md files, chunk, embed, index
    @Bean
    public EmbeddingStoreIngestor ingestor(EmbeddingModel embeddingModel,
                                           EmbeddingStore<TextSegment> embeddingStore) throws Exception {
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(500, 50)) // chunk size, overlap
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        // Point this at a folder of your interview guides
        Path docsDir = Path.of(System.getProperty("user.dir"), "docs-for-rag");
        try (Stream<Path> paths = Files.walk(docsDir)) {
            List<Document> docs = paths
                    .filter(p -> p.toString().endsWith(".md"))
                    .map(p -> loadDocument(p, new TextDocumentParser()))
                    .toList();
            ingestor.ingest(docs);
            System.out.println("Ingested " + docs.size() + " documents");
        }
        return ingestor;
    }
}
```

Copy a few guides to ingest:

```bash
mkdir docs-for-rag
cp ../09-system-design/fundamentals.md docs-for-rag/
cp ../09-system-design/design-problems/chat-system.md docs-for-rag/
cp ../13-ai-ml/rag-systems.md docs-for-rag/
cp ../03-messaging/kafka.md docs-for-rag/
```

---

## Step 3: The Assistant + REST endpoint (60 min)

LangChain4j's **AI Services** wire retrieval into a typed interface. Create `AssistantConfig.java`:

```java
package com.prep.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AssistantConfig {

    // Typed assistant interface — LangChain4j implements it at runtime
    public interface InterviewAssistant {
        String answer(String question);
    }

    @Bean
    public ChatLanguageModel chatModel(@Value("${ANTHROPIC_API_KEY}") String apiKey) {
        return AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName("claude-3-5-sonnet-20241022")
                .temperature(0.3)
                .build();
        // For Azure OpenAI, use AzureOpenAiChatModel.builder()... instead.
    }

    @Bean
    public ContentRetriever contentRetriever(EmbeddingStore<TextSegment> store,
                                             EmbeddingModel embeddingModel) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .maxResults(3)          // top-3 chunks
                .minScore(0.6)          // filter weak matches
                .build();
    }

    @Bean
    public InterviewAssistant assistant(ChatLanguageModel chatModel,
                                        ContentRetriever contentRetriever) {
        return AiServices.builder(InterviewAssistant.class)
                .chatLanguageModel(chatModel)
                .contentRetriever(contentRetriever)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();
    }
}
```

Create the REST controller `AskController.java`:

```java
package com.prep.rag;

import com.prep.rag.AssistantConfig.InterviewAssistant;
import org.springframework.web.bind.annotation.*;

@RestController
public class AskController {

    private final InterviewAssistant assistant;

    public AskController(InterviewAssistant assistant) {
        this.assistant = assistant;
    }

    public record AskRequest(String question) {}
    public record AskResponse(String answer) {}

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        return new AskResponse(assistant.answer(request.question()));
    }
}
```

That's it. LangChain4j auto-injects retrieved context into the prompt before calling the LLM — no manual prompt stitching.

---

## Step 4: Run & Test (90 min)

```bash
mvn spring-boot:run
```

```bash
# Test queries
curl -s -X POST localhost:8080/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"How do I design a chat system?"}' | jq

curl -s -X POST localhost:8080/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"Explain Kafka exactly-once semantics"}' | jq

# Ambiguous — should retrieve weakly / say it doesn't know
curl -s -X POST localhost:8080/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"What is the best pizza topping?"}' | jq
```

### Iterate (the real learning)

| Lever | Where | Effect |
|---|---|---|
| Chunk size | `DocumentSplitters.recursive(500, 50)` | Smaller = precise but fragmented |
| `maxResults` | `EmbeddingStoreContentRetriever` | More chunks = more context, more tokens |
| `minScore` | retriever | Higher = stricter, fewer hallucinations from junk |
| Add more docs | `docs-for-rag/` | Broader coverage |
| Custom prompt | `@SystemMessage` on the interface | Force citations, format, tone |

Force citations with a system prompt:

```java
public interface InterviewAssistant {
    @dev.langchain4j.service.SystemMessage("""
        You are a senior tech interviewer. Answer ONLY from the provided context.
        Cite which guide each fact comes from. If context is insufficient, say so.
        Keep answers to 2-3 paragraphs.
        """)
    String answer(String question);
}
```

---

## Step 5 (Option B): Swap to PgVector — your real stack (60 min)

Run Postgres with pgvector:

```bash
docker run -d --name pgvector -p 5432:5432 \
  -e POSTGRES_PASSWORD=postgres pgvector/pgvector:pg16
```

Replace the store bean:

```java
@Bean
public EmbeddingStore<TextSegment> embeddingStore() {
    return PgVectorEmbeddingStore.builder()
            .host("localhost").port(5432)
            .database("postgres").user("postgres").password("postgres")
            .table("interview_embeddings")
            .dimension(384)              // matches all-minilm-l6-v2
            .createTable(true)
            .build();
}
```

**Talking point:** *"I used PostgreSQL with the pgvector extension as my vector store — same database engine I run in production, so no new infra to operate."*

---

## What You'll Learn (Java lens)

| Concept | Where | Insight |
|---|---|---|
| Embeddings | `AllMiniLmL6V2EmbeddingModel` | 384-dim vectors, ONNX runs in-process — no GPU, no API |
| Chunking | `DocumentSplitters.recursive` | Overlap preserves context across boundaries |
| Vector search | `EmbeddingStoreContentRetriever` | Cosine similarity, top-k, score threshold |
| RAG wiring | `AiServices` | Retrieval injected before LLM call — declarative |
| Memory | `MessageWindowChatMemory` | Multi-turn conversations |
| Production store | `PgVectorEmbeddingStore` | Postgres doubles as a vector DB |

---

## Deliverables (Week 5)

1. ✅ Spring Boot app with `POST /ask`
2. ✅ `docs-for-rag/` ingested at startup
3. ✅ Tested with 5+ queries, tuned chunk size / minScore
4. ✅ (Stretch) Swapped to PgVector
5. ✅ Reflection (3 bullets): *biggest bottleneck? how to scale to 10K q/day? where does it hallucinate?*

---

## Interview Talking Point

> *"I built a RAG service in Spring Boot with LangChain4j. Markdown docs are chunked into 500-token overlapping segments, embedded in-process with all-MiniLM-L6-v2, and stored in pgvector on Postgres. On a query, I embed it, pull the top-3 segments above a 0.6 cosine score, and LangChain4j injects them into the prompt before calling Claude. I learned chunk overlap is critical for coherence and that a score threshold is the cheapest hallucination guard. To scale to 10K queries/day I'd add an embedding cache in Redis and batch the ingestion pipeline."*

That's a senior Java engineer who *understands* GenAI, not just a reader.

---

## FAQ

**Q: Do I need a GPU for embeddings?** No — `all-minilm-l6-v2` is ONNX, runs on CPU in-process.
**Q: Azure OpenAI instead of Claude?** Swap the `ChatLanguageModel` bean for `AzureOpenAiChatModel.builder()...`. Everything else stays.
**Q: Spring AI instead of LangChain4j?** Also valid (Spring's own LLM abstraction). LangChain4j has more vector-store integrations today; Spring AI is more "Spring-native." Either is a good talking point.
**Q: No API key at all?** Use Ollama locally: `langchain4j-ollama` + `ollama pull mistral`. Fully offline.

---

**Build it. 5–6 hours. Ship by end of Week 5.**
