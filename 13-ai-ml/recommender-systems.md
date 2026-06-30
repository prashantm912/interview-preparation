# Recommender Systems

[← Back to master index](../README.md)

An interview-grade reference for recommender systems — the discipline behind "people who bought this also bought," the home feed, "up next," and the product carousels that drive a huge share of engagement and revenue at consumer companies. This guide covers collaborative and content-based filtering, matrix factorization (SVD/ALS), hybrid designs, the cold-start problem, the modern two-stage candidate-generation-plus-ranking architecture, two-tower retrieval with embeddings and ANN, deep-learning recommenders (neural CF, wide & deep, sequence models), offline and online evaluation (precision@k, recall@k, NDCG, MAP, AUC), and the systems realities of bias, feedback loops, diversity, feature stores, and real-time vs batch serving. Every answer explains the *why* and the engineering trade-offs, with Python snippets for the practical and coding questions. Current through 2026.

## Table of Contents

- [🟢 Basic (0–2 yrs)](#-basic-02-yrs)
- [🟡 Intermediate (3–7 yrs)](#-intermediate-37-yrs)
- [🟠 Advanced (8–12 yrs)](#-advanced-812-yrs)
- [🔴 Expert (15+ yrs)](#-expert-15-yrs)
- [✅ Key Takeaways](#-key-takeaways)
- [⚠️ Common Pitfalls](#-common-pitfalls)
- [📚 Further Reading](#-further-reading)

---

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is a recommender system, and what core problem does it solve?

A **recommender system** predicts how relevant an item is to a user and surfaces the most relevant items, usually as a ranked list. The core problem is **personalized relevance at scale**: a catalog may have millions of items, a user can only see a handful, and the system must choose *which* handful for *each* user.

Formally, given a set of users `U`, items `I`, and some observed interactions, we want to estimate a relevance score `r(u, i)` for unseen `(user, item)` pairs and return `top-k` items by score. The "interactions" can be explicit (ratings) or implicit (clicks, watches, purchases).

The business value is concentrated: at Netflix, Amazon, YouTube, Spotify, and TikTok, recommendations drive a large majority of consumption, so a 1% lift in the model translates to large revenue and engagement gains.

### Q2. [Theory] Explain the difference between explicit and implicit feedback.

**Explicit feedback** is a deliberate, stated preference: a 5-star rating, a thumbs up/down, a like. It is high signal but **sparse** — most users never rate anything — and **biased** toward extreme opinions and toward items people already chose to consume.

**Implicit feedback** is behavior logged as a side effect of usage: clicks, watch time, purchases, dwell time, add-to-cart, skips. It is **abundant** but **noisy and one-class**: a click is a weak positive, and the *absence* of a click is ambiguous (the user may not have seen the item, may have disliked it, or simply hasn't gotten to it yet). There are no true negatives, only "unobserved."

```text
Explicit:  user 7 rated movie 42  →  4.0 / 5      (sparse, low-volume, high-signal)
Implicit:  user 7 watched movie 42 for 38 min     (dense, high-volume, noisy)
           user 7 did NOT click movie 88  →  ???   (missing, not negative)
```

Most production systems run on implicit feedback because it is plentiful and reflects real behavior rather than self-report. The modeling consequence: you cannot treat unobserved entries as zeros naively — you handle them with negative sampling or confidence-weighted losses (e.g., ALS for implicit feedback).

### Q3. [Theory] What is collaborative filtering, and how does it differ from content-based filtering?

**Collaborative filtering (CF)** recommends based on the **interaction patterns of many users**: "users similar to you liked X," or "items co-liked with what you liked." It uses only the user–item interaction matrix and needs no knowledge of *what the items actually are*. Its superpower is discovering non-obvious associations (diapers → beer) and surfacing items beyond a user's stated interests.

**Content-based filtering** recommends items **similar to ones the user already liked**, where similarity is computed from **item features** (genre, text, tags, embeddings): "you liked this sci-fi thriller, here's another sci-fi thriller." It needs descriptive features but works per-user in isolation.

```text
Collaborative:  "people like you also liked..."   (uses the crowd; ignores content)
Content-based:  "more items like the ones you liked"  (uses item features; ignores the crowd)
```

| Aspect            | Collaborative              | Content-based             |
|-------------------|----------------------------|---------------------------|
| Signal            | User–item interactions     | Item attributes/features  |
| New-item cold start | Bad (no interactions yet) | Good (features available) |
| Serendipity       | High                       | Low (filter bubble)       |
| Needs item metadata | No                       | Yes                       |

### Q4. [Theory] Explain user-based vs item-based collaborative filtering.

Both are **neighborhood (memory-based) CF** methods on the interaction matrix `R`.

- **User-based CF**: to recommend for user `u`, find users most similar to `u` (by cosine or Pearson over their rating/interaction vectors), then recommend items those neighbors liked that `u` hasn't seen.
- **Item-based CF**: precompute item–item similarities. To recommend for `u`, look at items `u` already liked and recommend items most similar to those.

```text
User-based:  sim(u, v) over rows of R    → "users like you"
Item-based:  sim(i, j) over cols of R    → "items like what you liked"
```

**Item-based is preferred in production** (this is Amazon's classic "item-to-item" approach) because:
1. Items are far **more stable** than users — item similarities can be precomputed offline and cached, while user tastes shift constantly.
2. There are usually **fewer items than users**, and the item–item matrix changes slowly.
3. It scales: at serving time you do a cheap lookup of "similar items" for the few items the user touched.

### Q5. [Coding] Implement item-based collaborative filtering with cosine similarity.

```python
import numpy as np

def cosine_item_similarity(R):
    """
    R: (n_users x n_items) interaction matrix (ratings or 0/1).
    Returns (n_items x n_items) cosine similarity matrix.
    """
    # Normalize each item column to unit length
    norms = np.linalg.norm(R, axis=0, keepdims=True)
    norms[norms == 0] = 1e-9          # avoid divide-by-zero for unseen items
    R_norm = R / norms
    return R_norm.T @ R_norm          # (items x items)

def recommend(R, sim, user_idx, k=5):
    user_row = R[user_idx]            # items this user interacted with
    # Score = weighted sum of similarities to items the user liked
    scores = sim @ user_row           # (n_items,)
    scores[user_row > 0] = -np.inf    # exclude already-seen items
    return np.argsort(scores)[::-1][:k]

# Example: 4 users x 5 items
R = np.array([
    [5, 0, 4, 0, 1],
    [4, 0, 5, 0, 1],
    [1, 5, 0, 4, 0],
    [0, 4, 0, 5, 0],
], dtype=float)

sim = cosine_item_similarity(R)
print(recommend(R, sim, user_idx=0, k=2))
```

Complexity: building the similarity matrix is `O(n_items² · n_users)` (done offline); scoring at serve time is a sparse matrix–vector product over only the items the user touched.

### Q6. [Theory] What is the cold-start problem? Name its three forms.

**Cold start** is the inability to make good recommendations when there is little or no interaction history. Three forms:

1. **New user** — no history, so collaborative filtering has nothing to work with. Mitigations: onboarding questionnaires, demographic priors, popularity/trending fallback, contextual signals (device, location, time).
2. **New item** — no one has interacted with it yet, so CF can't place it. Mitigation: content-based features and embeddings derived from metadata/text/images so it can be recommended on day one.
3. **New system** — a brand-new product with no data at all. Mitigation: bootstrap with content-based methods, editorial rules, and popularity until enough behavior accrues.

```text
       interactions →  none        some        lots
New user:               ❌ CF       weak CF      strong CF
New item:               ❌ CF       weak CF      strong CF
```

The general principle: **content/features rescue you when interactions are absent**, which is the central argument for hybrid systems.

### Q7. [Theory] Why is the user–item interaction matrix described as "sparse," and why does that matter?

In real systems a user interacts with a tiny fraction of the catalog — often **well under 1%** (frequently 0.01–0.1%). So the matrix `R` is `n_users × n_items` but almost entirely empty.

Why it matters:
- **Neighborhood overlap is thin**: two users may share zero items, so similarity is undefined or unreliable.
- **Storage**: you must use sparse representations, not dense arrays — a 10M × 1M dense matrix is impossible.
- **Generalization need**: because most cells are unobserved, the model must *generalize* from few observations, which motivates **low-rank** approaches like matrix factorization that fill in the blanks via latent structure.

### Q8. [Practical] How would you build a simple popularity-based baseline, and why bother?

A popularity baseline recommends the globally (or segment-wise) most-interacted items to everyone. It is your **floor**: any personalized model must beat it, and it's the right answer for cold-start users.

```python
import pandas as pd

def popularity_baseline(events: pd.DataFrame, k=10, recency_days=30):
    """events: columns [user_id, item_id, timestamp]."""
    cutoff = events["timestamp"].max() - pd.Timedelta(days=recency_days)
    recent = events[events["timestamp"] >= cutoff]
    top = (recent.groupby("item_id")["user_id"]
                 .nunique()                # unique users = popularity
                 .sort_values(ascending=False)
                 .head(k))
    return top.index.tolist()
```

Why bother: (1) it's a sanity check — if your fancy model can't beat popularity on offline metrics, something is wrong; (2) it's the cold-start fallback; (3) using **recency windows** and **unique-user counts** (not raw event counts) avoids letting a few power users or one viral day dominate forever.

### Q9. [Theory] What does "candidate generation + ranking" mean, and why two stages?

Modern large-scale recommenders use a **two-stage (sometimes multi-stage) funnel**:

```text
millions of items
      │  Candidate generation (retrieval): fast, cheap, high recall
      ▼
   ~hundreds–thousands of candidates
      │  Ranking: heavy model, many features, high precision
      ▼
   ~10–50 items shown
```

- **Candidate generation / retrieval** narrows millions of items to a few hundred or thousand using cheap methods (ANN over embeddings, co-occurrence, popularity). Optimized for **recall** and latency, not perfect ordering.
- **Ranking** applies an expensive model with rich features (user, item, context, cross features) to precisely order the small candidate set. Optimized for **precision** at the top.

Why split: scoring a heavy model over millions of items per request is infeasible (latency and cost). The funnel concentrates expensive computation where it matters. Many systems add a third **re-ranking** stage for business rules, diversity, and freshness.

### Q10. [Theory] What is content-based filtering's main weakness?

**Over-specialization / the filter bubble.** Because it only recommends items similar to what the user already engaged with, it keeps narrowing to a single neighborhood of the catalog and rarely surprises the user. If you watched one cooking video, you get only cooking videos.

Secondary weaknesses: it requires good item features (garbage features → garbage recs), it can't capture "taste" signals that aren't encoded in features, and it misses the cross-user wisdom that makes collaborative filtering discover unexpected gems. This is exactly why hybrids exist — CF provides serendipity, content-based provides cold-start coverage.

### Q11. [Practical] How do you turn item text/metadata into features for content-based filtering?

The classic pipeline turns each item into a vector, then recommends by nearest neighbors in that space.

```python
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics.pairwise import cosine_similarity

items = [
    "space sci-fi thriller with aliens",
    "romantic comedy in paris",
    "hard sci-fi about interstellar travel",
    "feel-good romance set in rome",
]

# TF-IDF: weight rare, informative words higher than common ones
vec = TfidfVectorizer(stop_words="english")
X = vec.fit_transform(items)             # (n_items x vocab) sparse

sim = cosine_similarity(X)               # item-item similarity

def similar_to(idx, k=2):
    scores = sim[idx].copy()
    scores[idx] = -1                     # exclude self
    return scores.argsort()[::-1][:k]

print(similar_to(0))   # -> the other sci-fi item
```

Modern upgrade: replace TF-IDF with **text embeddings** (a sentence-transformer or LLM embedding model) and use image/audio embeddings for those modalities. Embeddings capture semantic similarity ("automobile" ≈ "car") that bag-of-words misses, and they slot directly into ANN retrieval.

### Q12. [Theory] What similarity/distance measures are common in recommenders, and when do you use each?

- **Cosine similarity** — angle between vectors, ignores magnitude. The default for sparse interaction vectors and embeddings; robust to differing activity levels.
- **Pearson correlation** — cosine after mean-centering each user; corrects for **rating bias** (a user who rates everything 4–5 vs one who rates 1–2). Good for explicit ratings.
- **Jaccard similarity** — `|A ∩ B| / |A ∪ B|` over sets; for pure binary implicit data (did/didn't interact), ignoring counts.
- **Dot product** — magnitude matters; used by matrix-factorization and two-tower models where embedding norm encodes popularity/confidence. ANN systems use it directly (maximum inner-product search).
- **Euclidean (L2)** — used by some metric-learning recommenders; less common for sparse high-dimensional data because of the curse of dimensionality.

Rule of thumb: cosine for sparse/explicit neighborhoods and normalized embeddings; dot product for learned latent factors and retrieval.

### Q13. [Theory] What is the difference between recommendation and search?

Both rank items, but:
- **Search** is **query-driven** — the user states intent explicitly ("running shoes size 10"). Relevance is dominated by query–item match; personalization is secondary.
- **Recommendation** is **intent-inferred** — there is no query; the system must guess what the user wants from history and context. Personalization is primary.

They share machinery (retrieval + ranking, embeddings, learning-to-rank), which is why teams often unify them. The key modeling difference is the presence/absence of an explicit query as the dominant feature.

### Q14. [Practical] What offline data splits should you use to evaluate a recommender, and what's the common mistake?

**Mistake: random train/test split.** Recommenders predict the *future*, so a random split leaks future interactions into training and massively inflates metrics.

**Correct: temporal split** — train on interactions before time `T`, test on interactions after `T`. Variants:
- **Global temporal split** — one cutoff for everyone (most realistic, mirrors production retraining).
- **Leave-last-n-out per user** — hold out each user's most recent interactions; common in academic sequence-modeling.

```text
WRONG (random):                  RIGHT (temporal):
 ░train░ ▓test▓ ░train░ ▓test▓     ░░░ train ░░░ | ▓▓ test ▓▓
 (future leaks into the past)      time →     T
```

Also stratify by activity level and report cold-start slices separately, because a model can look great on power users while failing newcomers.

---

## 🟡 Intermediate (3–7 yrs)

### Q15. [Theory] Explain matrix factorization for recommendations.

**Matrix factorization (MF)** is the workhorse of model-based collaborative filtering. It approximates the sparse `n_users × n_items` matrix `R` as the product of two **low-rank** matrices:

```text
R  ≈  P · Qᵀ
(U×I)  (U×d)(d×I)

P: user embeddings (each user → d latent factors)
Q: item embeddings (each item → d latent factors)
prediction:  r̂(u,i) = pᵤ · qᵢ   (dot product)
```

Each user and item is mapped into a shared `d`-dimensional **latent space** (`d` typically 32–512). The dot product of a user vector and an item vector predicts the interaction. The latent dimensions aren't hand-defined — the model discovers them (some may loosely correspond to "amount of action," "indie vs blockbuster," etc.).

You learn `P` and `Q` by minimizing reconstruction error over the **observed** entries with regularization:

```text
min Σ_(u,i observed) (r(u,i) − pᵤ·qᵢ)²  +  λ(‖pᵤ‖² + ‖qᵢ‖²)
```

This famously won the Netflix Prize and remains a strong, cheap baseline. It generalizes far better than neighborhood CF on sparse data because the low-rank structure forces the model to learn shared factors instead of memorizing per-pair similarities.

### Q16. [Theory] How does SVD-based MF differ from ALS, and how does each optimize?

Both learn `P` and `Q`, but differ in formulation and optimization:

- **"SVD" in recommenders** (Funk-SVD / SVD++) is a misnomer — true SVD requires a complete matrix. The recommender version minimizes squared error over *observed* entries via **stochastic gradient descent (SGD)**, often adding **bias terms**: `r̂ = μ + bᵤ + bᵢ + pᵤ·qᵢ`. SVD++ further incorporates implicit feedback (which items a user interacted with at all).

- **ALS (Alternating Least Squares)** fixes `Q` and solves for `P` in closed form (a least-squares problem per user), then fixes `P` and solves for `Q`, alternating until convergence. Each step is a convex least-squares solve.

```text
SGD/SVD:  one global non-convex objective, gradient steps, sample-by-sample
ALS:      alternate two convex subproblems, each solved exactly,
          embarrassingly parallel across users/items
```

**When to use which:** ALS parallelizes beautifully across rows/columns (Spark MLlib's recommender is ALS) and is the standard for **implicit feedback** with confidence weighting. SGD-based SVD is memory-light, handles streaming updates, and easily extends with biases and side features. ALS needs more memory per step but converges in few iterations.

### Q17. [Theory] How does implicit-feedback ALS handle the "no negatives" problem?

Hu, Koren & Volinsky's implicit-ALS reframes the data into two pieces:

- **Preference** `p(u,i) = 1` if the user interacted at all, else `0`.
- **Confidence** `c(u,i) = 1 + α·count(u,i)` — how *sure* we are about that preference, scaled by interaction strength (watch count, plays).

The loss sums over **all** user–item pairs (including unobserved ones treated as weak negatives), weighted by confidence:

```text
min Σ_(all u,i) c(u,i) · (p(u,i) − pᵤ·qᵢ)²  +  λ(‖P‖² + ‖Q‖²)
```

So an unobserved cell is a low-confidence `0` (weak negative), while a heavily-interacted cell is a high-confidence `1`. The `α` hyperparameter trades off how strongly counts boost confidence. This is the right mental model for implicit data: **you're never certain a non-interaction is a dislike**, so you down-weight it rather than treating it as a hard negative.

### Q18. [Coding] Implement matrix factorization training with SGD.

```python
import numpy as np

def train_mf(R, mask, d=20, lr=0.01, reg=0.1, epochs=50, seed=0):
    """
    R:    (n_users x n_items) observed ratings (0 where unobserved)
    mask: same shape, 1 where observed
    Returns user factors P, item factors Q, global bias mu, user/item biases.
    """
    rng = np.random.default_rng(seed)
    n_u, n_i = R.shape
    P = rng.normal(0, 0.1, (n_u, d))
    Q = rng.normal(0, 0.1, (n_i, d))
    bu = np.zeros(n_u)
    bi = np.zeros(n_i)
    mu = R[mask == 1].mean()

    obs = list(zip(*np.where(mask == 1)))      # observed (u, i) pairs
    for epoch in range(epochs):
        rng.shuffle(obs)
        for u, i in obs:
            pred = mu + bu[u] + bi[i] + P[u] @ Q[i]
            err = R[u, i] - pred
            # gradient step on this single observation
            bu[u] += lr * (err - reg * bu[u])
            bi[i] += lr * (err - reg * bi[i])
            P[u]  += lr * (err * Q[i] - reg * P[u])
            Q[i]  += lr * (err * P[u] - reg * Q[i])
    return P, Q, mu, bu, bi

def predict(P, Q, mu, bu, bi, u, i):
    return mu + bu[u] + bi[i] + P[u] @ Q[i]
```

Key details interviewers probe: **biases** (`mu, bu, bi`) capture "this user rates high" / "this item is popular" so the latent factors model *interactions* rather than these offsets; **regularization** (`reg`) prevents the factors from memorizing; and you only iterate over **observed** entries, never the zeros.

### Q19. [Theory] What is the two-tower model and why is it the standard for retrieval?

The **two-tower (dual-encoder)** model is the dominant architecture for the **candidate-generation/retrieval** stage. Two separate neural networks ("towers") produce embeddings:

```text
   user features                 item features
        │                              │
   [ user tower ]                 [ item tower ]
        │                              │
   user embedding  ───── dot ─────  item embedding
                         product
                          │
                    relevance score
```

- The **user tower** encodes user id + history + context into a `d`-dim vector.
- The **item tower** encodes item id + features into a `d`-dim vector in the *same* space.
- Relevance = dot product (or cosine) of the two.

Why it's the standard for retrieval:
1. **Item embeddings are query-independent**, so you precompute *all* item embeddings offline and index them in an **ANN** structure.
2. At request time you compute *one* user embedding, then do a single ANN lookup to retrieve top candidates from millions of items in milliseconds.
3. It's trained with negatives (often in-batch negatives), naturally fitting implicit feedback.

The cost: the user and item never interact until the final dot product (no early cross-features), so the two-tower is great for recall/retrieval but a richer **cross-feature** model is needed in ranking.

### Q20. [Theory] What is approximate nearest neighbor (ANN) search, and why is it essential here?

Once items are embeddings, retrieval becomes "find the `k` item vectors closest to this user vector." Exact nearest-neighbor search is `O(n·d)` per query — too slow for millions of items at production QPS. **ANN** trades a tiny bit of accuracy for orders-of-magnitude speedup.

Major families:
- **HNSW (Hierarchical Navigable Small World)** — a multi-layer proximity graph; excellent recall/latency, the default in most vector DBs (Faiss, Milvus, pgvector, Qdrant, Weaviate). Higher memory.
- **IVF (Inverted File)** — cluster vectors, search only the nearest clusters; tunable via `nprobe`.
- **PQ (Product Quantization)** — compress vectors into codes for huge memory savings; often combined as **IVF-PQ** for billion-scale indexes.

```text
exact:  scan all 50M vectors  →  too slow
HNSW:   hop through a graph    →  ~log(n) comparisons, 95–99% recall
```

For maximum-inner-product retrieval (dot-product scoring), use an index configured for inner product, or normalize vectors so cosine ≈ dot product.

### Q21. [Coding] Build a two-tower-style retrieval lookup with Faiss.

```python
import numpy as np
import faiss

d = 64                                  # embedding dimension
n_items = 100_000
item_embs = np.random.randn(n_items, d).astype("float32")
faiss.normalize_L2(item_embs)           # so inner product == cosine

# Build an HNSW index for inner-product (cosine) search
index = faiss.IndexHNSWFlat(d, 32, faiss.METRIC_INNER_PRODUCT)
index.hnsw.efConstruction = 200
index.add(item_embs)                    # offline: index all items
index.hnsw.efSearch = 64                # recall/latency knob at query time

def retrieve(user_emb, k=200):
    q = user_emb.reshape(1, -1).astype("float32")
    faiss.normalize_L2(q)
    scores, ids = index.search(q, k)    # one ANN lookup → candidates
    return ids[0], scores[0]

user_emb = np.random.randn(d)
candidate_ids, candidate_scores = retrieve(user_emb, k=200)
```

The pattern: **index built offline**, **one query per request**, `efSearch`/`nprobe` tunes the recall–latency trade-off. The retrieved few hundred candidates then go to the ranking model.

### Q22. [Theory] Define precision@k, recall@k, and when you'd prefer each.

For a ranked top-`k` list, let `relevant` be the set of items the user actually engaged with in the holdout:

```text
precision@k = (# relevant in top-k) / k
recall@k    = (# relevant in top-k) / (total # relevant for that user)
```

- **Precision@k** answers "of what I showed, how much was good?" — it matters when **screen real estate is scarce** and showing junk is costly (the top of a feed, a 5-item carousel).
- **Recall@k** answers "of all the good items, how many did I surface?" — it matters in **retrieval/candidate-generation**, where you want to not *miss* relevant items before ranking refines them.

Rule of thumb: optimize **recall** in the retrieval stage (don't lose good candidates) and **precision/NDCG** in the ranking stage (order the top well). Both ignore *order within* the top-k, which is why ranking metrics like NDCG matter too.

### Q23. [Theory] What is NDCG and why is it preferred over precision@k for ranking?

**NDCG (Normalized Discounted Cumulative Gain)** rewards putting highly relevant items *near the top*, unlike precision@k which treats all top-k positions equally.

```text
DCG@k  = Σ (rel_i / log2(i + 1))        i = 1..k   (gain discounted by position)
IDCG@k = DCG of the ideal ordering
NDCG@k = DCG@k / IDCG@k                   ∈ [0, 1]
```

The `1/log2(i+1)` **position discount** means a relevant item at rank 1 contributes far more than the same item at rank 10 — matching how users actually consume ranked lists (attention decays sharply with position). Normalizing by the ideal DCG makes it comparable across users with different numbers of relevant items.

NDCG handles **graded relevance** (a 5-star item gains more than a 3-star) and **position**, which precision@k can't, making it the standard ranking-quality metric. It's the metric most learning-to-rank models effectively target.

### Q24. [Theory] Explain MAP and AUC in the recommender context.

- **MAP (Mean Average Precision)**: for one user, **Average Precision** is precision@k evaluated at each rank where a relevant item appears, averaged; MAP is the mean across users. It rewards ranking *all* relevant items high and, unlike precision@k at a fixed cut, accounts for the full ranked order of binary-relevant items. Good for binary relevance with many relevant items per user.

- **AUC (Area Under the ROC Curve)**: the probability that a randomly chosen **positive** (relevant) item is scored higher than a randomly chosen **negative**. In recommenders it measures pairwise ranking quality — "does the model rank positives above negatives?" It's threshold-free and pairs naturally with BPR-style training, but it's **position-agnostic**: it doesn't care whether the win happens at rank 2 or rank 2000, so it can look healthy while top-of-list quality is mediocre. Use NDCG/precision@k for the *visible* list and AUC as an overall pairwise-separation check.

### Q25. [Theory] Why can't offline metrics fully predict online performance? Compare offline and online evaluation.

**Offline evaluation** replays logged data: train on the past, measure NDCG/recall on a holdout. It's fast, cheap, and reproducible — but it suffers from **biases baked into the logs**:

- **Presentation/exposure bias**: you only logged feedback for items the *old* model showed. A new model recommending items the old one never surfaced gets unfairly counted as "wrong" because there's no label.
- **Closed-loop bias**: the data was generated by the current system, so offline replay favors models that mimic it.
- **No interactivity**: offline metrics can't see how users *react* to genuinely new recommendations.

**Online evaluation (A/B testing)** runs the model on real traffic and measures business metrics (CTR, watch time, retention, revenue). It's the ground truth but slow, expensive, and risky.

```text
offline  →  cheap, biased, proxy metrics   →  filters candidate models
online   →  costly, true, business metrics  →  decides the winner
```

The standard workflow: use offline metrics to *filter* candidates, then A/B test the survivors. **Off-policy/counterfactual evaluation** (IPS, doubly-robust estimators) and **interleaving** narrow the gap by correcting for exposure bias. Always remember: offline wins that don't replicate online are common, which is why online is the final arbiter.

### Q26. [Practical] How do you do negative sampling for implicit-feedback training?

With only positives (clicks/watches), you must manufacture negatives so the model learns to separate. Strategies:

- **Uniform random negatives** — sample items the user didn't interact with uniformly. Cheap but mostly "easy" negatives the model already gets right.
- **Popularity-based (frequency) negatives** — sample proportional to item popularity (often `freq^0.75`, the word2vec trick); harder negatives, corrects for the fact that popular items get clicked partly due to exposure.
- **In-batch negatives** — for two-tower training, treat the *other* users' positive items in the same mini-batch as negatives. Extremely efficient (reuses already-computed embeddings) and the de-facto standard for retrieval, but needs a **logQ / sampled-softmax correction** because popular items appear as negatives too often, otherwise it suppresses head items.
- **Hard negative mining** — sample items the current model scores highly but the user didn't pick; sharpens the decision boundary, used in later training stages.

```python
def sample_negatives(user_items, n_items, n_neg, pop=None, rng=None):
    rng = rng or np.random.default_rng()
    seen = set(user_items)
    negs = []
    while len(negs) < n_neg:
        j = (rng.choice(n_items, p=pop) if pop is not None
             else rng.integers(n_items))
        if j not in seen:
            negs.append(j)
    return negs
```

Trade-off: too-easy negatives → weak gradients; too-hard negatives (or false negatives the user *would* have liked) → noisy training. A mix of random + popularity + some hard negatives is common.

### Q27. [Theory] What is a hybrid recommender, and what are the main combination strategies?

A **hybrid** combines collaborative and content-based (and other) signals to get the best of each — CF's serendipity plus content's cold-start coverage. Burke's taxonomy of combination strategies:

- **Weighted** — score = weighted sum of CF and content scores.
- **Switching** — pick a method per situation (content-based for new items, CF for warm ones).
- **Mixed** — present recommendations from multiple methods side by side.
- **Feature combination** — feed content features *and* collaborative signals into one model (this is what modern deep recommenders do).
- **Cascade** — one method refines another's output (basically the retrieval→ranking funnel).
- **Meta-level** — one model's learned representation feeds another.

In practice the modern answer is **feature combination inside a single learned model**: a deep ranker that consumes user/item embeddings (collaborative), content features, and context together. The two-stage funnel itself is a cascade hybrid.

### Q28. [Theory] What is popularity bias and how do feedback loops amplify it?

**Popularity bias** is the tendency of recommenders to over-recommend already-popular items, because they have the most interaction data and thus the strongest, most confident signal. Niche items stay invisible for lack of data.

**Feedback loops** make this self-reinforcing and dangerous:

```text
model recommends popular items
        │
        ▼
users interact more with popular items (they're the only ones shown)
        │
        ▼
logs show popular items as even more "relevant"
        │
        ▼
next model trains on this, recommends them even harder  ──┐
        ▲                                                  │
        └──────────────────────────────────────────────────┘
                       (rich get richer)
```

Consequences: a shrinking catalog of "winners," reduced diversity, unfair exclusion of new/long-tail creators, and a system that confuses *exposure* with *preference*. Mitigations: exposure de-biasing (IPS weighting by propensity), explicit exploration (epsilon-greedy, bandits), diversity/long-tail boosts in re-ranking, and serving popularity-debiased candidate sources.

### Q29. [Theory] Define diversity, novelty, and serendipity. Why optimize for them?

These are "beyond-accuracy" objectives:

- **Diversity** — how *dissimilar* the items in a single list are to each other. A list of ten near-identical items has low diversity even if each is individually relevant.
- **Novelty** — how *unknown/unexpected* an item is to the user (often inverse popularity); recommending something they haven't seen before.
- **Serendipity** — recommendations that are both **relevant** and **surprising** — items the user wouldn't have found themselves and didn't expect, yet enjoy. The hardest and most valuable.

```text
relevant + obvious      = useful but boring  (low value)
relevant + surprising   = serendipity        (high value)
irrelevant + surprising = random noise
```

Why optimize for them: pure accuracy optimization collapses into a narrow, repetitive, popularity-biased feed that bores users and starves the catalog. Diversity raises long-term engagement and satisfaction even when it slightly lowers short-term CTR, and it mitigates filter bubbles. Techniques: **MMR (Maximal Marginal Relevance)**, **determinantal point processes (DPPs)**, and diversity penalties in re-ranking.

### Q30. [Coding] Implement Maximal Marginal Relevance (MMR) for diverse re-ranking.

```python
import numpy as np

def mmr_rerank(candidates, relevance, sim_matrix, k, lambda_=0.7):
    """
    candidates: list of item indices (already retrieved)
    relevance:  dict item -> relevance score
    sim_matrix: item-item similarity (indexed by item id)
    lambda_:    1.0 = pure relevance, 0.0 = pure diversity
    """
    selected = []
    remaining = list(candidates)
    while remaining and len(selected) < k:
        best, best_score = None, -np.inf
        for c in remaining:
            # max similarity to anything already chosen (redundancy penalty)
            div = max((sim_matrix[c][s] for s in selected), default=0.0)
            score = lambda_ * relevance[c] - (1 - lambda_) * div
            if score > best_score:
                best, best_score = c, score
        selected.append(best)
        remaining.remove(best)
    return selected
```

MMR greedily picks the item that maximizes `λ·relevance − (1−λ)·max-similarity-to-already-picked`. Tuning `λ` slides between a pure-relevance list and a maximally diverse one. It runs in `O(k·n)` similarity lookups, cheap enough for re-ranking a few hundred candidates.

### Q31. [Practical] How do you evaluate diversity and novelty offline?

You add list-level and catalog-level metrics alongside accuracy:

```python
import numpy as np

def intra_list_diversity(rec_items, sim_matrix):
    """1 - average pairwise similarity within one user's list."""
    n = len(rec_items)
    if n < 2:
        return 0.0
    sims = [sim_matrix[i][j] for a, i in enumerate(rec_items)
                              for j in rec_items[a+1:]]
    return 1 - np.mean(sims)

def novelty(rec_items, item_popularity):
    """Mean self-information: rarer items = higher novelty."""
    return np.mean([-np.log2(item_popularity[i] + 1e-9) for i in rec_items])

def catalog_coverage(all_rec_lists, n_items):
    """Fraction of the catalog ever recommended across all users."""
    shown = {i for lst in all_rec_lists for i in lst}
    return len(shown) / n_items
```

- **Intra-list diversity** — average dissimilarity within a list.
- **Novelty** — mean self-information (`-log` popularity); rare items score higher.
- **Coverage** — fraction of the catalog that ever gets recommended (a check against popularity collapse).
- **Gini / long-tail share** — how concentrated recommendations are.

Track these *together with* NDCG so you can see the accuracy–diversity trade-off explicitly, rather than silently collapsing the feed.

### Q32. [Theory] What is the difference between batch and real-time recommendation, and where does each fit?

```text
BATCH (offline)                       REAL-TIME (online)
precompute recs nightly               compute per request
store top-N per user in a KV store    score candidates live
serve via fast lookup                 react to current session
stale within a day                    fresh to the last click
cheap, simple                         complex, low-latency infra
```

- **Batch** precomputes recommendations on a schedule and caches them. Great for slow-changing tastes (a weekly "discover" playlist), simple to operate, cheap. Downside: it can't react to what the user just did this session.
- **Real-time** computes recommendations per request using up-to-the-second signals (the video you just watched, the item you just added to cart). Essential for session-based, news, and short-video feeds where intent shifts by the minute.

Most large systems are **hybrid**: batch-precompute embeddings and candidate pools, then do **real-time retrieval + ranking** that incorporates fresh session features. The phrase to use in interviews: "precompute what's stable, compute live what's fresh."

### Q33. [Theory] What is a feature store and why do recommenders need one?

A **feature store** is the system that manages ML features as a shared, governed asset: it computes, stores, serves, and versions features for both training and serving.

The core problem it solves is **online/offline consistency (train–serve skew)**: the feature you compute in a batch training pipeline must be *identical* to the one served at inference, or the model degrades silently.

```text
            ┌─────────────── Feature definitions (single source of truth)
            │
   batch ───┤  Offline store (warehouse)  → training data, point-in-time joins
            │
   stream ──┤  Online store (low-latency KV: Redis/DynamoDB) → serving
            └───────────────
```

Key capabilities for recommenders:
- **Online store** for millisecond feature lookups at serving time (user counters, recent items).
- **Offline store** for generating training sets with **point-in-time-correct** joins (no future leakage).
- **Feature reuse** across models and teams; **versioning** and **monitoring** for drift.
- **Streaming + batch** ingestion so real-time counters (clicks in the last 5 min) and slow aggregates coexist.

Examples: Feast, Tecton, and the in-house stores at large tech companies. The recommender-specific pain it removes: computing "user's last 50 items" the same way offline and online.

---

## 🟠 Advanced (8–12 yrs)

### Q34. [Theory] Walk through a modern industrial recommender architecture end to end.

A typical large-scale system is a multi-stage funnel feeding off shared infrastructure:

```text
                       ┌──────────── Feature Store (online + offline) ───────────┐
                       │                                                          │
 user request ─► [Candidate Generation] ─► [Filtering] ─► [Ranking] ─► [Re-ranking] ─► UI
                  (multiple sources)         (seen/        (heavy        (diversity,
                   • two-tower ANN            policy/        DNN, many     business
                   • co-occurrence            eligibility)   features)     rules, freshness)
                   • popularity/trending
                   • graph / session
                        │                                       │
                        └──────── logs ◄── impressions/clicks ◄──┘
                                    │
                          training pipelines (batch + streaming)
```

1. **Candidate generation** — several retrieval sources (two-tower ANN, item-to-item co-occurrence, trending, freshly-published) each return a few hundred candidates; union them. Multiple sources guard against any single model's blind spots.
2. **Filtering** — remove ineligible items (already seen, out of stock, region/age-blocked, policy violations).
3. **Ranking** — a heavy DNN (wide & deep / DLRM-style) scores each candidate with rich user×item×context cross features, predicting one or more engagement probabilities.
4. **Re-ranking** — apply diversity (MMR/DPP), freshness, exploration, and business constraints to the final ordered list.
5. **Logging & training** — impressions and outcomes flow back to train the next models; this closed loop is where bias must be managed.

The interview signal is understanding *why each stage exists* and where features, latency budgets, and biases live.

### Q35. [Theory] Explain the Wide & Deep architecture and what each part contributes.

**Wide & Deep** (Google, 2016) jointly trains two components to balance **memorization** and **generalization**:

```text
            ┌──── Wide (linear) ────┐
sparse  ───►│  cross-product feats  │──┐
features    └───────────────────────┘  │
                                        ├──► σ(sum) ──► prediction
dense + ───►┌──── Deep (MLP) ───────┐  │
embeddings  │  embeddings → hidden  │──┘
            └───────────────────────┘
```

- **Wide part** — a linear model over raw and **cross-product** features (e.g., `installed_app=X AND impression_app=Y`). It **memorizes** specific, frequent feature co-occurrences and exceptions — sharp, sparse rules.
- **Deep part** — embeddings fed through an MLP. It **generalizes** to unseen or rare feature combinations by learning dense representations (handles sparsity, finds patterns the wide part can't).

Jointly training both gives precise memorization *and* smooth generalization. The lesson generalizes: pure deep models can over-generalize (recommend plausible-but-wrong items), while pure linear models can't generalize at all — the hybrid is the point. Successors (DeepFM, DLRM, DCN) automate the cross-feature learning the wide part did manually.

### Q36. [Theory] What is Neural Collaborative Filtering, and does it actually beat matrix factorization?

**Neural Collaborative Filtering (NCF)** replaces MF's fixed **dot product** with a learned neural interaction function. NeuMF combines:
- **GMF** (generalized MF) — element-wise product of user/item embeddings, learnable weights.
- **MLP** — concatenate user/item embeddings and pass through hidden layers to learn an arbitrary interaction.

The pitch: the dot product is a fixed, linear way to combine factors; an MLP can learn non-linear interactions.

**The honest answer interviewers want:** later work (notably "Neural Collaborative Filtering vs. Matrix Factorization Revisited," 2020) showed that a **well-tuned dot-product MF with proper negative sampling often matches or beats** an MLP-learned similarity, and the dot product is far cheaper to serve (it enables ANN retrieval; a learned MLP similarity does not). So NCF demonstrated that embeddings + neural nets are flexible, but the dot product remains the right inductive bias for *retrieval*. Neural complexity pays off more in **ranking** (with rich features and cross terms) than in replacing the similarity function itself.

### Q37. [Theory] Why use sequence/session-based models, and what architectures dominate?

Static CF ignores **order and recency** — but intent is sequential. If you just watched three episodes of a series, the *next* item matters more than your all-time average taste. **Sequence models** predict the next item from the ordered history.

Evolution:
- **Markov chains** — next item depends on the last; simple, limited.
- **GRU4Rec** — RNN over the session sequence; captured longer dependencies.
- **Self-attentive (SASRec)** — a transformer-style self-attention model; attends to all past items, weighting the relevant ones; strong and parallelizable.
- **BERT4Rec** — bidirectional, masked-item training (cloze task), richer context.
- **Transformer-based / generative** — current SOTA; some 2024–2026 systems frame recommendation as **generative sequence modeling** over semantic item IDs (e.g., TIGER-style generative retrieval), predicting the next item token-by-token.

```text
history: [a, b, c, d]  ──► sequence model ──► P(next = e | a,b,c,d)
```

Session-based models shine for **short-video, news, music, and e-commerce sessions** where intent shifts fast and recency dominates. They also handle the "anonymous/logged-out user" case, since they need only the current session, not a long-term profile.

### Q38. [Coding] Implement a leave-one-out evaluation with NDCG@k and Hit Rate@k.

```python
import numpy as np

def evaluate_loo(model_scores, test_item, k=10):
    """
    model_scores: dict candidate_item -> score (includes the held-out
                  positive 'test_item' plus sampled negatives).
    Returns (hit@k, ndcg@k) for this one user.
    """
    ranked = sorted(model_scores, key=model_scores.get, reverse=True)
    topk = ranked[:k]
    if test_item not in topk:
        return 0.0, 0.0
    rank = topk.index(test_item)          # 0-based position
    hit = 1.0
    ndcg = 1.0 / np.log2(rank + 2)        # IDCG=1 for a single positive
    return hit, ndcg

def mean_metrics(per_user_results):
    hits  = np.mean([h for h, _ in per_user_results])
    ndcgs = np.mean([n for _, n in per_user_results])
    return {"HR@k": hits, "NDCG@k": ndcgs}

# Example: one user, held-out item id 42 plus 99 sampled negatives
scores = {42: 0.91, 7: 0.95, 13: 0.40, 88: 0.20}   # (truncated)
print(evaluate_loo(scores, test_item=42, k=10))
```

This **leave-one-out with sampled negatives** protocol (one positive + N negatives, rank the positive) is the standard in sequence-model papers. Caveat interviewers like: sampled-negative ranking metrics can be **biased and inconsistent** vs full-ranking (Krichene & Rendle, 2020) — for trustworthy comparison, rank against the *full* catalog when feasible.

### Q39. [Theory] How do you handle exposure/position bias in training data?

Logged feedback is confounded by **where** an item was shown: an item at position 1 gets clicked more regardless of relevance. Training naively on clicks teaches the model to reproduce the old ranker's layout, not true relevance.

Approaches:
- **Inverse Propensity Scoring (IPS)** — weight each observed example by `1 / P(observed | position)`. Items shown in bad positions but still clicked get up-weighted; this de-biases the loss toward true relevance. Requires estimating propensities (often via a **position-bias / examination model**).
- **Position as a feature with the "two-tower position trick"** — feed position into a *separate* shallow tower during training so the main model learns position-independent relevance; at serving time set position to a constant (e.g., 1). Used in YouTube-style systems.
- **Counterfactual / off-policy learning** — train with estimators (IPS, doubly-robust) that correct for the logging policy.
- **Randomization / exploration data** — a small fraction of random or interleaved traffic gives unbiased samples to calibrate propensities.

```text
naive:   loss treats a click at rank 1 == click at rank 20  (biased)
IPS:     loss weights by 1/P(seen|rank) → rewards true relevance
```

The principle: **clicks measure relevance × examination**; you must factor out examination to learn relevance.

### Q40. [Theory] How do you serve embeddings and keep them fresh at scale?

Two-tower retrieval depends on an up-to-date item index and live user embeddings. The serving stack:

1. **Item embeddings** — recompute in batch (hourly/daily) when the item tower or catalog changes; rebuild or incrementally update the **ANN index** (Faiss/ScaNN/Milvus). New items get embeddings from features immediately (cold-start friendly) and are inserted into the index.
2. **User embeddings** — for stable long-term taste, precompute in batch. For session-aware retrieval, compute the user embedding **at request time** from recent actions, so the same item index serves a freshly-computed query.
3. **Index management** — blue/green index swaps, versioning, and **embedding-version compatibility** (user and item towers must be the *same version*, or dot products are meaningless). A mismatch silently destroys quality.
4. **Sharding & replication** — billion-item indexes are sharded; queries fan out and merge top-k. PQ/IVF-PQ compress for memory.

```text
item tower (batch) ─► item embeddings ─► ANN index ──┐
                                                      ├─► retrieve top-k
user tower (request) ─► user embedding ──────────────┘
   ^ MUST be the same model version
```

The classic production bug: redeploying one tower without the other, so the embedding spaces diverge.

### Q41. [Practical] How would you design recommendations for a brand-new e-commerce marketplace (system cold start)?

Bootstrap from content and rules, then graduate to learned models as data accrues:

1. **Phase 0 — no behavior**: editorial/curated lists, category browse, and **content-based** similarity from product metadata (title, description, images via embeddings, price, brand). New-user onboarding (pick a few interests) seeds a profile.
2. **Phase 1 — sparse behavior**: add **co-visitation / co-purchase** counts ("frequently bought together"), trending within category, and simple popularity with recency. Content-based handles new items; popularity handles new users.
3. **Phase 2 — enough behavior**: train **implicit-feedback MF/ALS** or a **two-tower** model; keep content features so new items still embed well. Introduce exploration (bandits) to gather feedback on the long tail.
4. **Cross-cutting**: a **feature store** from day one so features are consistent as you upgrade models; logging discipline so the data you collect is usable; and exploration to avoid baking in early popularity bias.

The framing: **content + rules bridge the gap until collaborative signal exists**, and you instrument exploration so the cold-start data isn't self-fulfilling.

### Q42. [Behavioral] Describe a time you had to balance a model that improved offline metrics but risked harming user experience.

(Use the STAR structure; the interviewer is testing judgment about metric-vs-experience trade-offs and ownership.)

- **Situation** — a re-ranking change lifted offline NDCG and online CTR by reusing the user's most-recent-click signal heavily.
- **Task** — decide whether to ship despite worries it was collapsing diversity.
- **Action** — I added intra-list diversity and catalog-coverage metrics to the A/B dashboard, ran a longer holdback, and looked at *downstream* metrics (next-day return, session length) rather than just CTR. The data showed CTR up but 7-day retention and coverage down — a classic short-term-engagement / long-term-satisfaction trade-off and a feedback-loop risk.
- **Result** — instead of shipping as-is, I added an MMR diversity term and an exploration slice, which kept most of the CTR gain while restoring coverage and retention. We shipped that version and made "beyond-accuracy" metrics a standing gate.

The point to convey: you **distrust a single up-and-to-the-right metric**, you instrument second-order effects, and you optimize for long-term user value over a vanity number.

### Q43. [Theory] What is multi-task / multi-objective ranking and why is it needed?

Real systems care about **several outcomes at once** — click, watch time, like, share, purchase, and "did the user come back tomorrow." Optimizing a single objective (e.g., CTR) leads to clickbait; optimizing watch time alone can favor long, low-quality content.

**Multi-task ranking** trains one model with shared layers and **multiple prediction heads**, one per objective:

```text
            shared bottom (embeddings + layers)
              │        │        │
          [P(click)] [P(watch)] [P(share)] ...   (task heads)
              └────────┬────────┘
        combined score = w1·P(click) + w2·P(watch) + ...
```

Architectures like **MMoE (Multi-gate Mixture-of-Experts)** and **PLE (Progressive Layered Extraction)** let tasks share *and* specialize, mitigating negative transfer between conflicting objectives (YouTube's ranking uses this style). The final ranking score is a **weighted combination** of the heads, where weights encode the product's value model. This is also how you encode business goals (e.g., down-weight engagement-bait, up-weight satisfaction surveys) directly into ranking.

### Q44. [Practical] How do you detect and respond to model staleness/drift in a live recommender?

Recommenders decay because the world moves: new items, trends, seasonality, and shifting user behavior. Monitoring and response:

**Detect**
- **Input drift** — feature distributions shift (PSI/KL divergence on key features), new-item share rising.
- **Prediction drift** — score distributions or recommended-item distributions shift; coverage collapsing.
- **Performance drift** — online CTR/NDCG, conversion, and engagement trending down vs a holdback or vs the same model's launch baseline.
- **Freshness gaps** — share of recommendations that are recently-published items dropping (a sign the model can't surface new content).

**Respond**
- **Frequent retraining / incremental updates** — many systems retrain ranking daily and refresh embeddings hourly; some do online learning.
- **Always-on holdback** — a small slice on the previous model to measure live degradation.
- **Freshness/exploration injection** — guarantee some new-item exposure so the feedback loop doesn't starve fresh content.
- **Automated rollback** — guardrail metrics that trigger reverting to the last good model.

```python
def population_stability_index(expected, actual, bins=10):
    e = np.histogram(expected, bins=bins)[0] / len(expected) + 1e-6
    a = np.histogram(actual,   bins=bins)[0] / len(actual)   + 1e-6
    return np.sum((a - e) * np.log(a / e))   # >0.25 ≈ significant drift
```

---

## 🔴 Expert (15+ yrs)

### Q45. [Theory] How do you reason about and break harmful feedback loops at the system level?

Feedback loops are the deepest structural risk: the model shapes the data that trains its successor, so biases compound (popularity concentration, filter bubbles, demographic skews, even radicalization in content feeds).

System-level reasoning and interventions:
- **Treat the system as a closed loop, not a static predictor.** Evaluate long-horizon effects (catalog diversity over months, creator-side fairness, retention) — not just next-session CTR.
- **Inject exogenous signal** so the loop has an external reference: randomized exploration traffic, **bandits** (Thompson sampling / LinUCB) for principled explore-exploit, and interleaving for unbiased comparison.
- **De-bias the training signal** with propensity weighting (IPS) so exposure is factored out of "preference."
- **Constrain the optimization**: fairness/diversity/long-tail constraints in re-ranking; caps on how concentrated recommendations can become (Gini guardrails); creator-side exposure floors.
- **Counterfactual / off-policy evaluation** to estimate what a new policy *would* do without first deploying it and corrupting the loop.
- **Two-sided objectives**: in marketplaces, model both consumer relevance and provider/creator outcomes, since pure consumer-CTR optimization starves supply.

The senior framing: a recommender is a **socio-technical control system**. Optimizing the myopic metric is locally rational and globally destructive; you must add external exploration, de-biasing, and long-horizon objectives to keep it healthy.

### Q46. [Theory] Compare generative retrieval / LLM-based recommenders with the classic two-tower + ANN approach.

Two paradigms coexist in 2026:

**Classic embedding retrieval (two-tower + ANN)**
- Items → embeddings → ANN index; retrieve by nearest neighbor.
- Mature, fast, scalable to billions, well-understood ops.
- Limited by the embedding bottleneck (a single fixed user vector) and by the separation of retrieval and ranking.

**Generative retrieval** (e.g., TIGER-style)
- Assign each item a **semantic ID** (a short sequence of codebook tokens from its content embedding), then train a **sequence-to-sequence transformer to generate the next item's ID** autoregressively given history.
- Pros: no separate ANN index (the model *generates* candidates), better cold-start (semantic IDs are content-derived so new items share structure), memorization + generalization in one model, and natural handling of sequence.
- Cons: harder to scale to huge catalogs, decoding latency, beam-search diversity issues, and operational immaturity vs ANN.

**LLM-as-recommender / conversational rec**
- Use an LLM for reasoning, explanation, cold-start from natural-language preferences, and zero-shot generalization. Often as a **re-ranker or feature generator**, or for conversational recommendation, rather than the core retrieval engine (cost/latency/hallucination of nonexistent items are real constraints).

```text
two-tower:   query vector ─► ANN ─► candidate ids        (lookup)
generative:  history tokens ─► transformer ─► item-id tokens  (generate)
LLM:         "I want a cozy mystery like X" ─► reasoned, explained recs
```

The expert answer: classic two-tower + ANN remains the **scalable default for retrieval**; generative and LLM approaches are ascendant for **cold-start, sequence, explanation, and conversational** settings, increasingly hybridized with the funnel rather than replacing it wholesale.

### Q47. [Theory] How do you design recommendations for a two-sided marketplace?

In marketplaces (rides, gig labor, ads, creators, e-commerce sellers) you serve **two populations** whose interests can conflict: consumers want the most relevant items; providers/creators want exposure and fair distribution. Pure consumer-CTR optimization concentrates demand on a few winners and starves new/long-tail supply, eventually collapsing the marketplace.

Design considerations:
- **Two-sided objectives** — jointly optimize consumer relevance *and* provider outcomes (coverage, fairness, supply health). Often a constrained optimization: maximize consumer utility subject to exposure floors per provider segment.
- **Fairness of exposure** — guarantee long-tail/new providers a minimum share of impressions; measure exposure inequality (Gini), not just relevance.
- **Marketplace dynamics** — recommendations affect supply behavior (creators chase the algorithm); model the equilibrium, not a static snapshot.
- **Pacing & global constraints** — budget pacing for ads, inventory limits, capacity (a restaurant can't take infinite orders) — these are **global** constraints solved with allocation/optimization on top of per-item scores.
- **Cold start for supply** — new providers need exposure to bootstrap, so exploration on the supply side is a business necessity, not just a modeling nicety.

The framing: it's **constrained, multi-stakeholder optimization** over a dynamic system, where ignoring supply health is a path to long-term collapse even if short-term consumer metrics look great.

### Q48. [Behavioral] Tell me about a time you had to make a major architectural decision on a recommender platform with significant trade-offs.

(STAR; testing senior judgment, stakeholder management, and reasoning under ambiguity.)

- **Situation** — our recommendation stack was a monolithic batch pipeline that recomputed recs nightly; the product was moving to a session-driven, real-time feed.
- **Task** — decide whether to incrementally bolt real-time features onto the batch system or invest in a two-stage retrieval-plus-ranking platform with a feature store and online serving.
- **Action** — I framed the decision around the *product's* future (real-time intent was core, not a nice-to-have), prototyped a two-tower retrieval + online ranking slice to de-risk latency, quantified the migration cost, and aligned eng/PM/infra on a phased plan (introduce the feature store first, then retrieval, then ranking) so we delivered value at each step rather than a big-bang rewrite. I was explicit about the trade-off: higher upfront cost and operational complexity vs. a ceiling on the batch approach.
- **Result** — phased migration shipped real-time recs with materially better engagement, and the feature store paid off across other models. Crucially, the phased approach meant we could have stopped early if metrics didn't justify continuing.

The signal to convey: you tie architecture to **product direction**, de-risk with prototypes and quantified trade-offs, prefer **phased/reversible** migrations over big-bang rewrites, and bring stakeholders along.

### Q49. [Theory] How do you do principled exploration in recommenders, and what are the trade-offs?

Exploration counteracts feedback loops and gathers data on under-served items, at the cost of some short-term relevance. Approaches, roughly in order of sophistication:

- **Epsilon-greedy** — show a random/long-tail item with probability ε. Simple, but uniform exploration wastes impressions on clearly-bad items.
- **Thompson sampling** — sample from the posterior over item value and act on the sample; explores in proportion to uncertainty. Strong default, easy to implement with Beta/Bayesian models.
- **UCB / LinUCB** — pick items by score + an uncertainty bonus; **contextual bandits** (LinUCB) condition on user/context features, the right tool when context matters (news, ads).
- **Bayesian / deep contextual bandits** — neural reward models with uncertainty (dropout, ensembles) for high-dimensional contexts.

```text
explore too little →  feedback loop, stale catalog, missed gems
explore too much  →  user sees junk, short-term metrics suffer
bandits           →  explore in proportion to uncertainty (efficient)
```

Trade-offs and pitfalls:
- **Short-term cost vs long-term gain** — exploration lowers immediate CTR but improves long-term catalog health and model quality; you must measure the long horizon to justify it.
- **Non-stationarity** — item value drifts (trends), so you need discounting/decay; a converged bandit that stops exploring goes stale.
- **Delayed/partial feedback** — conversions arrive late; rewards are noisy and biased by exposure.
- **Safety** — never explore into clearly harmful or ineligible content; constrain the exploration space.

The expert point: exploration is **investment in future data quality**, and bandits make that investment efficient by spending exploration budget where the model is most *uncertain* rather than uniformly.

### Q50. [Theory] What are the hardest evaluation problems in recommenders, and how do you address them?

Evaluation is the part most likely to mislead a senior team. The hard problems:

1. **Offline–online gap** — offline metrics computed on logged, biased data routinely fail to predict online results. *Address with* counterfactual estimators (IPS, doubly-robust), exploration data for unbiased samples, and treating offline only as a *filter*.
2. **Exposure/selection bias** — you can't score items the old system never showed. *Address with* propensity weighting and randomized/interleaving traffic.
3. **Sampled-metric bias** — ranking against sampled negatives gives inconsistent metrics (Krichene & Rendle). *Address with* full-catalog ranking where feasible, or bias-corrected estimators.
4. **Feedback-loop contamination** — your eval data is generated by the very policy you're evaluating, so improvements can be illusory. *Address with* off-policy evaluation and long-horizon holdbacks.
5. **Metric–value misalignment** — proxy metrics (CTR) diverge from true value (satisfaction, retention, well-being); engagement can be optimized in harmful directions. *Address with* multi-objective evaluation, satisfaction surveys, long-term holdouts, and guardrail metrics.
6. **Long-term vs short-term** — A/B tests measure days, but the costs (filter bubbles, supply collapse, churn) play out over months. *Address with* long-running holdbacks, diversity/coverage tracking, and counterfactual long-term estimators.
7. **Interaction/network effects** — in marketplaces and social feeds, units interfere (SUTVA violated), so naive A/B is biased. *Address with* cluster/switchback experiments and marketplace-aware designs.

```text
trust hierarchy:
  long-term online holdback  > A/B test > interleaving
  > counterfactual offline eval > sampled offline metric
```

The senior stance: **no single number is trustworthy**; you triangulate offline filters, online experiments, counterfactual estimators, and long-horizon guardrails, and you stay suspicious of any metric that improves while user value might be quietly degrading.

---

## 🧩 Extended Questions — Set 1: Deeper theory & internals

This set drills below the architecture diagrams into the mathematics and mechanics: *why* the dot product is the retrieval inductive bias, what an ANN graph is actually doing edge by edge, how losses shape the embedding geometry, and the numerical and statistical internals that decide whether a recommender learns the right thing. The questions deliberately go deeper than the earlier tiers rather than restating them.

### 🟢 — extended

#### Q51. [Theory] Why is the dot product, not Euclidean distance, the natural scoring function for matrix factorization?

Matrix factorization approximates `R ≈ P·Qᵀ`, so the *predicted* affinity for a `(u,i)` pair is **defined** as the dot product `pᵤ·qᵢ` — it is what the factorization literally reconstructs. That has three consequences:

- **Magnitude carries meaning.** Unlike cosine or normalized distance, the dot product lets the embedding *norm* encode something useful: a popular item or a highly-active user can have a larger-norm vector, and that norm raises every score it participates in. This is how MF naturally absorbs popularity into the geometry (often you still add an explicit bias term to keep the factors about *taste*).
- **It linearizes into bias + interaction.** `r̂ = μ + bᵤ + bᵢ + pᵤ·qᵢ` cleanly separates "how high this user rates," "how popular this item is," and "do their tastes interact." Euclidean distance has no such additive decomposition.
- **It is what retrieval indexes support.** Maximum-inner-product search (MIPS) is the operation ANN libraries optimize, so a dot-product model drops straight into a Faiss/ScaNN index. A model trained on L2 distance would need an L2 index and a different geometric story.

The subtle point: dot product and Euclidean distance are *related* (`‖a−b‖² = ‖a‖² + ‖b‖² − 2a·b`) but not interchangeable — distance penalizes large norms, the dot product rewards them, and that sign flip is exactly why MF prefers the dot product.

#### Q52. [Theory] What does the "rank" in low-rank factorization actually control?

The latent dimension `d` is the **rank** of the approximation `P·Qᵀ`, and it sets the model's *capacity to express distinct taste patterns*. Concretely:

- A rank-`d` matrix can represent at most `d` independent "directions" of variation across users and items. With `d=1`, every user is just a scalar multiple of one global pattern (essentially popularity). As `d` grows, the model can carve out genres, moods, and niche affinities as separate directions.
- **Too small** → underfitting: the model collapses genuinely different tastes onto the same axis and recommends generically.
- **Too large** → overfitting and wasted capacity: with enough dimensions the model starts fitting noise in the sparse observed entries, which is why regularization `λ(‖P‖²+‖Q‖²)` is essential and grows in importance with `d`.

The deep intuition: low rank is an **inductive bias that the world is simple** — that millions of user–item preferences are governed by a few hundred underlying factors. That assumption is what lets the model *generalize* into the empty cells instead of memorizing. Rank is the knob that trades expressiveness for that generalizing pressure.

#### Q53. [Theory] Why does regularization in matrix factorization shrink embeddings toward zero, and what does that do to predictions?

The L2 penalty `λ(‖pᵤ‖² + ‖qᵢ‖²)` adds a cost proportional to the squared length of every embedding, so the optimizer is rewarded for keeping vectors short unless the data clearly demands otherwise. Effects:

- **Shrinkage toward the mean.** When a user or item has *few* observations, the data signal is weak and the penalty dominates, pulling its embedding toward zero. A near-zero embedding contributes `≈ 0` to the interaction term, so the prediction falls back to `μ + bᵤ + bᵢ` — the global/user/item averages. This is exactly the desired behavior: with little evidence, defer to popularity-style priors.
- **Confidence-scaled trust.** Items with lots of data overcome the penalty and develop expressive, large-norm vectors; sparse items stay timid. Regularization thus acts as an *automatic confidence weighting* by data volume.
- **Conditioning.** It also keeps the per-user/per-item least-squares solves (in ALS) well-conditioned by adding `λI` to the normal-equations matrix, preventing blow-ups when a user has nearly-collinear item vectors.

So regularization is not just an overfitting guard — it is the mechanism by which MF gracefully degrades to sensible defaults for the long tail.

#### Q54. [Practical] When you mean-center ratings before training, what bias are you removing and why does it help?

Mean-centering — subtracting the global mean `μ` (and often per-user `bᵤ` and per-item `bᵢ` offsets) before the factors model the residual — removes **additive rating bias** that has nothing to do with taste *interaction*:

```python
import numpy as np

def center_ratings(R, mask):
    mu = R[mask == 1].mean()
    bu = np.zeros(R.shape[0]); bi = np.zeros(R.shape[1])
    # simple alternating estimate of user/item offsets on the residual
    for _ in range(10):
        for u in range(R.shape[0]):
            idx = mask[u] == 1
            if idx.any(): bu[u] = (R[u, idx] - mu - bi[idx]).mean()
        for i in range(R.shape[1]):
            idx = mask[:, i] == 1
            if idx.any(): bi[i] = (R[idx, i] - mu - bu[idx]).mean()
    return mu, bu, bi
```

What this buys you:
- A user who rates *everything* 4–5 ("easy grader") and one who rates 1–3 ("harsh grader") no longer look like they have opposite tastes — the offset `bᵤ` absorbs the grading style.
- A universally-loved item no longer forces every user's vector to point toward it; its popularity goes into `bᵢ`.
- The latent factors `pᵤ·qᵢ` are then free to model the **interaction** — "does *this* user like *this kind* of item beyond what the averages predict" — which is the genuinely personalized signal. Without centering, the factors waste capacity re-learning these offsets.

#### Q55. [Theory] What is the cosine-vs-dot-product distinction in plain terms, and when does normalizing embeddings change recommendations?

Cosine similarity is the dot product **after** dividing each vector by its length: `cos(a,b) = (a·b)/(‖a‖‖b‖)`. So cosine measures *direction only*; dot product measures direction *and* magnitude.

When does it matter for recommendations?
- **If embedding norms encode popularity/confidence** (typical of MF and two-tower models), then dot product will favor high-norm (popular, well-trained) items, while cosine strips that out and ranks purely by directional match. Switching from dot to cosine can therefore *de-popularize* the list and surface niche items pointing the same direction.
- **If you normalize all embeddings to unit length first, dot product and cosine become identical** — which is exactly the trick used so that an inner-product ANN index computes cosine. Many systems `L2-normalize` precisely to make this equivalence hold and to stabilize training.

Practical rule: normalize when you want similarity to be about taste *direction* and want to neutralize popularity; keep raw dot product when you *want* popularity/confidence to influence the score.

#### Q56. [Theory] Why does cosine similarity break down badly on extremely sparse, high-dimensional interaction vectors?

Two issues compound:

1. **Thin overlap, unstable estimates.** Cosine between two user rows depends only on the items they *both* touched. In a matrix that's 99.9% empty, two users may overlap on one or zero items. A cosine computed from a single shared item is statistically meaningless — it's 1.0 or undefined, with no support. Neighborhood CF on raw sparse vectors is therefore dominated by noise from tiny overlaps.
2. **Concentration of distances (curse of dimensionality).** In very high dimensions, distances and angles between random vectors concentrate — most pairs look roughly equidistant — so the *contrast* between "similar" and "dissimilar" collapses and rankings become brittle.

This is precisely the motivation for moving from raw-vector neighborhood CF to **low-rank embeddings**: projecting users and items into a dense `d`-dimensional space (with `d` in the tens-to-hundreds) gives every pair full support (no missing-overlap problem) and restores meaningful contrast. The embedding step is what makes similarity trustworthy again.

#### Q57. [Coding] Show how shrinkage (significance weighting) fixes unreliable neighborhood similarities.

A raw cosine from few co-ratings is overconfident. Shrinkage multiplies the similarity by a factor that grows with the **number of supporting co-interactions**, pulling thinly-supported similarities toward zero:

```python
import numpy as np

def shrunk_similarity(sim_raw, co_counts, beta=50):
    """
    sim_raw:   raw cosine/Pearson similarity matrix (items x items)
    co_counts: matrix of how many users co-rated each item pair
    beta:      shrinkage strength; higher = trust raw sim only with lots of support
    """
    shrink = co_counts / (co_counts + beta)     # ∈ [0,1), →1 as support grows
    return sim_raw * shrink

# two item pairs: one backed by 3 users, one by 300 users, same raw sim 0.9
sim_raw   = np.array([[1.0, 0.9], [0.9, 1.0]])
co_counts = np.array([[999, 3],   [3,   999]])     # only 3 users co-rated the pair
print(shrunk_similarity(sim_raw, co_counts, beta=50))
# the 0.9 backed by 3 users is shrunk to ~0.05; a 0.9 backed by 300 stays ~0.77
```

The `n/(n+β)` form is the standard significance-weighting trick (it's a Bayesian-flavored shrink toward the prior of "no similarity"). Without it, a coincidence between two users who happen to share one obscure item can dominate recommendations. This is the neighborhood-CF analogue of the regularization that protects MF.

#### Q58. [Theory] What exactly is "the embedding bottleneck" in a two-tower model, and why does it limit expressiveness?

In a two-tower model the user and item never interact until a *single* dot product at the very end. That forces all of the user's relevance to *every* item to be summarized in **one fixed `d`-dimensional vector** computed before any item is seen. That single vector is the bottleneck.

Why it limits the model:
- **No early cross-features.** A ranking model can compute "does *this user's* country match *this item's* language" as an explicit interaction feature. The two-tower can't — it must hope both towers independently encode enough that the dot product reconstructs such interactions. Many genuinely cross-dependent signals are lost.
- **One vector can't express multi-modal taste cleanly.** A user who loves both death metal and lullabies has a taste distribution with two far-apart modes; a single point embedding lands somewhere in between and may retrieve neither well. (Multi-interest/multi-vector user models exist precisely to break this bottleneck.)
- **Capacity ceiling.** The maximum number of distinguishable user–item relationships is bounded by the rank `d` of the interaction it can represent.

This is *why* two-tower is used for **retrieval** (cheap, indexable, high-recall) but a richer cross-feature model is layered on top for **ranking** — the bottleneck that makes retrieval fast is the same bottleneck that makes it imprecise.

### 🟡 — extended

#### Q59. [Theory] Walk through how an HNSW search actually traverses the graph to find nearest neighbors.

HNSW (Hierarchical Navigable Small World) is a **multi-layer proximity graph**. Each node is an item vector; edges connect a node to some of its near neighbors. The layers form a hierarchy: the top layer is sparse (few nodes, long-range links), and density increases as you descend, with layer 0 containing every node.

Search for a query `q`:
1. **Start at the single entry point in the top layer.** Greedily walk to the neighbor closest to `q`; repeat until no neighbor is closer — you've found a local minimum *at that layer*.
2. **Drop down one layer** using that node as the new entry point and greedy-walk again. The long-range top-layer links let you cover huge distance in a few hops (the "highway"); lower layers refine locally (the "streets").
3. **At layer 0**, instead of pure greedy, run a **beam search** keeping the best `efSearch` candidates, expanding their neighbors, until the candidate set stops improving. Return the top-`k`.

```text
L2:  o─────────────o            (sparse highway: big jumps toward q)
       \           /
L1:  o──o────o────o──o          (refine the region)
L0:  o-o-o-o-o-o-o-o-o-o-o       (dense local search, beam = efSearch)
```

Why it's fast: greedy descent through a small-world graph reaches the neighborhood in `~O(log n)` hops, and only the final layer does fine-grained work. `efSearch` is the recall/latency knob: larger beam → more nodes visited → higher recall, slower query. `efConstruction` and `M` (neighbors per node) set build-time graph quality.

#### Q60. [Theory] What is Product Quantization, and how does it shrink a billion-vector index without scanning every vector exactly?

Product Quantization (PQ) **compresses** each vector into a short code so a billion vectors fit in RAM and distances become table lookups.

How it works:
- **Split** each `d`-dim vector into `m` contiguous sub-vectors (e.g., a 128-dim vector into 8 chunks of 16 dims).
- **Cluster** each sub-space separately with k-means into, say, 256 centroids. Each sub-vector is replaced by the ID (1 byte) of its nearest centroid. A 128-dim float vector (512 bytes) becomes `m=8` bytes — a ~64× compression.
- **Approximate distance** between a query and a stored code via **Asymmetric Distance Computation**: precompute, for the query, the distance from each of its sub-vectors to all 256 centroids in each sub-space (an `m × 256` table). The distance to any stored vector is then just `m` table lookups summed — no need to decompress.

```text
vector → [chunk1|chunk2|...|chunk8] → [c_id1, c_id2, ..., c_id8]  (8 bytes)
query distance = Σ_j  LUT_j[ stored_code[j] ]      ← m adds, no float math
```

Combined as **IVF-PQ**: IVF first restricts the search to a few clusters (`nprobe`), then PQ approximates distances within them. The trade-off is recall loss from quantization error, tuned by `m`, the number of centroids, and `nprobe`. PQ is what makes web-scale ANN memory-feasible.

#### Q61. [Theory] Explain BPR (Bayesian Personalized Ranking) and why a pairwise loss suits implicit feedback better than pointwise regression.

BPR optimizes **relative order** rather than absolute scores. For each user it samples a triplet `(u, i, j)` where `i` is an observed (positive) item and `j` an unobserved one, and maximizes the probability that the model scores `i` above `j`:

```text
objective:  maximize  Σ_(u,i,j)  ln σ( x̂_ui − x̂_uj )  −  λ‖Θ‖²
            where x̂_ui = pᵤ·qᵢ   and   σ is the logistic function
```

Why pairwise beats pointwise for implicit data:
- **Implicit feedback is one-class.** Pointwise regression must assign a target value to unobserved entries (usually 0), asserting "the user dislikes this," which is false — they may simply not have seen it. BPR never makes that claim; it only asserts the *weaker, defensible* statement "the user prefers the thing they engaged with over a random thing they didn't."
- **It directly targets ranking.** Recommendation is a ranking task; optimizing `σ(score_i − score_j)` shapes the embedding so positives sit above negatives — closely aligned with AUC (BPR's expected loss is a smooth surrogate for AUC).
- **Negative sampling is built in.** Each step draws one `j`, making it scalable; the choice of sampling distribution (uniform vs popularity vs hard) controls difficulty.

The conceptual upgrade BPR makes: stop pretending you have negative labels; model **preferences between pairs**, which is all implicit data actually tells you.

#### Q62. [Theory] What are in-batch negatives, and why do they require a logQ / sampled-softmax correction?

In two-tower retrieval training, a mini-batch holds `B` `(user, positive-item)` pairs. **In-batch negatives** reuse the *other* `B−1` items in the batch as negatives for each user — no extra sampling, and the item embeddings are already computed, so it's nearly free. The batch becomes a `B × B` score matrix; the diagonal is positives, off-diagonal are negatives, and you apply softmax across each row.

The catch: items appear as in-batch negatives **in proportion to their popularity**, because popular items show up as positives (and thus as others' negatives) far more often. Plain softmax then over-penalizes popular items, suppressing them in retrieval — the opposite of what you want. The fix is the **logQ correction** (sampled-softmax / sampling-bias correction): subtract `log Q(i)` from each logit, where `Q(i)` is the estimated sampling probability of item `i` (≈ its frequency):

```text
corrected_logit(u, i) = sᵤ·sᵢ  −  log Q(i)
```

This removes the popularity-induced bias so the softmax estimates the *true* conditional `P(item | user)` rather than a frequency-distorted one. Without it, the model systematically under-retrieves head items even though they're genuinely relevant — a well-documented two-tower failure mode (Yi et al., 2019).

#### Q63. [Coding] Implement the in-batch-negatives sampled-softmax loss with logQ correction.

```python
import numpy as np

def in_batch_softmax_loss(user_emb, item_emb, item_logq, temperature=0.05):
    """
    user_emb, item_emb: (B, d) aligned positive pairs (row k = matched pair k).
    item_logq:          (B,) log sampling prob of each in-batch item (popularity).
    Returns mean cross-entropy where the correct class is the diagonal.
    """
    logits = (user_emb @ item_emb.T) / temperature      # (B, B) score matrix
    logits = logits - item_logq[None, :]                # logQ correction per column
    # numerically-stable log-softmax across each row
    logits -= logits.max(axis=1, keepdims=True)
    log_probs = logits - np.log(np.exp(logits).sum(axis=1, keepdims=True))
    targets = np.arange(user_emb.shape[0])              # diagonal = positives
    return -log_probs[np.arange(len(targets)), targets].mean()

B, d = 4, 8
rng = np.random.default_rng(0)
u = rng.normal(size=(B, d)); v = rng.normal(size=(B, d))
logq = np.log(np.array([0.4, 0.1, 0.1, 0.4]))          # items 0,3 are popular
print(in_batch_softmax_loss(u, v, logq))
```

Two knobs interviewers ask about: **temperature** sharpens/softens the softmax (low temperature → harder separation, common ~0.05), and the **logQ subtraction** is what stops popular items from being unfairly treated as strong negatives. Drop the correction and head-item recall silently degrades.

#### Q64. [Theory] How does negative-sampling distribution shape the learned embedding geometry?

The negatives you sample define *what the model is taught to push apart*, so they directly sculpt the embedding space:

- **Uniform negatives** are dominated by the long tail (most items are unpopular), so the model mostly learns "positive ≠ random obscure item" — easy gradients that quickly saturate. The space separates head from tail but leaves *similar* items poorly resolved.
- **Popularity-weighted negatives** (`freq^0.75`, the word2vec exponent) pull popular items in as negatives more often, forcing the model to *distinguish among popular items* and counteracting the exposure advantage popular items get. This generally yields better head-region resolution.
- **Hard negatives** (items the current model already scores highly but the user didn't pick) produce the largest, most informative gradients and sharpen the **decision boundary** — they teach fine distinctions. But too many *false* hard negatives (items the user actually would have liked) inject label noise and can collapse or distort the geometry.

The `0.75` exponent is a deliberate compromise: it dampens the most-frequent items relative to true popularity weighting, giving mid-frequency items enough representation. The senior framing: negative sampling is not a detail — it is an implicit specification of the loss surface, and changing it changes *which distinctions the embedding bothers to encode*.

#### Q65. [Theory] Why can implicit-ALS sum over all unobserved pairs efficiently when there are billions of them?

Naively, implicit-ALS's loss `Σ_(all u,i) c(u,i)(p(u,i) − pᵤ·qᵢ)²` ranges over *every* user–item pair — `n_users × n_items`, often trillions. The trick (Hu–Koren–Volinsky) is an **algebraic decomposition** that exploits the fact that for unobserved pairs `c(u,i) = 1` (baseline confidence) and `p(u,i) = 0`.

For the per-user least-squares solve you need `Qᵀ C^u Q` and `Qᵀ C^u p^u`, where `C^u` is the diagonal confidence matrix for user `u`. Split confidence as `C^u = I + (C^u − I)`:

```text
Qᵀ C^u Q  =  QᵀQ  +  Qᵀ (C^u − I) Q
              ↑           ↑
       precompute ONCE   only nonzero for items the user actually touched
       (shared by all    (a handful of terms, not the whole catalog)
        users)
```

`QᵀQ` is a single `d × d` matrix computed *once per iteration* and reused for every user. The correction term `Qᵀ(C^u − I)Q` is nonzero only on the user's **observed** items (since `C^u − I = 0` elsewhere), so each user costs `O(n_observed_u · d² )`, not `O(n_items · d²)`. That reduction from "all items" to "observed items" is what makes implicit-ALS tractable at scale — you account for all the implicit zeros mathematically without ever iterating over them.

#### Q66. [Practical] Why does the choice between MIPS and cosine retrieval interact with how you trained the model, and how do you avoid a silent mismatch?

Retrieval must use the **same geometry the model was trained under**, or scores are inconsistent and recall drops without any error being raised.

- If the model was trained with an **unnormalized dot product** (norms encode popularity/confidence), retrieval must do **maximum-inner-product search**. Indexing with a cosine/L2 metric throws away the norm information the model relies on, demoting exactly the high-confidence items the dot product meant to favor.
- If the model was trained with **cosine** (embeddings L2-normalized during training), you must **L2-normalize at index *and* query time**, then either use a cosine index or an inner-product index on the normalized vectors (where dot = cosine).

```python
import numpy as np
def prepare_for_index(emb, trained_with_cosine):
    if trained_with_cosine:                    # must normalize to match training
        emb = emb / (np.linalg.norm(emb, axis=1, keepdims=True) + 1e-9)
    return emb.astype("float32")               # and use METRIC_INNER_PRODUCT
```

How to avoid the silent mismatch: (1) **store the metric and normalization as metadata alongside the embedding version** and assert it at index-build and query time; (2) normalize queries with the *exact* same function as items; (3) add a canary test that re-ranks a small set with brute-force exact scoring and checks the ANN top-k overlaps. The classic production bug is a normalization applied to items but forgotten on the query (or vice versa), which quietly halves recall.

### 🟠 — extended

#### Q67. [Theory] Derive why a softmax recommendation loss implicitly performs popularity correction, and where it leaks.

A full-softmax retrieval model estimates `P(i | u) = exp(sᵤ·sᵢ) / Σ_j exp(sᵤ·sⱼ)`. The gradient of the log-likelihood for a positive `i` is:

```text
∂/∂sᵢ  log P(i|u)  =  sᵤ · ( 1 − P(i|u) )           (pull positive up)
∂/∂sⱼ  log P(i|u)  = −sᵤ · P(j|u)        for j≠i     (push others down,
                                                      weighted by their prob)
```

The "push down" on every other item is weighted by the model's *current* probability of that item. Popular items the model already scores high get pushed down harder — a built-in self-balancing that prevents any item from dominating *if you could afford the full normalizer*.

Where it leaks: you can't compute the full sum over millions of items, so you **approximate** it with sampled negatives (in-batch or otherwise). The approximation distorts the normalizer by the sampling distribution `Q(i)`. That's the entire reason for the **logQ correction** — without it the sampled softmax estimates `P(i|u)·something(Q)` instead of `P(i|u)`, reintroducing the popularity bias the full softmax would have removed. So the elegant self-correction of softmax is real but *only* survives sampling if you correct for the sampler. This is the theoretical bridge between "softmax debiases popularity" and "in-batch training needs logQ."

#### Q68. [Theory] What is the relationship between matrix factorization and a single-layer neural network, and why did "Neural CF vs MF revisited" conclude the dot product wins for retrieval?

MF *is* a shallow neural network: user and item one-hot inputs feed an embedding layer (the lookup tables `P`, `Q`), and the output is their dot product. NeuMF's MLP variant replaces that fixed dot product with `MLP([pᵤ; qᵢ])` — a learned, non-linear interaction.

The "revisited" finding (Rendle et al., 2020): a **well-tuned dot-product MF with proper negative sampling matches or beats** the MLP-learned similarity, despite the MLP's greater flexibility. Why:
- **Inductive bias.** The dot product is the *right* prior for "two latent-factor vectors interacting." An MLP must *learn* that bilinear structure from data, and with sparse implicit feedback it often learns a worse approximation of it — flexibility without enough data is a liability.
- **Optimization & tuning.** Much of NCF's early apparent advantage came from weaker MF baselines (under-tuned regularization, learning rate, negative sampling). Tuned fairly, MF closes the gap.
- **Serving decides it.** Even if an MLP similarity were marginally better, it **cannot be indexed by ANN** — scoring requires running the MLP over every candidate, which defeats sub-linear retrieval. The dot product enables MIPS; that operational fact is dispositive for the retrieval stage.

The nuanced verdict: neural complexity earns its keep in **ranking** (rich features, explicit cross terms over a small candidate set), not in replacing the similarity function in **retrieval**, where the dot product's indexability and matched inductive bias make it the right default.

#### Q69. [Theory] Explain how DCN/DeepFM automate the cross-feature engineering that Wide & Deep did by hand, at the mechanism level.

Wide & Deep's "wide" part needs humans to specify cross-products (`installed_app=X AND impression=Y`). DeepFM and DCN learn feature interactions automatically:

- **DeepFM** adds a **Factorization Machine** component beside the deep MLP. The FM models every pairwise feature interaction as the dot product of the features' embeddings: `Σ_{i<j} ⟨vᵢ, vⱼ⟩ xᵢ xⱼ`. Because each interaction is parameterized by per-feature embedding vectors (not a free parameter per pair), it learns **all** second-order crosses with `O(k·n)` parameters and generalizes them even to crosses unseen in training — solving the sparsity that kills hand-built crosses. The FM and deep parts **share the same embeddings**, so there's no manual feature engineering at all.
- **DCN (Deep & Cross Network)** stacks explicit **cross layers**: `x_{l+1} = x_0 · (w_l · x_l) + b_l + x_l`. Each layer increases the polynomial *degree* of feature interaction by one, so an `L`-layer cross network captures bounded-degree feature crosses (degree `L+1`) explicitly and efficiently, in parallel with a deep network. DCN-v2 makes the cross weight a matrix for more capacity.

```text
Wide&Deep:  human writes  cross(app_X, imp_Y)        (manual, sparse, brittle)
DeepFM:     FM learns ⟨v_X, v_Y⟩ for ALL pairs        (automatic, generalizes)
DCN:        stacked cross layers → degree-(L+1) crosses (automatic, bounded degree)
```

The mechanism shared by both: **embed features, then express interactions as products of embeddings**, so an interaction's parameters are tied to the features' learned vectors instead of being free per-combination — which is exactly how they generalize to rare and unseen crosses.

#### Q70. [Theory] In a self-attention sequence recommender (SASRec), what does each attention head compute, and why is causal masking essential?

SASRec represents a user's history as a sequence of item embeddings (plus **positional embeddings**, since attention is order-agnostic without them) and applies self-attention to predict the next item.

What an attention head computes: for the representation at position `t`, the head forms **query** `q_t`, and for every prior position `s ≤ t` a **key** `k_s` and **value** `v_s`. The output is a weighted sum `Σ_s softmax(q_t·k_s/√d) v_s` — i.e., position `t`'s next-item prediction is built from a **learned, content-dependent weighting of earlier items in the session**. Different heads can specialize: one might focus on the most recent item (recency), another on a recurring category across the session (long-range theme).

Why **causal masking** is essential: to predict the item at position `t+1`, the model may only look at positions `≤ t`. The mask sets attention weights to `−∞` (→ 0 after softmax) for all future positions. Without it, the model would attend to the very item it's trying to predict (and items after it) — **label leakage** that yields a model that looks brilliant in training and is useless at serving, where the future genuinely doesn't exist. The mask makes the single forward pass equivalent to predicting *every* next-item simultaneously while keeping each prediction honest about what was available at that moment — which is also why SASRec trains far more efficiently than an RNN.

#### Q71. [Coding] Implement scaled dot-product self-attention with a causal mask (the SASRec core).

```python
import numpy as np

def causal_self_attention(X, Wq, Wk, Wv):
    """
    X: (T, d) sequence of item+position embeddings (one row per timestep).
    Wq, Wk, Wv: (d, d) projection matrices.
    Returns (T, d): each row attends only to itself and earlier positions.
    """
    T, d = X.shape
    Q, K, V = X @ Wq, X @ Wk, X @ Wv
    scores = (Q @ K.T) / np.sqrt(d)                 # (T, T) affinity
    # causal mask: position t may not see s > t
    mask = np.triu(np.ones((T, T), dtype=bool), k=1)
    scores[mask] = -np.inf
    scores -= scores.max(axis=1, keepdims=True)     # stability
    attn = np.exp(scores); attn /= attn.sum(axis=1, keepdims=True)
    return attn @ V                                 # (T, d) contextized reps

rng = np.random.default_rng(0)
T, d = 5, 8
X = rng.normal(size=(T, d))
Wq, Wk, Wv = (rng.normal(size=(d, d)) * 0.1 for _ in range(3))
out = causal_self_attention(X, Wq, Wk, Wv)
print(out.shape)        # (5, 8); row t summarizes items 0..t only
```

The `np.triu(..., k=1)` upper-triangle mask is the whole game: zeroing future attention is what prevents the next-item prediction from peeking at the answer. The final row `out[-1]` is the user's session representation used to score next-item candidates by dot product.

#### Q72. [Theory] How does BERT4Rec's bidirectional masked-item objective differ from SASRec's left-to-right one, and what is the train/serve discrepancy it introduces?

SASRec is **unidirectional**: causal masking lets each position see only the past, and it's trained to predict the next item at every step. BERT4Rec borrows masked-language-modeling: it **randomly masks items in the sequence** and trains the model to reconstruct them using context from **both directions** (a *cloze* task). Bidirectionality lets the representation of an item depend on what came *before and after*, capturing richer context (an item's meaning is informed by later choices in the session).

The discrepancy: at **serving** time you want to predict the item that comes *after* the user's last action — but BERT4Rec was trained to fill *interior* masks with two-sided context, and there is no "future" at inference. The standard fix is to **append a [mask] token to the end** of the sequence at serving and predict it, but this position was under-represented during training (interior masks dominated), creating a **train/serve mismatch**. SASRec has no such mismatch — its next-item training objective is exactly its serving task.

The trade-off interviewers want articulated: BERT4Rec's bidirectional context can improve representation quality, but its cloze objective is **misaligned with the autoregressive serving task**, whereas SASRec's left-to-right objective is perfectly aligned, cheaper, and often competitive — which is why SASRec-style causal models remain a strong, operationally simpler default.

#### Q73. [Theory] What are semantic IDs in generative retrieval, and why do they help cold start and storage compared to atomic item IDs?

Classic retrieval treats each item as an **atomic ID** with its own free embedding row — no structure is shared between items, so a brand-new item's embedding starts from scratch (cold start), and the embedding table grows linearly with the catalog.

**Semantic IDs** (as in TIGER) instead assign each item a **short sequence of discrete codebook tokens** derived from its *content* embedding via residual/hierarchical quantization (e.g., item → `[c₁=42, c₂=7, c₃=19]`). A sequence-to-sequence transformer is then trained to **generate** the next item's token sequence given the user's history — recommendation becomes autoregressive decoding over these tokens.

Why this helps:
- **Cold start.** Because the ID is *content-derived*, a new item's semantic ID shares early tokens with existing similar items (same coarse cluster). The generative model already knows how to produce those tokens, so the new item is reachable on day one without ever having been interacted with — its structure is inherited, not learned from clicks.
- **Storage/parameter sharing.** Items are represented by a small shared **codebook** (e.g., a few thousand centroids across a handful of levels) rather than one independent vector per item. A catalog of hundreds of millions collapses to combinations of codebook tokens, so the parameter cost is sub-linear in catalog size, and semantically-similar items literally share token prefixes.
- **Generalization.** Generating IDs token-by-token lets the model compose *novel-but-plausible* item codes, smoothing over the catalog the way subword tokens smooth over a vocabulary.

The costs (decoding latency, ensuring generated IDs map to real items, beam-search diversity) are why it's emerging rather than universal — but the cold-start and storage internals are the genuine advances over atomic IDs.

### 🔴 — extended

#### Q74. [Theory] Why does Inverse Propensity Scoring give an unbiased estimate of true relevance, and what makes it high-variance?

Logged clicks confound **relevance** with **examination** (was the item even seen, given its position). IPS corrects this by reweighting each observed click by the inverse probability that the item was *examined*: `weight = 1 / P(examine | position)`.

**Why it's unbiased.** Model a click as `click = relevance × examination`. If an item at a rarely-examined position (say `P(examine)=0.1`) still got clicked, it's *strong* evidence of relevance — it overcame a 90% chance of going unseen. Up-weighting that example by `1/0.1 = 10` makes its expected contribution equal to what it *would have been* if every position were examined equally. Formally, the expectation of the IPS-weighted loss over the logging policy equals the loss you'd compute under uniform exposure — the propensity weights exactly cancel the exposure distribution, so `E[IPS estimate] = true relevance objective`. That cancellation is the unbiasedness guarantee.

**Why it's high-variance.** The weights `1/P(examine)` blow up when propensities are small: a position examined with probability 0.01 contributes a weight of 100, so a single noisy click there dominates the gradient and the estimator's variance explodes. Rare events with tiny propensities inject enormous, unstable terms. Mitigations: **clipping/capping** propensities (`max(P, τ)`), **self-normalized IPS** (divide by the sum of weights to stabilize scale), and **doubly-robust estimators** that combine IPS with a direct reward model so that low-variance model predictions carry the load where propensities are tiny. The bias–variance tension is the whole story: IPS buys unbiasedness with variance, and the practical art is taming that variance without reintroducing bias.

#### Q75. [Coding] Implement clipped and self-normalized IPS estimators for off-policy evaluation.

```python
import numpy as np

def ips_estimate(rewards, logging_prob, target_prob, clip=None, self_norm=False):
    """
    rewards:      observed reward for each logged action (e.g., click/value).
    logging_prob: P(action | context) under the policy that COLLECTED the data.
    target_prob:  P(action | context) under the NEW policy being evaluated.
    Returns an estimate of the new policy's expected reward from logged data.
    """
    w = target_prob / logging_prob                 # importance weights
    if clip is not None:
        w = np.minimum(w, clip)                    # cap to control variance
    if self_norm:                                  # SNIPS: divide by Σw
        return np.sum(w * rewards) / np.sum(w)
    return np.mean(w * rewards)                    # vanilla IPS

rng = np.random.default_rng(0)
n = 10_000
logging_p = rng.uniform(0.02, 0.5, n)              # some tiny propensities
target_p  = rng.uniform(0.02, 0.5, n)
rewards   = (rng.uniform(size=n) < 0.3).astype(float)
print("IPS       :", ips_estimate(rewards, logging_p, target_p))
print("IPS clip10:", ips_estimate(rewards, logging_p, target_p, clip=10))
print("SNIPS     :", ips_estimate(rewards, logging_p, target_p, self_norm=True))
```

Vanilla IPS is unbiased but its variance is driven by the smallest `logging_prob`. **Clipping** caps the worst weights (introducing a little bias for a large variance reduction), and **SNIPS** (self-normalized) divides by the weight sum, which removes scale sensitivity and is usually lower-variance in practice. A doubly-robust estimator would add a reward-model baseline `Σ q̂(x) + w·(r − q̂(x))` so the IPS term only corrects the model's residual — the production-grade choice when propensities are extreme.

#### Q76. [Theory] How do determinantal point processes (DPPs) formalize diversity better than greedy MMR?

MMR is a **greedy heuristic**: pick the next item maximizing `λ·relevance − (1−λ)·max-sim-to-selected`. It's fast but myopic and the redundancy term (max similarity to *one* already-picked item) is ad hoc.

A **DPP** defines a probability distribution over *subsets* where the probability of a set `S` is proportional to the **determinant** of a kernel submatrix `det(L_S)`. The kernel `L` encodes both quality and similarity: `L_{ij} = qᵢ · Sᵢⱼ · qⱼ`, where `qᵢ` is item quality and `Sᵢⱼ` is item–item similarity. Geometrically, `det(L_S)` equals the **squared volume** spanned by the items' (quality-scaled) feature vectors:

```text
two near-identical items  → nearly collinear vectors → tiny volume → low prob
two relevant & dissimilar → near-orthogonal vectors → large volume → high prob
```

So a DPP *natively* assigns high probability to sets that are simultaneously high-quality (long vectors) and mutually dissimilar (spread out), capturing **set-level** diversity rather than pairwise penalties. It models the full interaction among *all* selected items via the determinant, not just nearest-neighbor redundancy. Practically you select via **greedy MAP inference** over the DPP (still greedy, but with a principled volume objective and a single tunable quality–diversity balance), and the kernel is learnable. The advantage over MMR: a coherent probabilistic objective, calibrated quality/diversity trade-off, and a notion of diversity that accounts for the *whole* slate's geometry instead of pairwise maxima.

#### Q77. [Theory] What is the deletion/decay problem in maintaining a live ANN index, and how do graph indexes handle it?

Catalogs change constantly — items are added, removed, restocked, or re-embedded — but the structures that make ANN fast assume a relatively static graph/partitioning, so updates are the hard part.

The problems:
- **Deletions in HNSW are not free.** A node sits in a navigable graph; simply dropping it can **sever connectivity**, stranding regions that were only reachable through it and silently degrading recall for unrelated queries. So deletions are usually handled by **soft-delete/tombstoning** (mark as removed, filter from results) and **periodic rebuild/compaction** to actually reclaim the node and repair edges.
- **Drift from re-embedding.** When the item tower is retrained, *every* item embedding moves, invalidating the entire graph's geometry. You can't incrementally patch this — the neighbor relationships are globally stale.
- **IVF centroid drift.** As the data distribution shifts, the original k-means centroids no longer partition well, so `nprobe` clusters cover the query's true neighbors poorly and recall decays.

How production handles it:
- **Blue/green (versioned) index swaps.** Build a fresh index offline from the new embeddings, validate recall against a canary set, then atomically swap traffic — never mutate the live index in place for big changes. This also enforces **embedding-version consistency**: the user tower, item embeddings, and index must all be the same version, or dot products are meaningless.
- **Incremental inserts + periodic full rebuilds.** New items are inserted live (cheap for HNSW); accumulated deletions and drift are cleaned up by scheduled rebuilds.
- **Tombstone + filter at query time** for immediate removals (out-of-stock, policy), decoupling "stop showing this now" from "physically remove from the graph later."

The senior point: an ANN index is a **cache of a geometry that keeps moving**; correctness comes from versioning and rebuild discipline, not from trying to mutate a proximity graph in place.

#### Q78. [Theory] Why do conflicting objectives cause "negative transfer" in multi-task ranking, and how do MMoE and PLE mitigate it architecturally?

In multi-task ranking a shared bottom feeds several heads (click, watch, share, purchase). **Negative transfer** happens when tasks pull the shared parameters in *conflicting* directions: optimizing for "click" (favors clickbait) and "satisfaction" (favors quality) produces gradients that partially cancel in the shared layers, so the shared representation becomes a muddy compromise that serves *no* task well. The more loosely-related the tasks, the worse hard parameter sharing performs.

**MMoE (Multi-gate Mixture-of-Experts)** replaces the single shared bottom with **several expert sub-networks** plus a **per-task gating network** that learns a soft weighting over experts. Each task can route through the experts that suit it — overlapping tasks share experts, conflicting tasks lean on different ones — so the architecture *learns* how much to share per task instead of forcing full sharing. Gradients from conflicting tasks land on different experts and stop fighting over the same weights.

**PLE (Progressive Layered Extraction)** sharpens this by **explicitly separating shared experts from task-specific experts** and stacking extraction layers so each layer progressively disentangles shared from private signal. Task-specific experts are shielded from other tasks' gradients, while shared experts carry the genuinely common signal — reducing the "seesaw" where improving one task regresses another.

```text
hard sharing:  one bottom, all gradients collide        → negative transfer
MMoE:          experts + per-task gates                  → soft, learned sharing
PLE:           shared experts + task-private experts     → explicit disentangling
```

The principle: don't force tasks to share *everything*; give the model the structural freedom to **share what's common and isolate what conflicts**, which is exactly what gating and shared/private expert separation provide.

#### Q79. [Theory] Why are A/B tests biased in two-sided marketplaces and social feeds, and what experiment designs restore validity?

Standard A/B testing assumes **SUTVA** — the Stable Unit Treatment Value Assumption — that a unit's outcome depends only on *its own* treatment, with no interference between units. Marketplaces and social feeds violate this routinely:

- **Shared inventory / supply (marketplace interference).** If the treatment makes treated users buy more of a scarce item, fewer remain for control users — the control's outcome is *depressed by* the treatment. The measured treatment effect is inflated because control was contaminated; both arms compete over the same finite supply.
- **Network/social spillover.** If treated users share more content, their control friends see and engage with that content, so control's metrics rise *because of* the treatment, **shrinking** the apparent effect. Direction of bias depends on the interference mechanism.
- **Learning/budget interference.** Treatment and control may share a model, a pacing budget, or an exploration pool, coupling the arms.

Designs that restore validity:
- **Cluster-randomized experiments.** Randomize at the level of an interference-contained unit — a geographic market, a supply region, a social community detected by graph clustering — so spillover happens *within* a unit (same arm) rather than across arms. Reduces interference at the cost of statistical power (fewer effective units, higher variance).
- **Switchback experiments.** Alternate the *whole system* between treatment and control over time intervals (common for pricing/dispatch in ride-hailing), so each interval's market is internally consistent; account for temporal correlation and carryover.
- **Budget-split / two-sided designs.** Split *supply* as well as demand, or run marketplace-aware estimators that model the equilibrium so the counterfactual accounts for supply reallocation.

The expert framing: in interfering systems the naive A/B effect estimates the *partial-equilibrium* response, not the *general-equilibrium* effect you'd get at full rollout. You either contain interference within randomization units (clusters/switchbacks) or model the market dynamics explicitly — otherwise you ship a number that won't hold when everyone gets the treatment.

#### Q80. [Theory] How do you reason about the bias–variance trade-off across the full recommender evaluation stack?

Every estimator in the stack sits somewhere on a bias–variance spectrum, and the senior skill is knowing which you're holding:

- **Sampled offline metrics** — *low variance, high bias.* Ranking a positive against a few sampled negatives is cheap and stable run-to-run, but systematically inconsistent with full-catalog ranking (Krichene & Rendle) and contaminated by the logging policy's exposure. Treat as a coarse filter only.
- **Full-catalog offline metrics** — *lower bias on ranking, still exposure-biased.* Removes sampling bias but not the closed-loop/exposure bias baked into which items were ever logged.
- **IPS / off-policy estimators** — *low bias, high variance.* Unbiased in expectation but variance explodes with small propensities; clipping trades a little bias back for stability.
- **Doubly-robust** — *engineered bias–variance balance.* Combines a (possibly biased) reward model with the (unbiased, noisy) IPS correction so it's consistent if *either* component is right, with variance lower than pure IPS.
- **Interleaving** — *low variance, moderate bias.* Very sensitive (controls within-user comparison) so it needs little traffic, but measures a narrower notion of preference than a full A/B.
- **A/B test** — *low bias (modulo interference), higher variance/cost.* Closest to truth for the measured horizon, but SUTVA violations reintroduce bias in marketplaces, and it's slow/expensive.
- **Long-term holdback** — *lowest bias on true value, highest variance/cost.* Catches feedback-loop and retention effects that all the above miss, at the price of long duration and large opportunity cost.

```text
bias ↓ / cost & variance ↑  →  sampled offline → full offline → IPS/DR
                                → interleaving → A/B → long-term holdback
```

The reasoning discipline: **no single estimator is both cheap and trustworthy**, so you build a *funnel of evaluators* — high-variance-tolerant cheap metrics to filter many candidates, progressively lower-bias/higher-cost methods to confirm the few survivors — and you stay alert that the cheapest estimators are exactly the most biased toward reproducing the current system. Triangulating across the stack, not trusting any one number, is the whole game.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

This set is deliberately operational: the pager-at-2am scenarios, the "metrics moved and nobody knows why" investigations, the data pipeline traps, and the small but load-bearing pieces of code that hold a recommender together in production. Where Set 1 went deep on internals, this set goes wide on *what actually breaks* and how you diagnose and fix it. The questions assume the architecture from the earlier tiers and push on the seams between training, serving, data, and the business.

### 🟢 — extended

#### Q81. [Practical] Your offline NDCG jumped 30% overnight after a data pipeline change, but nothing about the model changed. What do you check first?

A metric moving without a model change almost always means the **data or the evaluation harness** changed, not the model's quality. Work from most-likely to least:

1. **Future leakage into the training/eval set.** The classic cause of a sudden "free" jump: a join or filter change let post-cutoff interactions into training, or the temporal split boundary shifted so the holdout now overlaps training. Verify the split timestamps and that no test interaction predates the train cutoff.
2. **Label definition changed.** Did "relevant" silently broaden (e.g., now counting impressions or dwell-time as positives), or did the holdout shrink to easier users? A 30% jump from a label change is common.
3. **Candidate set / negative set changed.** If the eval now ranks against fewer or easier negatives (e.g., sampled negatives dropped from 999 to 99, or a popularity filter removed hard negatives), every metric inflates without any real improvement.
4. **Deduplication or filtering.** Removing duplicate events, bot traffic, or already-seen items changes the denominator of recall and the difficulty of the task.

The discipline: **a metric improvement you can't explain is a bug until proven otherwise.** A real model change should be *attributable*; a pipeline-only change that lifts metrics is a red flag that the evaluation got easier, not the model better. The fastest confirmation is to re-run the *old* pipeline and *old* model on the *new* eval data — if the old model also jumps, the eval changed.

#### Q82. [Practical] New users in your app get terrible recommendations for the first few sessions. Walk through a pragmatic fix.

This is the **new-user cold-start** problem, and the fix is a graceful fallback chain rather than one silver bullet:

1. **Immediate (zero signal):** serve a **popularity-with-recency** baseline, ideally segmented by whatever context you *do* have at signup — device, locale, referral source, time of day. Even coarse segmentation beats a global top-N.
2. **Onboarding capture:** a lightweight "pick a few interests / follow a few items" step seeds a content-based profile from the very first screen. Even three selections move you off pure popularity.
3. **Fast session adaptation:** use a **session-based / sequence model** that needs only the current session, so by the second or third click you're personalizing on in-session behavior, not a long-term profile you don't have yet.
4. **Blend and decay:** interpolate `score = w·personalized + (1−w)·popularity`, where `w` ramps up with the number of observed interactions, so the transition from cold to warm is smooth rather than a jarring switch.

```python
def cold_start_weight(n_interactions, full_trust_at=20):
    # 0 at signup, →1 as the user accumulates history
    return min(1.0, n_interactions / full_trust_at)
```

The framing interviewers want: cold start isn't binary, it's a **confidence ramp** — fall back to priors when you know nothing, and blend toward personalization as evidence accumulates.

#### Q83. [Coding] Write a function that merges candidates from multiple retrieval sources, dedups, and keeps each item's best source and score.

Production retrieval unions several sources (two-tower ANN, co-visitation, trending). You must merge them while remembering provenance (useful for debugging and for source-level diversity).

```python
def merge_candidates(source_lists, source_weights=None):
    """
    source_lists: dict source_name -> list of (item_id, raw_score)
    source_weights: optional dict source_name -> multiplier
    Returns list of dicts sorted by weighted score, deduped to the best entry.
    """
    weights = source_weights or {}
    best = {}                                   # item_id -> best entry
    for source, items in source_lists.items():
        w = weights.get(source, 1.0)
        for item_id, raw in items:
            score = raw * w
            cur = best.get(item_id)
            if cur is None or score > cur["score"]:
                best[item_id] = {"item_id": item_id, "score": score,
                                 "source": source, "raw": raw}
    return sorted(best.values(), key=lambda d: d["score"], reverse=True)

cands = merge_candidates(
    {"two_tower": [(1, 0.9), (2, 0.5)],
     "trending":  [(2, 0.8), (3, 0.7)]},
    source_weights={"two_tower": 1.0, "trending": 0.6},
)
# item 2 appears in both; keeps the higher weighted score and its source tag
```

Why keep the source tag: when retrieval recall drops you need to know *which source* regressed, and re-ranking often wants per-source caps (e.g., "no more than 5 trending items") to preserve diversity across sources.

#### Q84. [Practical] A stakeholder says "the recommendations feel repetitive." How do you turn that vague complaint into something measurable and fixable?

Translate the subjective complaint into **list-level and cross-session metrics**, then locate where repetition is introduced:

1. **Quantify it.** Compute **intra-list diversity** (1 − average pairwise item similarity within a single list) and **cross-session repetition** (fraction of items a user has already seen recently that reappear). "Feels repetitive" usually shows up as low intra-list diversity *or* high already-seen overlap.
2. **Localize it.** Repetition is introduced at a specific stage: retrieval may be returning near-duplicates (e.g., many items from one creator/series), ranking may be over-weighting a single strong signal (last-click), or there may be **no de-dup / no diversity step** at all in re-ranking.
3. **Fix at the right stage.** Add a **seen-items filter** (don't re-show recent items), a **per-attribute cap** (max N from one creator/category), and an **MMR or DPP diversity term** in re-ranking. If retrieval itself is narrow, diversify the *sources*.
4. **Watch the trade-off.** Diversity often dips short-term CTR while improving retention and session length, so measure both and use a longer holdback.

The signal: you don't argue about feelings — you **operationalize** the complaint into diversity/freshness/repeat-exposure metrics, find the responsible stage, and fix it there.

#### Q85. [Coding] Implement a "seen items" filter with time-decay so recently-shown items are suppressed but old ones can return.

Hard-filtering everything a user ever saw shrinks the catalog and starves recommendations; a **decayed suppression** is better — recently-shown items are strongly suppressed, old ones gradually become eligible again.

```python
import math, time

def apply_seen_penalty(scores, seen_log, half_life_hours=48, now=None):
    """
    scores:   dict item_id -> relevance score
    seen_log: dict item_id -> last_shown_unix_ts
    Multiplies score by a factor in (0,1]: ~0 if just shown, →1 as it ages out.
    """
    now = now or time.time()
    out = {}
    for item, s in scores.items():
        ts = seen_log.get(item)
        if ts is None:
            out[item] = s                                  # never shown: full score
        else:
            age_h = (now - ts) / 3600.0
            decay = 1 - math.exp(-math.log(2) * age_h / half_life_hours)
            out[item] = s * decay                          # 0 when just shown, →s when old
    return out
```

This avoids two failure modes: never re-showing anything (catalog collapse, and you can never recover from a bad recommendation) versus showing the same thing every session (the "repetitive" complaint). The half-life is the knob — short for fast-moving feeds, long for catalogs where re-exposure is annoying.

#### Q86. [Practical] You're asked to add a brand-new product category with zero interaction history to an existing warm recommender. How do you avoid it being invisible?

A warm CF model will ignore the new category entirely because it has no interactions — the **new-item cold start at category scale**. Concrete steps:

1. **Embed from content, not behavior.** Generate item embeddings for the new category from metadata/text/image features using the *same* item-tower or content encoder, so the items land in the existing embedding space and are reachable by ANN from day one.
2. **Guarantee exposure with an exploration slice.** Reserve a small fraction of impressions (or a dedicated shelf) for new-category items so they accumulate the interactions they need to graduate to warm CF. Without forced exposure, the feedback loop keeps them invisible forever.
3. **Bridge with rules and co-occurrence.** Editorial placement and "people who liked X often like this new thing" co-visitation (once any data trickles in) accelerate the bootstrap.
4. **Monitor graduation.** Track the new category's impression share and interaction rate over time; the goal is for it to earn its place organically and for the exploration crutch to be removable.

The framing: **content embeddings make it retrievable, forced exploration makes it discoverable, and monitoring tells you when it's warm enough to stand on its own.**

#### Q87. [Coding] Write a temporal train/test split that prevents future leakage and reports per-user holdout sizes.

The single most common recommender bug is a random split. Here's a correct global-temporal split with a sanity report.

```python
import pandas as pd

def temporal_split(events: pd.DataFrame, cutoff, min_train=1):
    """
    events: columns [user_id, item_id, timestamp]; cutoff: a pd.Timestamp.
    Train = interactions strictly before cutoff; Test = at/after cutoff.
    Drops test rows for users with no training history (can't personalize them).
    """
    train = events[events["timestamp"] < cutoff]
    test  = events[events["timestamp"] >= cutoff]

    trained_users = set(train.groupby("user_id").size()
                             .loc[lambda s: s >= min_train].index)
    test = test[test["user_id"].isin(trained_users)]

    # leakage assertion: no test interaction may predate the cutoff
    assert (test["timestamp"] >= cutoff).all(), "future leakage!"
    report = {
        "train_rows": len(train), "test_rows": len(test),
        "test_users": test["user_id"].nunique(),
        "median_holdout_per_user": int(test.groupby("user_id").size().median()),
    }
    return train, test, report
```

Note the `min_train` guard: evaluating users who have *no* training history measures cold-start fallback, not the personalized model — so you either drop them or report them as a **separate slice**. Mixing them in muddies the headline metric.

#### Q88. [Practical] Latency on the recommendations endpoint spiked from 40ms to 300ms p99 after a deploy. How do you triage?

Treat it as a standard latency regression but with recommender-specific suspects. Triage in layers:

1. **Localize the stage.** Add/inspect per-stage timing (retrieval, feature fetch, ranking, re-ranking). A 7× jump is usually concentrated in one stage, not spread evenly.
2. **Retrieval suspects:** an ANN parameter change (`efSearch`/`nprobe` raised for recall), an index that got larger or wasn't built with the right index type, or a fallback to **exact** search because the index failed to load.
3. **Feature-fetch suspects:** the online feature store (Redis/DynamoDB) — more feature lookups per request, a cache-hit-rate drop, a hot key, or N+1 lookups instead of a batched multi-get. Feature fetch is the most common hidden cost.
4. **Ranking suspects:** a bigger model, more candidates flowing into ranking (retrieval returning 2000 instead of 500), or batch-size/threading changes.
5. **Confirm with a diff.** What actually changed in the deploy? Correlate the p99 step-change timestamp with the rollout, and roll back to confirm causation if needed.

The principle: **instrument per-stage latency first** so you're debugging the responsible component, not the whole pipeline. The two usual culprits are "retrieval got more thorough" and "we're now fetching more features per request."

#### Q89. [Practical] Half your users see great recommendations and half see junk. The model and code are identical for everyone. What's likely going on?

Identical model + bimodal quality strongly implies a **data or feature-availability split**, not a model problem:

- **Feature coverage gap.** One cohort is missing key features at serving time — a new app version logs an event the model depends on, or a feature pipeline only populates the online store for some users (e.g., logged-in vs logged-out, app vs web, new vs old client). Missing features silently default to zeros/means and degrade those users.
- **Cold vs warm split.** The "junk" half may be genuinely new/low-history users hitting the cold-start fallback, and the "great" half are warm users — the model is fine, your *coverage* of personalization isn't.
- **Train/serve skew for a subpopulation.** A feature computed differently offline vs online affects only the users whose data triggers the discrepancy.
- **An A/B or rollout boundary.** Half the users are in a different bucket (a flag, a region, a model version) than you think.

Diagnosis: **slice every metric by cohort** (client version, login state, history length, region) and look for the feature whose *availability* correlates with the quality split. The lesson: "identical model" doesn't mean "identical inputs" — quality bimodality is almost always an input/coverage problem.

#### Q106. [Coding] Write a guard that detects and handles missing/unknown item or user IDs at serving time instead of crashing or silently returning garbage.

Unknown IDs (a brand-new item not yet in the embedding table, a logged-out user) are a constant serving reality. Crashing is bad; silently embedding a zero vector is worse because it degrades quietly. Handle it explicitly.

```python
import numpy as np

class EmbeddingLookup:
    def __init__(self, emb_table, fallback="content"):
        self.emb = emb_table                 # dict id -> vector (np.ndarray)
        self.dim = len(next(iter(emb_table.values())))
        self.fallback = fallback

    def get(self, item_id, content_vec=None):
        v = self.emb.get(item_id)
        if v is not None:
            return v, "known"
        # Unknown item: prefer a content-derived embedding if we have features
        if self.fallback == "content" and content_vec is not None:
            return content_vec, "content_fallback"
        # Last resort: average/popularity embedding, flagged so we can monitor
        return self._mean_embedding(), "mean_fallback"

    def _mean_embedding(self):
        return np.mean(np.stack(list(self.emb.values())), axis=0)

lk = EmbeddingLookup({1: np.ones(4), 2: np.zeros(4)})
print(lk.get(99, content_vec=np.full(4, 0.5)))   # content_fallback
print(lk.get(99))                                 # mean_fallback (flagged)
```

The two non-negotiables: **never crash on an unknown ID**, and **never let a fallback be invisible** — return a status so you can monitor the fallback rate. A spiking `mean_fallback` rate is itself an alert (the item-embedding pipeline is lagging behind the catalog), which is exactly the kind of silent degradation that a zero-vector default would hide.

### 🟡 — extended

#### Q90. [Practical] Your A/B test shows CTR up 5% but 7-day retention down 2%. The PM wants to ship. What do you do?

This is the canonical **short-term engagement vs long-term value** conflict, and it's a judgment-and-evidence question, not a yes/no.

1. **Trust the longer-horizon, higher-stakes metric more.** CTR is a *proxy*; retention is much closer to true value. A pattern of "CTR up, retention down" is the signature of clickbait/novelty-chasing that wins the click and loses the user — exactly the trap multi-objective ranking exists to prevent. Default to *not* shipping as-is.
2. **Check significance and confounds.** Is the retention drop statistically significant and stable, or noise? Are there interference effects (shared inventory, novelty effects that decay)? Run longer / larger before concluding.
3. **Diagnose the mechanism.** Look at diversity, repeat-exposure, and content-quality slices. Usually CTR rose because the model over-exploited a strong engagement signal and collapsed diversity, which bores users into churning.
4. **Propose a fix, not just a veto.** Add a diversity term, down-weight the engagement-bait signal, or add a satisfaction head to ranking — then re-test. The goal is to *keep* the CTR gain while *restoring* retention.

The signal you want to send: you **optimize for long-term user value**, you treat CTR as a means not an end, and you bring a constructive path forward rather than just blocking.

#### Q91. [Coding] Implement bootstrap confidence intervals for a ranking metric so you can tell real movement from noise.

Point estimates of NDCG lie about significance. Bootstrapping over users gives a CI without distributional assumptions.

```python
import numpy as np

def bootstrap_ci(per_user_metric, n_boot=2000, alpha=0.05, seed=0):
    """
    per_user_metric: 1D array of a metric (e.g., NDCG@k) computed per user.
    Returns (mean, lo, hi) for a (1-alpha) percentile bootstrap CI.
    """
    rng = np.random.default_rng(seed)
    arr = np.asarray(per_user_metric)
    n = len(arr)
    means = np.empty(n_boot)
    for b in range(n_boot):
        sample = arr[rng.integers(0, n, size=n)]   # resample users w/ replacement
        means[b] = sample.mean()
    lo, hi = np.percentile(means, [100*alpha/2, 100*(1-alpha/2)])
    return arr.mean(), lo, hi

ndcg_per_user = np.clip(np.random.default_rng(1).normal(0.32, 0.15, 5000), 0, 1)
print(bootstrap_ci(ndcg_per_user))   # (mean, lo, hi)
```

To compare two models, bootstrap the **paired per-user difference** (`ndcg_A − ndcg_B` for the same user) and check whether the CI excludes zero — pairing removes user-level variance and is far more sensitive than comparing two independent CIs. Reporting a metric without a CI is how teams ship noise as "improvement."

#### Q92. [Practical] After retraining, your model recommends almost nothing but extremely popular items. What likely went wrong and how do you fix it?

A sudden collapse to head items points at the **negative-sampling / popularity-correction machinery**, the usual suspects:

1. **Lost or broken logQ correction.** In two-tower in-batch training, dropping the `log Q(i)` sampling-bias correction makes popular items appear as negatives too often *or* the softmax over-rewards them — either way the model over- or under-shoots on popularity. A regression here is the single most common cause.
2. **Negative sampling went uniform.** If negatives switched from popularity-weighted to uniform, the model only learns "positive ≠ random obscure item" and never learns to *distinguish among* popular items, defaulting to recommending them.
3. **Regularization too low / embeddings collapsed.** Under-regularized factors can collapse toward a dominant popularity direction; or a learning-rate change caused a degenerate solution where the popularity bias term dominates the interaction term.
4. **Lost personalization features.** If the user tower lost its key features (a feature pipeline break), every user embedding looks the same, so everyone gets the global popular list.

Diagnosis order: check **catalog coverage and Gini** (they'll have cratered), then diff the **sampling config and correction terms**, then check **feature coverage in the user tower**. Fix is usually restoring the popularity correction and/or feature inputs. The meta-lesson: "recommends only popular items" is a *symptom*, and the popularity-debiasing pieces (logQ, popularity-weighted negatives, biases) are where you look first.

#### Q93. [Coding] Detect train/serve skew by comparing feature distributions computed in the two pipelines.

Train/serve skew is silent and deadly. A cheap guard is logging a sample of *serving-time* features and comparing their distribution to the *training* features for the same definition.

```python
import numpy as np

def feature_skew_report(train_vals, serve_vals, n_bins=20):
    """
    train_vals, serve_vals: 1D arrays of the SAME feature from the two pipelines.
    Returns PSI plus null-rate delta; flags likely skew.
    """
    lo, hi = np.percentile(np.concatenate([train_vals, serve_vals]), [0.5, 99.5])
    bins = np.linspace(lo, hi, n_bins + 1)
    e = np.histogram(np.clip(train_vals, lo, hi), bins=bins)[0] / len(train_vals) + 1e-6
    a = np.histogram(np.clip(serve_vals, lo, hi), bins=bins)[0] / len(serve_vals) + 1e-6
    psi = float(np.sum((a - e) * np.log(a / e)))
    null_delta = abs(np.isnan(serve_vals).mean() - np.isnan(train_vals).mean())
    return {"psi": psi, "null_rate_delta": null_delta,
            "likely_skew": psi > 0.25 or null_delta > 0.05}

rng = np.random.default_rng(0)
train = rng.normal(0, 1, 10000)
serve = rng.normal(0.3, 1, 10000)              # shifted: a skew
print(feature_skew_report(train, serve))
```

The two most common skew sources this catches: a feature that's **computed differently** (different aggregation window or normalization offline vs online) and a feature with a **different null rate** (e.g., a join that succeeds in batch but times out online, so serving sees more nulls). Both quietly degrade the model with no error in the logs — which is exactly why a feature store with a single definition exists.

#### Q94. [Practical] How would you debug a complaint that "recommendations stopped updating" for some users even though they keep interacting?

"Stuck" recommendations despite fresh activity points at a **staleness in the serving path**, not the model itself:

1. **Cached recommendations not invalidated.** If you precompute and cache top-N per user in a KV store, the cache TTL may be too long, or the cache-refresh job is failing for a shard, so those users get a frozen list regardless of new activity.
2. **User embedding not recomputed.** For batch-computed user embeddings, a user's vector only updates on the next batch run — if that job is failing or lagging, their personalization freezes. Session-aware systems should recompute at request time; check whether the real-time path silently fell back to the cached embedding.
3. **Feature pipeline lag.** The "recent items" feature feeding the model may be backed by a streaming pipeline that's lagging or stuck, so the model sees stale inputs even though new events are being written elsewhere.
4. **Already-seen filter starvation.** If the seen-items filter is too aggressive and the candidate pool is small, a heavy user may have exhausted eligible items, so the list barely changes.

Diagnosis: pick an affected user, trace their request end-to-end — are new events in the online feature store? Is the user embedding fresh? Is the cache being hit? The lesson: **freshness lives in the serving and feature pipelines**, so "not updating" is usually a stale-cache or lagging-pipeline bug, not a modeling one.

#### Q95. [Coding] Implement a per-attribute cap (e.g., max items per creator) during re-ranking without re-sorting everything.

Slates that show six items from one creator feel spammy. A streaming cap enforces diversity-by-constraint while preserving relevance order.

```python
from collections import defaultdict

def cap_per_attribute(ranked_items, attr_of, max_per_attr=2, k=10):
    """
    ranked_items: list of item_ids already sorted by relevance (best first).
    attr_of:      function item_id -> attribute (e.g., creator_id, category).
    Greedily take items in order, skipping any whose attribute is already at cap.
    """
    counts = defaultdict(int)
    out, overflow = [], []
    for item in ranked_items:
        a = attr_of(item)
        if counts[a] < max_per_attr:
            out.append(item); counts[a] += 1
            if len(out) == k:
                return out
        else:
            overflow.append(item)              # keep as backfill if we run short
    # backfill from overflow if capping left us short of k
    for item in overflow:
        if len(out) == k: break
        out.append(item)
    return out

items = [101, 102, 103, 104, 105, 106]
creator = {101:"A",102:"A",103:"A",104:"B",105:"C",106:"A"}
print(cap_per_attribute(items, creator.get, max_per_attr=2, k=4))  # [101,102,104,105]
```

Two design choices interviewers probe: keeping an **overflow backfill** so you don't return fewer than `k` items when the catalog is thin, and capping *after* ranking (cheap, order-preserving) rather than baking diversity into the score (more principled but heavier — that's MMR/DPP territory). For most production slates, a simple per-attribute cap plus a light MMR pass is the pragmatic combination.

#### Q96. [Practical] A small number of "power users" with thousands of interactions dominate your training data. Why is that a problem and how do you handle it?

Power-user dominance skews the model toward a tiny, atypical subpopulation:

- **The problem.** If 1% of users generate 50% of events, gradient updates are dominated by their behavior, so the model optimizes for power-user taste and *underfits the median user* — the people you most need to serve well. Item co-occurrence statistics also get distorted (whatever power users binge looks universally popular).
- **Handling it:**
  - **Cap interactions per user** (subsample each user's events to a maximum) so no single user contributes unbounded gradient mass.
  - **Down-weight by activity** in the loss (`weight ∝ 1/sqrt(user_event_count)`) so high-activity users don't drown out everyone else.
  - **Stratified evaluation.** Always report metrics sliced by activity level — a model can look great on the heavy-tail aggregate while failing newcomers and casual users.
  - **Per-user normalization** of confidence in implicit-ALS so a user with 5000 plays doesn't have 1000× the loss weight of a casual user.

The framing: **aggregate metrics hide subpopulation failures**, and unbounded per-user contribution lets a few atypical users define the model. Cap, weight, and slice.

#### Q97. [Coding] Compute catalog coverage and Gini over recommendation logs to detect popularity collapse.

When a model quietly collapses onto head items, accuracy metrics may stay flat while the *distribution* of recommended items concentrates. Track it directly.

```python
import numpy as np
from collections import Counter

def coverage_and_gini(all_rec_lists, n_items):
    """
    all_rec_lists: iterable of lists of recommended item_ids (one per impression).
    Returns catalog coverage and the Gini coefficient of recommendation frequency.
    """
    counts = Counter(i for lst in all_rec_lists for i in lst)
    coverage = len(counts) / n_items                       # fraction ever shown

    freqs = np.array(sorted(counts.values()))
    n = len(freqs)
    cum = np.cumsum(freqs)
    # Gini: 0 = perfectly even exposure, →1 = a few items get everything
    gini = (2 * np.sum((np.arange(1, n + 1)) * freqs) / (n * cum[-1])) - (n + 1) / n
    return {"coverage": coverage, "gini": float(gini)}

logs = [[1, 2, 3], [1, 2, 4], [1, 2, 5], [1, 2, 6]]        # 1,2 dominate
print(coverage_and_gini(logs, n_items=100))
```

Read it as a pair: **low coverage + high Gini = popularity collapse** (a few items soak up all exposure, the long tail is invisible). Tracking these alongside NDCG is what catches a feedback loop *before* it has run for months — accuracy can look fine while the catalog quietly shrinks to a handful of winners.

#### Q107. [Practical] Your team wants to add "real-time" personalization to a batch system that precomputes nightly. What's the minimal, lowest-risk increment?

You don't rewrite the batch system; you **layer a thin real-time signal on top** of the stable precomputed base, getting most of the value for a fraction of the risk:

1. **Keep batch as the base.** The nightly precomputed top-N (or batch user embedding) captures stable long-term taste and stays the cheap, reliable backbone.
2. **Add a session-aware re-rank.** At request time, fetch the user's **last few actions** from the online store and re-rank the precomputed candidate pool by boosting items similar to the just-touched items (content/embedding similarity). This needs only a fast similarity lookup, not a new model.
3. **Or recompute just the user embedding live.** If you have a two-tower user tower, recompute the *user* vector at request time from recent actions and re-query the *existing, batch-built* item index — the expensive item index stays batch, only the cheap query side goes real-time.
4. **Guard with a fallback.** If the real-time path is slow or the session store misses, fall back to the batch list — never let the new path become a hard dependency that can take down recommendations.

```text
batch (nightly):  stable candidate pool / item index   ← unchanged, reliable
real-time (req):  recent actions → boost / re-query     ← thin, cheap layer
fallback:         batch list if real-time path fails
```

The framing: **"precompute what's stable, compute live what's fresh."** The minimal increment reuses the batch index and only makes the *query side* real-time, which de-risks the migration and ships value without a platform rewrite — the phased approach a senior engineer prefers over a big-bang.

#### Q108. [Coding] Implement a popularity-debiased candidate score so retrieval doesn't over-favor head items, with a tunable strength.

A common, cheap intervention is to divide (or down-weight) scores by a power of item popularity, sliding between raw relevance and a popularity-neutral ranking.

```python
import numpy as np

def debias_scores(item_scores, item_pop, gamma=0.5, eps=1e-9):
    """
    item_scores: dict item_id -> raw relevance score (assumed >= 0).
    item_pop:    dict item_id -> popularity (e.g., interaction count).
    gamma:       0 = no debiasing; 1 = full inverse-popularity; in between is typical.
    """
    out = {}
    for item, s in item_scores.items():
        pop = item_pop.get(item, 0) + eps
        out[item] = s / (pop ** gamma)        # shrink popular items' scores
    # renormalize to keep scores comparable across requests
    m = max(out.values()) or 1.0
    return {i: v / m for i, v in out.items()}

scores = {1: 0.9, 2: 0.85, 3: 0.6}
pop    = {1: 100000, 2: 50, 3: 30}            # item 1 is a head item
print(debias_scores(scores, pop, gamma=0.3))  # item 1's edge shrinks
```

Tuning `gamma` is the whole game: too low and the feed collapses onto head items (feedback loop); too high and you surface obscure-but-irrelevant items, hurting precision. Unlike a hard diversity cap, this is a **smooth, score-level** correction you can A/B at several strengths — pair it with coverage/Gini monitoring so you can see the accuracy-vs-exposure trade-off as you turn the knob.

### 🟠 — extended

#### Q98. [Practical] Design an end-to-end debugging playbook for "online CTR is flat but offline NDCG improved a lot" on a shipped model.

This offline-up/online-flat divergence is the most important failure to reason about cleanly, because it's usually *not* a bug — it's the offline–online gap doing exactly what it does.

1. **Suspect exposure/selection bias first.** Offline NDCG is computed on items the *old* model logged. A new model that recommends genuinely different items gets no credit offline for items that were never shown, *and* its real online behavior is on out-of-distribution items the offline metric never tested. Offline improvement on the logged distribution simply may not transfer.
2. **Check the candidate/eval mismatch.** If offline ranks against sampled negatives but online retrieves from the full catalog, the offline win may be an artifact of an easier task (sampled-metric bias).
3. **Verify the model is actually live and consistent.** Confirm the served model == evaluated model, the **embedding versions match** across towers and index, and features at serving == features in training (no skew). A version/skew mismatch makes the online model effectively a different, worse model.
4. **Look at where the win was.** Offline gains concentrated in the tail or on hard negatives may not move CTR if the *visible top slots* (where users actually look) didn't change much. Slice offline NDCG by position.
5. **Run the right online test.** Use **interleaving** for a sensitive, low-traffic read; if interleaving also shows no lift, the offline win is illusory or non-visible. If interleaving shows a lift but A/B CTR is flat, suspect novelty/diversity effects washing out at the aggregate.

The senior conclusion: offline metrics are a **filter, not a verdict**, and "offline up, online flat" is the *expected* outcome of exposure bias more often than it's a defect — the job is to distinguish "real but non-visible," "illusory from sampling/leakage," and "broken serving."

#### Q99. [Practical] You must retire a heavily-used retrieval source (say a legacy co-visitation index) without hurting metrics. How do you do it safely?

Removing a candidate source is risky because each source covers blind spots of the others; a naive removal silently drops recall for some queries.

1. **Measure its marginal contribution.** Quantify what the source *uniquely* provides: the fraction of final-shown (or clicked) items that came *only* from that source and weren't retrievable by any other. If its unique contribution is near zero, removal is safe; if it's material, you must replace that coverage first.
2. **Slice the contribution.** A legacy source often matters disproportionately for a subpopulation (cold users, long-tail items, a region). Aggregate marginal value can look small while a cohort depends on it — check slices before concluding.
3. **Backfill the gap.** If it carries unique recall, strengthen another source (tune the two-tower, add a co-purchase source) to cover the same queries *before* removing it.
4. **Ramp down behind an A/B / holdback.** Disable it for a fraction of traffic, watch retrieval recall, downstream NDCG, *and* diversity/coverage (it may have been a stealth diversity source). Roll forward only if guardrails hold.
5. **Keep a kill-switch and logs** so you can restore it instantly if a delayed metric (retention, cold-start success) regresses.

The framing: **a retrieval source's value is its *marginal, unique* coverage, not its raw share**, and you de-risk removal with slice analysis, backfill, and a metrics-gated ramp-down — never a big-bang delete.

#### Q100. [Coding] Implement an interleaving evaluation (team-draft interleaving) to compare two rankers sensitively.

Interleaving compares two rankers within a single user's list, controlling for user/context variance, so it detects differences with far less traffic than A/B.

```python
import numpy as np

def team_draft_interleave(ranking_a, ranking_b, rng):
    """
    Build one blended list by alternately letting A and B 'draft' their top
    unused item. Returns (interleaved_list, team) where team[item]='A'/'B'.
    """
    a = [x for x in ranking_a]; b = [x for x in ranking_b]
    interleaved, team = [], {}
    ai = bi = 0
    while ai < len(a) or bi < len(b):
        a_first = rng.random() < 0.5             # randomize who picks each round
        for who, lst in ((("A", a), ("B", b)) if a_first else (("B", b), ("A", a))):
            # advance that team's pointer to its next unused item
            ptr = ai if who == "A" else bi
            while ptr < len(lst) and lst[ptr] in team:
                ptr += 1
            if ptr < len(lst):
                item = lst[ptr]; team[item] = who; interleaved.append(item)
            if who == "A": ai = ptr + 1
            else:          bi = ptr + 1
    return interleaved, team

def credit(clicks, team):
    a = sum(team[i] == "A" for i in clicks)
    b = sum(team[i] == "B" for i in clicks)
    return a, b          # whichever team's items got more clicks "wins" this session

rng = np.random.default_rng(0)
inter, team = team_draft_interleave([1,2,3,4], [3,2,5,6], rng)
print(inter, "->", credit([3, 2], team))   # which ranker's items got the clicks
```

Aggregate the per-session A-vs-B credit across many sessions; a consistent lean is a sensitive, low-variance signal of which ranker users prefer. Interleaving's power is the **within-session paired comparison** — it removes the between-user variance that forces A/B tests to run long, which is why it's the standard pre-A/B screen.

#### Q101. [Practical] Your ranking model's predicted probabilities are used downstream for bidding/pacing, but they're miscalibrated after a change. How do you diagnose and fix calibration?

When scores feed a downstream system (ad bidding, budget pacing, expected-value thresholds), the *absolute* probability matters, not just the ranking — so calibration becomes a first-class concern.

1. **Diagnose with a reliability diagram.** Bucket predictions by predicted probability and compare each bucket's mean prediction to its empirical positive rate. Systematic over/under-prediction (the curve bows off the diagonal) is miscalibration. Quantify with **Expected Calibration Error (ECE)**.
2. **Find the cause.** Common triggers: a change in **negative sampling rate** (sampling negatives at rate `r` inflates predicted positive probability and needs a logit correction `logit − log(r)`), class-imbalance shifts, a new loss, or distribution shift between training and serving traffic.
3. **Fix.** Apply a **post-hoc recalibration** layer — **Platt scaling** (fit a logistic on a held-out set) or **isotonic regression** (non-parametric, monotonic) — mapping raw scores to calibrated probabilities. For sampling-induced bias, apply the closed-form **prior-correction** to the logit instead of/along with recalibration.

```python
import numpy as np
def expected_calibration_error(probs, labels, n_bins=10):
    bins = np.linspace(0, 1, n_bins + 1)
    ece = 0.0
    for lo, hi in zip(bins[:-1], bins[1:]):
        m = (probs >= lo) & (probs < hi)
        if m.any():
            ece += m.mean() * abs(probs[m].mean() - labels[m].mean())
    return ece
```

The key insight: **ranking quality (NDCG/AUC) and calibration are different properties** — a model can rank perfectly while being badly miscalibrated. The moment scores leave the recommender and drive a downstream numeric decision, you must monitor and correct calibration, not just ordering.

#### Q102. [Practical] How do you architect a recommender so an experiment that changes the ranking model doesn't require re-running retrieval, and why does that matter?

You want **stage isolation** so each stage can be experimented on independently, which both speeds iteration and keeps experiments clean.

- **Decouple via a stable candidate contract.** Retrieval emits a candidate set (ids + retrieval features) through a fixed interface; ranking consumes it. A ranking experiment then varies only the ranking model over the *same* candidate set, so any metric change is attributable to ranking, not confounded by different candidates.
- **Why it matters for validity.** If a ranking experiment also changed retrieval, you couldn't tell which caused the metric move — and SUTVA-style confounding creeps in. Holding retrieval fixed makes the experiment a clean A/B on ranking.
- **Why it matters for cost/latency.** Retrieval (ANN over millions) is the expensive part; ranking scores only a few hundred candidates. Caching or sharing the candidate set across ranking variants avoids paying for retrieval N times and lets you shadow-evaluate several rankers on one retrieval pass.
- **Operational form.** Log the candidate set with each request; replay it offline to score new rankers (counterfactual-ish offline ranking eval on real candidates), and in online experiments split *only* the ranking stage by bucket.

The framing: **clean stage boundaries are an experimentation and attribution feature, not just an engineering nicety** — they make per-stage A/Bs interpretable and cheap, which is why mature recommender platforms invest in a well-defined candidate-set contract between retrieval and ranking.

#### Q109. [Practical] A delayed-conversion product (e.g., a marketplace where purchases happen days after the click) makes your engagement labels arrive late. How does that break training and serving, and how do you handle it?

**Delayed feedback** is a subtle but serious data problem: at the moment you log an impression, you don't yet know its true label, because the conversion may arrive hours or days later.

What it breaks:
- **Label bias if you train too soon.** If you snapshot labels at training time, recent impressions are systematically mislabeled as negatives simply because their conversion hasn't happened *yet* — not because they won't. This biases the model against recent (and fast-moving) items and undercounts positives.
- **Non-stationarity interacts badly.** Trends and fresh items are exactly the ones whose conversions are still pending, so the bias falls hardest where freshness matters most.
- **Attribution windows.** You must define a window (e.g., 7-day) after which an unconverted impression is *truly* negative — too short undercounts conversions, too long delays training and staleness.

How to handle it:
- **Delayed-feedback models.** Explicitly model the conversion *delay distribution* (e.g., the Chapelle delayed-feedback model: jointly model P(convert) and the time-to-convert), so an unconverted-but-recent impression is treated as "censored," not negative.
- **Importance weighting / positive relabeling.** When a delayed conversion finally arrives, relabel and **up-weight** that example, or use streaming pipelines that emit a correction event.
- **Two-stage labeling.** Train on a stable window where labels are mature, and use a separate fast-adapting model (or recency features) for the freshest items where labels aren't settled.

The expert framing: with delayed feedback, **"no conversion yet" ≠ "negative"** — it's *censored* data, and treating censoring as negativity biases the model against exactly the recent, high-velocity items you most want to get right. You either model the delay explicitly or define disciplined attribution windows with correction events.

#### Q110. [Practical] After a major model upgrade, aggregate metrics are flat but the support queue fills with complaints from a specific user segment. How do you reconcile "metrics say fine" with "users say broken"?

This is the **aggregate-hides-the-tail** failure, and the reconciliation is almost always *slicing*:

1. **Trust the complaints enough to investigate.** Aggregate flatness can mask a segment that got much worse offset by a segment that got better. A real regression for a vocal segment is invisible in a global average.
2. **Slice every metric by the complaining segment** — and by plausible axes (locale, device, content type, history length, language). Look for where the new model's metrics *diverge* from the old one's per slice, not just in aggregate.
3. **Check for a representation gap.** Major upgrades often help the data-rich majority while regressing a minority the training data under-represents (a niche locale, a content type with sparse interactions, accessibility-dependent users). The model optimized the average and sacrificed the tail.
4. **Look for a concrete breakage in that slice** — a feature that's null for that segment, a tokenizer/embedding that handles their language worse, a content filter mis-tuned for their catalog.
5. **Decide with weighted judgment.** Even if the segment is small, the *harm* may be large (trust, churn, fairness, PR risk). The fix may be a segment-specific fallback, a fairness constraint, or holding the rollout for that segment until addressed.

The senior point: **a flat aggregate is not evidence of no regression** — it's an average that can hide a severe, concentrated harm. Vocal complaints are a sampling signal pointing you at the slice the aggregate is drowning out; the discipline is to slice before you dismiss, and to weigh harm, not just headcount.

### 🔴 — extended

#### Q103. [Practical] A regulator (or your own policy) requires that you can *explain* why a specific item was recommended to a specific user. How do you build explainability into a deep two-stage recommender?

Deep recommenders are opaque, but you can engineer **traceable, faithful-enough explanations** at each stage rather than post-hoc rationalizations:

1. **Provenance from retrieval.** Log *which source* surfaced the item and *why* — e.g., "retrieved by two-tower as similar to items you watched: X, Y" or "co-purchased with Z you bought." Source-level provenance is the most honest, user-legible explanation and is cheap to log.
2. **Feature attribution in ranking.** Use **SHAP / integrated gradients** on the ranking model for the specific `(user, item)` pair to identify the top contributing features ("recency of category C," "you engaged with similar creators"). Cache the dominant attributions for auditing.
3. **Nearest-neighbor / influence tracing.** For embedding retrieval, surface the user's *own past items* that are nearest to the recommended item in embedding space — a faithful "because you liked these" explanation grounded in the actual geometry that produced the candidate.
4. **Counterfactual checks.** "Would this item still be recommended if we removed signal X?" — useful for audits and for verifying the explanation is causal, not decorative.
5. **Governance plumbing.** Persist per-recommendation explanation records (sources, top features, model version) so you can answer a specific historical "why" — which also requires **embedding/model versioning** so you can reconstruct the exact model state at the time.

The expert framing: explainability in deep recommenders is mostly an **architecture-and-logging** problem — capture provenance at retrieval, attribution at ranking, and version everything — rather than hoping a black-box model is interpretable after the fact. And be honest about *faithfulness*: a plausible explanation that doesn't reflect the real cause is worse than none for a regulator.

#### Q104. [Practical] You discover a feedback loop has been quietly amplifying a demographic bias for months. Walk through containment, correction, and prevention.

This is an incident-response question crossed with fairness — handle it like a production incident with an ethical dimension.

- **Contain (stop the bleeding).**
  - Add immediate **guardrails / re-ranking constraints** that enforce exposure floors or cap the skew on the affected dimension, even at some short-term metric cost. Containment beats elegance during an active harm.
  - If severe, fall back to a less-personalized but fairer source (popularity within balanced segments) for affected surfaces while you fix the root cause.
- **Correct (fix the loop).**
  - **De-bias the training signal** with propensity/exposure weighting so the model learns preference, not the historical exposure that encoded the bias.
  - **Break the closed loop** with randomized exploration on under-exposed segments to collect unbiased data, since the existing logs are contaminated by the very bias you're removing.
  - **Retrain on de-biased data** and validate on **fairness metrics** (exposure parity, equal opportunity on engagement) sliced by the affected group, not just aggregate accuracy.
- **Prevent (don't recur).**
  - Add **fairness/diversity guardrail metrics** to the standing dashboard and to automated launch gates, with alerts on drift in exposure distribution by segment.
  - Institute **long-horizon holdbacks** and periodic feedback-loop audits, because these harms compound slowly and are invisible to next-session CTR.
  - Treat the recommender as a **closed-loop socio-technical system**: document the loop, owners, and the monitoring that would have caught it earlier.

The senior framing: feedback-loop bias is a *systemic* failure, so the response is systemic — contain with constraints, correct by de-biasing and breaking the loop with exploration, and prevent with long-horizon fairness monitoring baked into the launch process. And a blameless post-mortem on *why monitoring missed it for months* is part of the fix.

#### Q105. [Practical] How would you stand up shadow evaluation / online guardrails so a bad model can never reach users at full scale?

The goal is **defense in depth**: many independent gates, each able to stop a bad model, so no single failure ships a regression to everyone.

1. **Offline gate.** A new model must beat the incumbent on a battery of offline metrics *and* not regress guardrails (coverage, Gini, calibration, cold-start slices) before it's even eligible. This is a filter, not a verdict, but it catches gross breakage.
2. **Shadow mode.** Run the new model on **live traffic without affecting users** — score real requests in parallel, log its would-be recommendations, and compare distributions to production (overlap, popularity skew, latency). Catches serving bugs, skew, and latency regressions on real inputs with zero user risk.
3. **Canary / small-percentage rollout.** Expose to 1% with **automated guardrail monitoring** (engagement, error rate, latency p99, diversity) and **automatic rollback** if any guardrail breaches a threshold. Never jump straight to full traffic.
4. **Always-on holdback.** Keep a permanent small slice on the prior model so you can measure live degradation of the *current* model over time, not just at launch — this catches slow drift and delayed harms.
5. **Kill switch + versioning.** One-click revert to the last-good model, with strict **embedding/model version pinning** so a rollback is consistent across towers and index.

```text
offline gate → shadow (no user impact) → 1% canary (auto-rollback)
            → ramped rollout → full + always-on holdback   (+ kill switch)
```

The expert point: **shipping safety is a pipeline, not a launch event.** Each stage assumes the previous one can fail, and the cheap, low-risk stages (offline, shadow) catch most problems before the expensive, user-facing ones — with automated rollback and a permanent holdback ensuring even a model that *passed* every gate can't quietly degrade users without being detected and reverted.

#### Q111. [Practical] You're consolidating three separate recommender models (home feed, "up next," email) that have diverged. How do you reason about unifying them without regressing any surface?

Three models that grew independently usually share 80% of the machinery but each encodes surface-specific objectives and constraints; the risk of consolidation is **regressing the surface-specific value** while chasing platform leverage.

1. **Separate the shareable from the surface-specific.** The retrieval stack, embeddings, feature store, and candidate sources are almost always worth unifying (one source of truth, one place to improve). What's genuinely different is usually the **objective weighting and constraints** per surface — "up next" prizes session continuation, email prizes re-engagement and freshness, the home feed prizes diversity. Unify the *substrate*, keep per-surface *heads/objectives*.
2. **Adopt a shared model with surface as context.** A **multi-task / multi-surface model** with surface as an input feature (and per-surface objective heads, MMoE/PLE-style) lets surfaces share representation while specializing — capturing the leverage without forcing one objective on all.
3. **Migrate one surface at a time behind A/Bs.** Never cut over all three at once. For each surface, A/B the unified model against its incumbent on *that surface's* own metrics, including its specific guardrails (email unsubscribe rate, feed diversity, "up next" continuation). Only graduate a surface when it's neutral-or-better.
4. **Preserve per-surface guardrails as launch gates.** The consolidation must not regress any surface's key metric; encode each as a hard gate so a platform win can't quietly cost a surface its objective.
5. **Quantify the leverage you're buying.** Justify the migration by the *maintenance and improvement velocity* gained (one model to improve, consistent features) against the integration cost and risk — and keep the option to stop if a surface can't be matched.

The senior framing: consolidation is **"share the substrate, specialize the objective."** The leverage is in unifying retrieval, features, and representation; the danger is flattening genuinely different surface objectives into one. You de-risk with a multi-surface model, per-surface A/Bs, and per-surface guardrail gates, migrating incrementally and reversibly rather than forcing a single global model that serves every surface mediocrely.

## ✅ Key Takeaways

- **Two big families:** collaborative filtering (learn from the crowd's interactions) vs content-based (learn from item features). Each fails where the other shines, so production systems are **hybrids**.
- **Matrix factorization is the foundational model-based CF:** low-rank user/item embeddings, dot-product scoring, with SGD-SVD (plus biases) or ALS (closed-form, parallel, great for implicit feedback) as the two training routes.
- **Implicit feedback is the norm and has no true negatives:** treat unobserved as low-confidence negatives, use negative sampling, and never random-split — split by **time**.
- **Cold start = no interactions:** content/features rescue new users and items; popularity and exploration bridge the gap.
- **Scale via a two-stage funnel:** cheap high-recall **candidate generation** (two-tower + ANN over embeddings) then a heavy high-precision **ranking** model, often with a diversity/business **re-ranking** stage.
- **Evaluate with the right metric for the stage:** recall@k for retrieval, NDCG/precision@k/MAP for ranking, AUC for pairwise separation — and never trust offline alone; **online A/B is the arbiter**.
- **Beyond accuracy matters:** diversity, novelty, and serendipity sustain long-term engagement; popularity bias and feedback loops silently degrade the system if unmanaged.
- **Deep recommenders** (wide & deep, DLRM, neural CF, MMoE multi-task, SASRec/BERT4Rec sequence models, and emerging generative/LLM recommenders) add power — but a well-tuned dot-product MF remains a strong, cheap baseline, especially for retrieval.
- **It's a system, not a model:** feature stores for online/offline consistency, batch-vs-real-time serving, drift monitoring, exploration, and de-biasing are what make recommenders work in production.

## ⚠️ Common Pitfalls

- **Random train/test splits** that leak the future and inflate every offline number — use temporal splits.
- **Treating unobserved implicit entries as hard negatives** instead of low-confidence ones; or using no negative sampling at all.
- **Optimizing a single proxy metric (CTR)** into clickbait, ignoring watch-time quality, diversity, retention, and long-term satisfaction.
- **Ignoring popularity bias and feedback loops** — letting the rich get richer until the catalog collapses to a few head items.
- **Trusting offline NDCG to predict online results** — exposure bias and the closed loop make offline an unreliable oracle; it's a filter, not a verdict.
- **Mismatched embedding versions** between the user tower and item tower / ANN index, silently destroying retrieval quality.
- **No diversity/serendipity in the final list** — a relevant but monotonous feed that bores users into churn (filter bubble).
- **Reporting ranking metrics against sampled negatives** and assuming they reflect full-catalog ranking quality.
- **Forgetting cold start** — a model that's great for power users but useless for new users/items, with no popularity/content fallback.
- **Train–serve skew** — computing a feature differently offline and online (the exact problem a feature store exists to prevent).
- **Putting a learned (MLP) similarity in retrieval**, which can't use ANN, instead of a dot product that can.
- **Position/exposure bias** baked into training so the model just imitates the previous ranker's layout instead of learning relevance.

## 📚 Further Reading

- *Recommender Systems Handbook* (Ricci, Rokach, Shapira) — the comprehensive reference text.
- *Matrix Factorization Techniques for Recommender Systems* (Koren, Bell, Volinsky) — the Netflix-Prize MF paper, the canonical starting point.
- *Collaborative Filtering for Implicit Feedback Datasets* (Hu, Koren, Volinsky) — the implicit-ALS confidence/preference formulation.
- *Amazon.com Recommendations: Item-to-Item Collaborative Filtering* (Linden, Smith, York) — the production item-based classic.
- *Deep Neural Networks for YouTube Recommendations* (Covington et al.) — the candidate-generation + ranking funnel in practice.
- *Wide & Deep Learning for Recommender Systems* (Cheng et al.) and *DLRM* (Naumov et al.) — deep ranking architectures.
- *Neural Collaborative Filtering* (He et al.) and *Neural Collaborative Filtering vs. Matrix Factorization Revisited* (Rendle et al.) — read both for the nuanced verdict on neural vs dot-product.
- *Sampling-Bias-Corrected Neural Modeling for Large Corpus Item Recommendations* (Yi et al.) — two-tower retrieval with in-batch-negative correction.
- *Self-Attentive Sequential Recommendation (SASRec)* (Kang & McAuley) and *BERT4Rec* (Sun et al.) — sequence models; *Recommender Systems with Generative Retrieval (TIGER)* (Rajput et al.) — generative retrieval.
- *On Sampled Metrics for Item Recommendation* (Krichene & Rendle) — why sampled ranking metrics mislead.
- *Modeling Task Relationships in Multi-task Learning with MMoE* (Ma et al.) — multi-objective ranking.
- **Faiss**, **ScaNN**, and **HNSW** documentation/papers — ANN at scale; **Feast** and **Tecton** docs — feature stores.
- Chip Huyen, *Designing Machine Learning Systems* and Eugene Yan's writing (eugeneyan.com) — production recommender system design and evaluation discipline.
