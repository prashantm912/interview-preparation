# RAG Mini-Project — "Interview Guide Chatbot"
**Goal:** 4–5 hrs (Week 5) to build and ship a toy but *real* RAG system.

---

## Why This Project?

By Week 5, you'll have read the LLM & RAG guides but not **touched** anything. This 4-hr build bridges theory→practice. You'll:

- Ingest your own markdown docs (interview guides)
- Chunk and embed them with a free model
- Query via an LLM (Claude API or open-source)
- See retrieval fail gracefully when it's ambiguous
- Understand the bottlenecks (retrieval latency, context window limits, hallucinations)

**Talking point after:** *"I built a production-pattern RAG app. I know how embeddings work, why chunking matters, and how to mitigate hallucination."*

---

## Architecture (1 page, no code yet)

```
                    ┌─────────────────────┐
                    │  Interview Guides   │
                    │  (your .md files)   │
                    └──────────┬──────────┘
                               │
                     ┌─────────▼────────┐
                     │   Text Chunking   │  (500-token windows, overlap)
                     └─────────┬────────┘
                               │
         ┌─────────────────────┼────────────────────┐
         │                     │                    │
    ┌────▼────┐         ┌─────▼──────┐      ┌─────▼──────┐
    │ Chunk 1 │         │  Chunk 2   │      │ Chunk N    │
    └────┬────┘         └─────┬──────┘      └─────┬──────┘
         │                     │                    │
         └─────────────────────┼────────────────────┘
                               │
                    ┌──────────▼────────┐
                    │ Embedding Model   │  (sentence-transformers)
                    │ (384-dim vectors) │
                    └──────────┬────────┘
                               │
                    ┌──────────▼──────────┐
                    │  Vector Store       │  (Chroma or Weaviate)
                    │  (in-memory or .db) │
                    └─────────────────────┘

USER QUERY:
    "How do I design a chat system?"
         │
         ├─→ [Embed query]
         │
         ├─→ [Retrieve top-3 chunks from vector store]
         │       → chunk from 09-system-design/chat-system.md
         │       → chunk from 09-system-design/distributed-systems.md
         │       → chunk from 03-messaging/kafka.md
         │
         ├─→ [Prompt LLM with retrieved context + user query]
         │
         └─→ [LLM returns answer with citations]

LLM:  "Based on your guides, chat systems require:
       1. Real-time message delivery (WebSockets or polling)
       2. Ordering guarantees (see distributed-systems.md, CAP theorem)
       3. Persistence via Kafka (see kafka.md, exactly-once semantics)
       
       Here's how you'd design it..."
```

---

## Stack Choice (Pick One)

### Option A: LangChain + Chroma (Easiest, 3 hrs)
- **LangChain:** orchestration (chunking, embedding, retrieval, prompting)
- **Chroma:** vector DB (runs in-memory or local SQLite, no server)
- **Claude API:** LLM (free credits or pay $0.01 per 1K input tokens)
- **Python 3.10+**

```bash
pip install langchain langchain-community langchain-anthropic chroma-db

# You write: 60-80 lines of Python
```

✅ **Recommended for you.** Fastest to working. Requires API key (`sk-ant-...`), but free Claude credits exist.

### Option B: LLamaIndex + Chroma (Also good, 3–4 hrs)
- **LLamaIndex:** simpler API, opinionated RAG patterns
- **Chroma:** same vector DB
- **Open-source LLM:** Ollama (run Mistral 7B locally, no API key)
- **Python 3.10+**

```bash
pip install llama-index llama-index-vector-stores-chroma chroma-db ollama
ollama pull mistral  # ~4 GB, runs locally
```

✅ **Free-tier friendly.** No API key. Slower inference (~5–10s per query on CPU), but fully offline.

### Option C: Manual (For Learning, 4–5 hrs)
- **sentence-transformers:** embedding model
- **NumPy + FAISS:** vector search (no external DB)
- **Claude API or ollama:** LLM
- **Python 3.10+**

```bash
pip install sentence-transformers faiss-cpu langchain anthropic
```

✅ **Maximum learning.** You write the retrieval loop. Understand every piece.

**Recommendation:** **Option A (LangChain + Chroma + Claude API).** You'll finish in 3 hrs and have time to iterate (e.g., add few-shot prompts).

---

## Step 1: Set Up (30 mins)

### 1a. Get a Claude API key
1. Go to https://console.anthropic.com/account/keys
2. Create new key (or use existing)
3. Export: `export ANTHROPIC_API_KEY="sk-ant-..."`

### 1b. Install dependencies
```bash
pip install langchain langchain-community langchain-anthropic chroma-db
```

### 1c. Organize your docs for ingestion
```bash
cd interview-preparation

# Create a small input set for testing (don't ingest all 129 docs yet)
mkdir docs-for-rag
cp 09-system-design/fundamentals.md docs-for-rag/
cp 09-system-design/chat-system.md docs-for-rag/
cp 13-ai-ml/llm-fundamentals.md docs-for-rag/
cp 13-ai-ml/rag-systems.md docs-for-rag/
cp 03-messaging/kafka.md docs-for-rag/

# Later, add more: cp 09-system-design/design-problems/* docs-for-rag/
```

---

## Step 2: Write the Script (90 mins)

Create `rag-app.py` in the repo root:

```python
#!/usr/bin/env python3
"""
Interview Guide RAG Chatbot
Ingest markdown docs, embed them, and answer questions via Claude.
"""

from pathlib import Path
from langchain.document_loaders import DirectoryLoader, TextLoader
from langchain.text_splitter import RecursiveCharacterTextSplitter
from langchain_chroma import Chroma
from langchain.embeddings import HuggingFaceEmbeddings
from langchain.prompts import PromptTemplate
from langchain_anthropic import ChatAnthropic
from langchain.schema.runnable import RunnablePassthrough

# 1. Load documents from directory
print("📖 Loading documents...")
loader = DirectoryLoader(
    "docs-for-rag",
    glob="**/*.md",
    loader_cls=TextLoader
)
docs = loader.load()
print(f"   Loaded {len(docs)} files")

# 2. Split into chunks (overlap helps with context)
print("✂️  Chunking documents...")
splitter = RecursiveCharacterTextSplitter(
    chunk_size=500,  # tokens-ish (rough)
    chunk_overlap=50
)
chunks = splitter.split_documents(docs)
print(f"   Created {len(chunks)} chunks")

# 3. Embed chunks and store in vector DB
print("🧮 Embedding and indexing...")
embeddings = HuggingFaceEmbeddings(
    model_name="sentence-transformers/all-MiniLM-L6-v2"  # 384-dim, fast
)
vectorstore = Chroma.from_documents(
    documents=chunks,
    embedding=embeddings,
    persist_directory="./chroma_db"  # Save for next runs
)
print(f"   Indexed {len(chunks)} chunks in Chroma")

# 4. Create retriever
retriever = vectorstore.as_retriever(
    search_kwargs={"k": 3}  # Top-3 results
)

# 5. LLM chain with context
llm = ChatAnthropic(model="claude-3-5-sonnet-20241022")

prompt = PromptTemplate(
    input_variables=["context", "question"],
    template="""You are an expert interviewer helping someone prepare for a senior engineering role.
    
Context from interview guides:
{context}

Question: {question}

Answer based on the context above. If the context doesn't address the question, say so. 
Be concise (2–3 paragraphs). Cite which guide you're referencing."""
)

# Chain: retrieve → format → LLM → output
def format_docs(docs):
    return "\n\n".join(doc.page_content for doc in docs)

chain = (
    {"context": retriever | format_docs, "question": RunnablePassthrough()}
    | prompt
    | llm
)

# 6. Interactive chat loop
print("\n🤖 Chat started. Type 'exit' to quit.\n")
while True:
    query = input("You: ").strip()
    if query.lower() == "exit":
        print("Goodbye!")
        break
    if not query:
        continue
    
    print("\nAssistant: ", end="", flush=True)
    response = chain.invoke(query)
    print(f"{response.content}\n")
```

**What this does:**
1. Loads your `.md` files
2. Chunks them into overlapping 500-token windows
3. Embeds with sentence-transformers (384-dim, ~50MB download, runs locally)
4. Stores in Chroma (in-memory or local SQLite)
5. Retrieves top-3 chunks per query
6. Sends retrieval + query to Claude
7. Streams the answer back

**Total: ~60 lines, easy to understand.**

---

## Step 3: Test & Iterate (120 mins)

### 3a. First run
```bash
python rag-app.py
```

**Expected:** Chroma downloads vectors, embeds your 5 docs (~30s), then waits for input.

### 3b. Test queries
```
You: How do I design a chat system?
    → Should retrieve chat-system.md
    → Claude explains with reference to guide

You: What is a vector database?
    → Should retrieve rag-systems.md
    → Claude explains Chroma, vector search, chunks

You: Explain Kafka exactly-once semantics.
    → Should retrieve kafka.md
    → Claude gives the interview answer

You: How do I get 10K certifications in cloud?
    → Low match (not in docs)
    → Claude says: "Your guides don't cover cloud certifications specifically..."
```

### 3c. Iterate: Improve retrieval
If answers are weak:

**Add more docs:**
```bash
cp 09-system-design/design-problems/* docs-for-rag/
# Re-run: python rag-app.py (Chroma re-indexes)
```

**Tune chunk size:**
```python
chunk_size=300    # Smaller = more precise, more chunks = slower
chunk_overlap=75  # More overlap = better stitching, slower
```

**Tune retrieval:**
```python
retriever = vectorstore.as_retriever(
    search_kwargs={"k": 5}  # Get top-5 instead of top-3
)
```

**Improve prompts:**
```python
prompt = PromptTemplate(
    input_variables=["context", "question"],
    template="""You are a senior tech interviewer. Answer the question using ONLY the context provided.
    
Context:
{context}

Question: {question}

Format your answer as:
1. Direct answer (1 paragraph)
2. Why it matters in interviews
3. A follow-up question they might ask

If context is insufficient, say so explicitly."""
)
```

---

## Step 4: Add Few-Shot Optimization (30 mins, optional)

Show the model examples of good interview answers:

```python
from langchain.few_shot_prompt import FewShotPromptTemplate

examples = [
    {
        "question": "Design a chat system.",
        "answer": "Start with: 1) Real-time (WebSockets vs polling), 2) Ordering (distributed transactions), 3) Persistence (Kafka), 4) Scaling (sharding by user_id), 5) Failure modes (read receipts are eventually consistent)."
    },
    {
        "question": "What's CAP theorem?",
        "answer": "Consistency, Availability, Partition tolerance—pick two. Example: Kafka sacrifices availability for C+P (ISR)."
    }
]

example_prompt = PromptTemplate(
    input_variables=["question", "answer"],
    template="Q: {question}\nA: {answer}"
)

prompt = FewShotPromptTemplate(
    examples=examples,
    example_prompt=example_prompt,
    suffix="Q: {question}\nA: "
)
```

This teaches the model *your* interview style.

---

## Step 5: Deploy Locally (Optional, 30 mins)

Make it a Flask app so you can open it in a browser:

```bash
pip install flask
```

```python
# rag-app-web.py
from flask import Flask, request, jsonify
from rag_chain import chain  # Import the chain from above

app = Flask(__name__)

@app.route("/ask", methods=["POST"])
def ask():
    data = request.json
    question = data.get("question")
    response = chain.invoke(question)
    return jsonify({"answer": response.content})

if __name__ == "__main__":
    app.run(debug=True, port=5000)
```

```bash
python rag-app-web.py
# Open http://localhost:5000
```

---

## What You'll Learn

| Concept | When | What You'll Know |
|---|---|---|
| **Embeddings** | Step 2: embedding | How text becomes 384-dim vectors; why L2 distance matters |
| **Chunking** | Step 2: splitting | Why overlaps help; how context windows (4K, 128K tokens) affect retrieval |
| **Vector Search** | Step 2: indexing | How Chroma finds top-k nearest neighbors; why ~3–5 results is the sweet spot |
| **Retrieval Augmentation** | Step 4: prompt | Why context + query beats pure LLM; how to order retrieved chunks |
| **Prompt Engineering** | Step 4: prompt | Few-shot examples, system message tone, output format |
| **Failure Modes** | Step 5: test | When retrieval fails (low similarity), when LLM hallucinates, cost per query |

---

## Deliverables (Week 5 End)

By end of Week 5, you should have:

1. ✅ `rag-app.py` (or `rag-app-web.py`) — working chatbot
2. ✅ `docs-for-rag/` — 5–10 ingested interview guides
3. ✅ `chroma_db/` — vector store (persistent)
4. ✅ **Reflection doc** (3 bullets):
   - *What surprised me about how embeddings work?*
   - *How would I scale this to 10K queries/day?*
   - *What's the biggest bottleneck I hit, and why?*

---

## Interview Talking Points After This Project

You'll be able to say:

> *"I built a RAG application over my interview guides. I ingested 10 markdown files, chunked them into 500-token overlapping windows, embedded them with sentence-transformers, and stored them in Chroma. When a user asks a question, I retrieve the top-3 most similar chunks, pass them to Claude with a custom prompt, and return the answer. I learned that chunk overlap is critical for coherence, and that retrieval precision depends heavily on embedding quality. I also noticed that Claude sometimes hallucinates details not in the context, so I added explicit instructions to cite the source guide."*

**That's expert-level context.** You're not just *reading* about RAG; you've *built* it.

---

## FAQ

**Q: Do I need to run this locally?**
A: Yes. The learning is in watching it fail, tweaking it, and rebuilding. 2 hrs hands-on > 6 hrs reading.

**Q: What if the API key fails?**
A: Use `ollama` instead (Option B). Download Mistral, run locally, it's free.

**Q: Can I use GPT-4?**
A: Yes, swap `ChatAnthropic` for `ChatOpenAI`. But you'll need OpenAI credits.

**Q: How do I cite specific guides in the answer?**
A: The `format_docs` function includes `doc.page_content`. Add metadata to track filenames:
```python
def format_docs(docs):
    return "\n\n".join(f"[{doc.metadata.get('source', 'unknown')}]\n{doc.page_content}" for doc in docs)
```

**Q: What if the answers are bad?**
A: That's the point. Debug: Is retrieval working? Print the retrieved chunks. Is the LLM confused? Try a stronger model or a better prompt.

---

**Go build it. You've got 4 hours. Ship by Saturday.**
