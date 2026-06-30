# Deep Learning Fundamentals

[← Back to master index](../README.md)

An interview-grade reference for the deep-learning foundations every engineer is expected to know — the perceptron and MLP, activation functions, forward/backward propagation, loss functions and optimizers, learning-rate schedules, the gradient pathologies (vanishing/exploding), weight initialization, regularization (dropout, batchnorm, layernorm, weight decay), and the major architecture families (CNN, RNN/LSTM/GRU, transformer). Every answer explains the *why* and the engineering trade-offs, with PyTorch/NumPy snippets for the practical and coding questions. Current through 2026.

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

### Q1. [Theory] What is a perceptron, and what are its limitations?

A **perceptron** is the simplest neural unit: it computes a weighted sum of its inputs plus a bias, then applies a step (threshold) activation to produce a binary output.

```text
        x1 ──w1──┐
        x2 ──w2──┤
        x3 ──w3──┤──► Σ (wᵢxᵢ) + b ──► step() ──► ŷ ∈ {0,1}
         1 ──b───┘
```

Formally `ŷ = step(w·x + b)`. It is a **linear classifier**: the decision boundary `w·x + b = 0` is a hyperplane. Training (the perceptron learning rule) only converges if the data is **linearly separable**.

Its famous limitation, highlighted by Minsky & Papert (1969), is that a single perceptron **cannot represent XOR** — a non-linearly-separable function. The fix is to stack perceptrons into layers with non-linear activations, giving a **multi-layer perceptron (MLP)**, which can approximate any continuous function.

### Q2. [Theory] What is a multi-layer perceptron (MLP), and why do we need non-linear activations?

An **MLP** is a feed-forward network of one or more **hidden layers** between input and output. Each layer applies an affine transform `z = Wx + b` followed by a non-linear activation `a = σ(z)`.

The non-linearity is the whole point. If every activation were linear (identity), then stacking layers would collapse:

```text
W₂(W₁x + b₁) + b₂ = (W₂W₁)x + (W₂b₁ + b₂) = W'x + b'
```

— still just a single linear map, no matter how many layers. Non-linear activations let the network bend the input space and compose features hierarchically. The **Universal Approximation Theorem** states that an MLP with a single hidden layer of sufficient width can approximate any continuous function on a compact domain to arbitrary precision; in practice **depth** is far more parameter-efficient than width.

### Q3. [Theory] Explain ReLU, sigmoid, and tanh. When would you use each?

These map a scalar to a non-linear output.

```text
sigmoid(x) = 1/(1+e^-x)        range (0,1)    S-curve
tanh(x)    = (eˣ-e⁻ˣ)/(eˣ+e⁻ˣ) range (-1,1)  zero-centered S-curve
ReLU(x)    = max(0,x)           range [0,∞)   "hinge"
```

- **Sigmoid** squashes to (0,1) — useful for the **output** of binary classification (as a probability) or gates in LSTMs. As a hidden activation it suffers from **vanishing gradients** (derivative ≤ 0.25, saturates) and is not zero-centered.
- **Tanh** is zero-centered (helps optimization vs. sigmoid) but still saturates at the extremes.
- **ReLU** is the default hidden activation: cheap, non-saturating for positive inputs, and it promotes sparse activations. Its drawback is **dead neurons** — if a unit's pre-activation stays negative, its gradient is permanently 0. Variants (Leaky ReLU, ELU, GELU) address this.

Rule of thumb: ReLU (or GELU) for hidden layers; sigmoid for a single probability output; softmax for multi-class output; tanh occasionally inside recurrent gates.

### Q4. [Theory] What is GELU and why do transformers prefer it over ReLU?

**GELU** (Gaussian Error Linear Unit) weights an input by the probability that a standard-normal variable is below it: `GELU(x) = x·Φ(x)`, where Φ is the standard normal CDF. A common approximation is `0.5x(1 + tanh(√(2/π)(x + 0.044715x³)))`.

Unlike ReLU's hard cutoff at 0, GELU is **smooth and differentiable everywhere** and allows a small amount of negative information to pass (gating it probabilistically rather than with a hard gate). Empirically it trains transformers slightly better and avoids ReLU's dead-neuron problem, which is why BERT, GPT, and most modern transformer stacks use GELU (or SwiGLU, a gated variant, in newer LLMs).

### Q5. [Theory] Describe the forward pass through a 2-layer network.

The forward pass computes the prediction by propagating activations layer by layer.

```text
Input x ─► z¹ = W¹x + b¹ ─► a¹ = σ(z¹) ─► z² = W²a¹ + b² ─► ŷ = softmax(z²)
```

For a layer `l`: `z^l = W^l a^{l-1} + b^l` then `a^l = activation(z^l)`, with `a^0 = x`. You cache the intermediate `z` and `a` values because backpropagation needs them to compute gradients. The final layer's activation depends on the task: softmax for multi-class, sigmoid for binary, identity for regression.

### Q6. [Coding] Implement a forward pass for a small MLP in NumPy.

```python
import numpy as np

def relu(z):
    return np.maximum(0, z)

def softmax(z):
    z = z - z.max(axis=1, keepdims=True)   # numerical stability
    e = np.exp(z)
    return e / e.sum(axis=1, keepdims=True)

def mlp_forward(X, params):
    # X: (batch, in_dim)
    z1 = X @ params["W1"] + params["b1"]   # (batch, hidden)
    a1 = relu(z1)
    z2 = a1 @ params["W2"] + params["b2"]  # (batch, classes)
    return softmax(z2)

rng = np.random.default_rng(0)
params = {
    "W1": rng.normal(0, 0.1, (4, 8)), "b1": np.zeros(8),
    "W2": rng.normal(0, 0.1, (8, 3)), "b2": np.zeros(3),
}
X = rng.normal(size=(5, 4))
print(mlp_forward(X, params).round(3))   # each row sums to 1.0
```

Complexity is dominated by the matrix multiplies: `O(batch · in · hidden + batch · hidden · classes)`.

### Q7. [Theory] What is a loss function? Contrast MSE and cross-entropy.

A **loss function** measures how far predictions are from targets; training minimizes its average over the data.

- **Mean Squared Error (MSE)**: `(1/n)Σ(y − ŷ)²` — used for **regression**. It penalizes large errors quadratically and assumes Gaussian noise.
- **Cross-entropy**: `−Σ y_c log(ŷ_c)` — used for **classification**. For binary: `−[y log ŷ + (1−y) log(1−ŷ)]`. It measures the divergence between the true distribution and the predicted probability distribution.

Why not MSE for classification? Paired with sigmoid/softmax, MSE produces a **non-convex** loss surface with flat regions where gradients vanish even when the prediction is confidently wrong. Cross-entropy paired with softmax yields a clean gradient `ŷ − y`, giving strong, well-behaved learning signals. So: regression → MSE; classification → cross-entropy.

### Q8. [Theory] What is gradient descent, and what are batch, mini-batch, and stochastic variants?

**Gradient descent** updates parameters in the direction of steepest descent of the loss: `θ ← θ − η·∇θ L`, where `η` is the learning rate.

The variants differ in how many examples the gradient is computed over:

```text
Batch GD       : full dataset per step   → accurate gradient, slow, lots of memory
Stochastic GD  : 1 example per step       → noisy, fast updates, escapes some minima
Mini-batch GD  : B examples per step      → the practical default (B = 32..512)
```

Mini-batch is the standard: it balances gradient-estimate quality against compute, vectorizes well on GPUs, and the noise from sampling acts as a mild regularizer. "SGD" in modern frameworks almost always means mini-batch SGD.

### Q9. [Theory] What is the learning rate, and what happens if it is too high or too low?

The **learning rate (η)** scales each gradient step.

```text
η too small ──► tiny steps, very slow convergence, may stall in a plateau
η too large ──► overshoots minima, loss oscillates or diverges (NaN)
η just right ─► steady decrease to a good minimum
```

It is the single most important hyperparameter. Practical tactics: start with a learning-rate range test, use a schedule (warmup then decay), and watch the loss curve — divergence or wild oscillation means lower it; painfully slow progress means raise it.

### Q10. [Coding] Implement vanilla mini-batch SGD training in PyTorch.

```python
import torch, torch.nn as nn

model = nn.Sequential(nn.Linear(20, 64), nn.ReLU(), nn.Linear(64, 3))
loss_fn = nn.CrossEntropyLoss()
opt = torch.optim.SGD(model.parameters(), lr=0.1)

X = torch.randn(1000, 20)
y = torch.randint(0, 3, (1000,))

for epoch in range(10):
    perm = torch.randperm(len(X))
    for i in range(0, len(X), 32):                 # mini-batches of 32
        idx = perm[i:i+32]
        xb, yb = X[idx], y[idx]
        opt.zero_grad()                            # clear old grads
        logits = model(xb)                         # forward
        loss = loss_fn(logits, yb)                 # compute loss
        loss.backward()                            # backprop
        opt.step()                                 # update weights
    print(f"epoch {epoch}: loss={loss.item():.3f}")
```

The four-line core — `zero_grad → forward → backward → step` — is the universal training loop; forgetting `zero_grad()` causes gradients to accumulate across batches, a classic bug.

### Q11. [Theory] What is an epoch, a batch, and an iteration?

- **Batch (mini-batch)**: a group of `B` training examples processed together in one forward/backward pass.
- **Iteration (step)**: one parameter update — one batch processed.
- **Epoch**: one full pass over the entire training set.

If you have 10,000 examples and batch size 100, then one epoch = 100 iterations. You typically train for many epochs. The relationship: `iterations_per_epoch = ceil(dataset_size / batch_size)`.

### Q12. [Theory] What is the softmax function and why subtract the max before exponentiating?

**Softmax** turns a vector of logits into a probability distribution:

```text
softmax(z)_i = e^{z_i} / Σ_j e^{z_j}
```

The outputs are positive and sum to 1, so they read as class probabilities. The **max-subtraction trick** — computing `e^{z_i − max(z)}` — is for **numerical stability**: large logits like `e^1000` overflow to `inf`, producing NaNs. Subtracting the max shifts the largest exponent to `e^0 = 1` without changing the result (the shift cancels in numerator and denominator). Production frameworks fold this into a fused `log_softmax`/`cross_entropy` for stability.

### Q13. [Practical] How do you split data into train/validation/test, and why?

You hold out data the model never trains on so you can estimate **generalization**.

```text
┌──────────────── all data ────────────────┐
│  train (70%)   │ validation (15%) │ test (15%) │
└────────────────┴──────────────────┴────────────┘
   fit weights      tune hyperparams    final, untouched report
```

```python
from sklearn.model_selection import train_test_split
X_tmp, X_test, y_tmp, y_test = train_test_split(X, y, test_size=0.15, random_state=42)
X_tr, X_val, y_tr, y_val = train_test_split(X_tmp, y_tmp, test_size=0.1765, random_state=42)
```

The **validation** set guides model selection and early stopping; the **test** set is touched **once** at the very end. Reusing the test set for tuning leaks information and inflates your reported metric. For temporal data, split by time (no shuffling) to avoid look-ahead leakage.

### Q14. [Theory] What is overfitting in a neural network, and how do you detect it?

**Overfitting** is when the network memorizes training noise rather than learning generalizable patterns — low training loss, high validation loss.

```text
loss
 │  ╲train
 │   ╲________
 │   ╱‾‾‾‾ val   ← val loss turns up while train keeps falling = overfitting
 └──────────────► epochs
```

You detect it by watching the **gap** between training and validation curves. Once validation loss starts rising while training loss keeps falling, the model is overfitting. Remedies: more data, data augmentation, dropout, weight decay, early stopping, or a smaller model.

### Q15. [Practical] What is data augmentation and why does it help?

**Data augmentation** synthetically enlarges the training set by applying **label-preserving transformations** to existing examples, exposing the model to more variation and acting as a regularizer.

```python
import torchvision.transforms as T
augment = T.Compose([
    T.RandomResizedCrop(224, scale=(0.8, 1.0)),
    T.RandomHorizontalFlip(),
    T.ColorJitter(0.2, 0.2, 0.2),
    T.ToTensor(),
])
```

For **images**: flips, crops, rotations, color jitter, Cutout, MixUp/CutMix. For **text**: synonym replacement, back-translation, token masking. For **audio**: time/frequency masking (SpecAugment), pitch shift. The transform must not change the label (don't horizontally flip a "6" into a "9" digit). Augmentation reduces overfitting and is one of the cheapest ways to improve generalization.

### Q16. [Theory] What is early stopping?

**Early stopping** halts training when validation performance stops improving, preventing the model from overfitting in late epochs. You monitor a validation metric, keep the **best** checkpoint, and stop after `patience` epochs without improvement.

```text
track best val_loss; if no improvement for `patience` epochs → stop, restore best weights
```

It is effectively a free, implicit regularizer — it limits how far parameters drift from their initialization. Always restore the best weights rather than using the final (already-overfitting) ones.

### Q17. [Theory] What is the role of the bias term?

The **bias** lets a neuron shift its activation independently of its inputs. Without it, every layer's affine map `Wx` is forced through the origin — the decision boundary must pass through 0, severely limiting what the network can represent. The bias `b` translates the function:

```text
no bias:  σ(w·x)        boundary fixed at origin
with bias: σ(w·x + b)   boundary can shift anywhere
```

It is analogous to the intercept in linear regression. Bias terms are usually initialized to 0 (sometimes small positive for ReLU layers to keep units active early).

---

## 🟡 Intermediate (3–7 yrs)

### Q18. [Theory] Explain backpropagation and the chain rule.

**Backpropagation** is the efficient algorithm for computing the gradient of the loss with respect to every parameter, by applying the **chain rule** backward through the computation graph.

For a chain `x → z = Wx+b → a = σ(z) → L`, the gradient w.r.t. `W` is:

```text
∂L/∂W = ∂L/∂a · ∂a/∂z · ∂z/∂W
        └─upstream─┘  └local┘  └local┘
```

The key idea: compute each layer's local derivative, then **multiply by the upstream gradient** flowing back from the layer above, propagating from output to input. This reuses intermediate results (the cached forward activations), making the full gradient cost roughly the same as one forward pass — `O(parameters)` — instead of the exponential cost of naively differentiating. This is reverse-mode automatic differentiation.

### Q19. [Coding] Implement backprop for a single linear+softmax layer.

```python
import numpy as np

def softmax(z):
    z = z - z.max(axis=1, keepdims=True)
    e = np.exp(z); return e / e.sum(axis=1, keepdims=True)

def forward_backward(X, y_onehot, W, b):
    # forward
    logits = X @ W + b
    probs = softmax(logits)
    n = X.shape[0]
    loss = -np.sum(y_onehot * np.log(probs + 1e-12)) / n

    # backward: for softmax+cross-entropy, dL/dlogits = (probs - y)
    dlogits = (probs - y_onehot) / n         # (n, classes)
    dW = X.T @ dlogits                        # (in, classes)
    db = dlogits.sum(axis=0)                  # (classes,)
    return loss, dW, db
```

The elegant result `∂L/∂logits = ŷ − y` is why softmax and cross-entropy are paired — the messy Jacobian of softmax cancels with the cross-entropy derivative, giving a clean, cheap gradient.

### Q20. [Theory] What causes vanishing and exploding gradients?

In a deep network the gradient is a **product** of many layer Jacobians. If each factor is consistently `< 1`, the product shrinks toward 0 (**vanishing**); if consistently `> 1`, it blows up (**exploding**).

```text
∂L/∂θ_early ≈ ∏ (Wᵏ · σ'(zᵏ))   over many layers
              small ⁿ → 0   (vanishing: early layers stop learning)
              large ⁿ → ∞   (exploding: NaN losses, unstable updates)
```

Saturating activations (sigmoid/tanh, derivative ≤ 1) cause vanishing; large weights cause exploding. **Fixes**: ReLU/GELU activations, careful weight init (Xavier/He), residual (skip) connections, normalization layers, and **gradient clipping** for the exploding case.

### Q21. [Theory] Explain Xavier (Glorot) and He initialization. Why does init matter?

Weight init sets the **scale** of initial weights so that activations and gradients neither shrink nor blow up as they propagate through layers. Bad init (all zeros → symmetry, no learning; too large → explosion; too small → vanishing) cripples training.

- **Xavier/Glorot**: `Var(W) = 2/(fan_in + fan_out)` — keeps variance stable for **tanh/sigmoid** (symmetric activations).
- **He (Kaiming)**: `Var(W) = 2/fan_in` — accounts for **ReLU** zeroing half the inputs, so it doubles the variance to compensate.

```python
import torch.nn as nn
layer = nn.Linear(256, 256)
nn.init.kaiming_normal_(layer.weight, nonlinearity="relu")  # He init for ReLU
nn.init.zeros_(layer.bias)
```

Rule: He init for ReLU-family activations, Xavier for tanh/sigmoid. Modern frameworks default to sensible variants automatically.

### Q22. [Theory] Compare SGD, SGD with momentum, Adam, and AdamW.

All update `θ ← θ − η·(something based on the gradient)`.

- **SGD**: `θ ← θ − η·g`. Simple, can generalize best, but slow and sensitive to learning rate.
- **Momentum**: accumulates a velocity `v ← βv + g; θ ← θ − ηv`. Smooths the trajectory, accelerates along consistent directions, dampens oscillation across ravines.
- **Adam**: maintains per-parameter adaptive learning rates from running estimates of the first moment (mean) and second moment (variance) of gradients. Fast, robust to lr choice, the default for transformers.
- **AdamW**: Adam with **decoupled weight decay** — the decay is applied directly to weights rather than folded into the gradient (which Adam's adaptive scaling would otherwise distort). This fixes Adam's broken L2 regularization and is the standard optimizer for training LLMs and transformers today.

```text
SGD       → robust, slow, best generalization with tuning
Momentum  → faster, smoother
Adam      → adaptive, fast convergence, easy to tune
AdamW     → Adam + correct weight decay → modern default
```

### Q23. [Theory] How does Adam work mathematically?

Adam tracks exponential moving averages of the gradient (`m`, first moment) and its square (`v`, second moment), bias-corrects them, then takes a step scaled by `1/√v`.

```text
m_t = β₁·m_{t-1} + (1-β₁)·g_t            # momentum-like mean
v_t = β₂·v_{t-1} + (1-β₂)·g_t²           # uncentered variance
m̂  = m_t/(1-β₁ᵗ),   v̂ = v_t/(1-β₂ᵗ)    # bias correction (early steps)
θ_t = θ_{t-1} − η · m̂ / (√v̂ + ε)
```

Typical defaults: `β₁=0.9, β₂=0.999, ε=1e-8`. The bias correction matters because `m` and `v` start at 0 and are biased toward 0 in early steps. Dividing by `√v̂` gives each parameter its own effective step size — large for rarely-updated params, small for high-variance ones.

### Q24. [Theory] What is weight decay and how does it differ from L2 regularization?

**Weight decay** shrinks weights toward zero each step: `θ ← θ − η(g + λθ)`, equivalently `θ ← (1−ηλ)θ − ηg`. **L2 regularization** adds `½λ‖θ‖²` to the loss, whose gradient is `λθ` — so for **plain SGD the two are identical**.

They **diverge under Adam**: L2 added to the loss gets passed through Adam's adaptive `1/√v` scaling, so parameters with large gradients get less decay — distorting the intended uniform shrinkage. **AdamW** decouples decay from the adaptive update, applying `(1−ηλ)θ` directly. That is why for adaptive optimizers you use weight decay (AdamW), not L2-in-the-loss.

### Q25. [Theory] Explain learning-rate schedules: warmup, step decay, cosine.

A **schedule** varies `η` over training instead of holding it fixed.

```text
warmup:       ramp 0 → η_max over first k steps  (stabilizes early Adam/transformers)
step decay:   η ← η·γ every N epochs              (drop at milestones)
cosine:       η_t = η_min + ½(η_max-η_min)(1+cos(πt/T))   (smooth decay to ~0)
exponential:  η_t = η₀·e^{-kt}
```

```text
η │  warmup      cosine decay
  │   ╱‾‾‾‾╲___
  │  ╱         ‾‾‾‾‾‾─____
  └──────────────────────► steps
```

**Warmup** prevents early instability when Adam's variance estimates are noisy (critical for transformers). **Cosine decay** (often warmup + cosine) is the modern default — it spends most of the budget at a high-ish lr then anneals smoothly, improving final accuracy.

### Q26. [Practical] What is gradient clipping and when do you use it?

**Gradient clipping** caps the magnitude of gradients before the optimizer step to prevent explosions. The common form is **clip-by-norm**: if `‖g‖ > τ`, rescale `g ← g·τ/‖g‖`.

```python
loss.backward()
torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
opt.step()
```

It is essential for **RNNs/LSTMs** (long sequences amplify gradients) and common when training large transformers, where a single bad batch can produce a huge gradient spike that destabilizes training. A typical `max_norm` is 1.0. Clipping by value (clamping each component) is an alternative but clip-by-norm preserves direction.

### Q27. [Theory] Explain dropout. Why scale activations at inference?

**Dropout** randomly zeroes a fraction `p` of activations during training, forcing the network not to rely on any single unit — a form of regularization that approximates training an ensemble of subnetworks.

```text
train:  a ← a · mask / (1-p)     # "inverted dropout": mask ~ Bernoulli(1-p), then scale up
infer:  a ← a                    # no dropout, no scaling needed
```

The **scaling** keeps the expected activation magnitude constant. With inverted dropout, training divides surviving activations by `(1−p)`, so inference simply uses all units with no change — clean and fast. Typical `p`: 0.1–0.5 for fully-connected layers; transformers use ~0.1. Dropout is disabled at eval time (`model.eval()`).

### Q28. [Theory] Explain batch normalization. What problem does it solve?

**Batch normalization** normalizes each layer's pre-activations across the **batch** to zero mean and unit variance, then applies a learnable scale `γ` and shift `β`.

```text
μ_B, σ²_B over the batch  →  x̂ = (x − μ_B)/√(σ²_B + ε)  →  y = γ·x̂ + β
```

It smooths the loss landscape, allows higher learning rates, reduces sensitivity to initialization, and adds slight regularization (batch noise). At inference it uses **running** mean/variance accumulated during training (not the current batch). Caveats: behaves poorly with very small batches, and couples examples in a batch — which is why sequence models prefer layer norm.

### Q29. [Theory] Contrast batch norm and layer norm. Why do transformers use layer norm?

Both normalize, but over **different axes**.

```text
Input shape (batch, features):
BatchNorm: normalize each feature across the batch  (↓ columns)
LayerNorm: normalize each example across its features (→ rows)
```

**Layer norm** computes statistics per-example, so it is independent of batch size and other examples in the batch. Transformers process variable-length sequences with small/variable effective batches per token, where batch statistics are unstable — layer norm sidesteps this entirely. It also works identically in training and inference (no running stats). Modern LLMs often use **RMSNorm**, a cheaper variant that skips mean-centering.

### Q30. [Theory] What is a convolution in a CNN, and what are its key hyperparameters?

A **convolution** slides a small learnable filter (kernel) across the input, computing dot products to produce a feature map. It exploits **local connectivity** and **weight sharing** — the same filter detects a pattern (edge, texture) anywhere in the image, giving translation equivariance and far fewer parameters than a dense layer.

```text
Input          Kernel(3×3)      Output feature map
[ . . . . ]      [w w w]
[ . ▓ . . ]  ⊛   [w w w]   →    each output = Σ(kernel · local patch)
[ . . . . ]      [w w w]
```

Key hyperparameters:
- **Kernel size** (e.g. 3×3): the filter's spatial extent.
- **Stride**: step size of the slide; stride 2 halves spatial resolution.
- **Padding**: zeros added at borders; "same" padding preserves spatial size.
- **Channels (filters)**: number of independent kernels → output depth.

Output size: `(W − K + 2P)/S + 1`.

### Q31. [Theory] What is pooling, and what is a receptive field?

**Pooling** downsamples a feature map to reduce spatial resolution and computation while adding small translation invariance. **Max pooling** takes the max in each window; **average pooling** takes the mean.

```text
2×2 max pool, stride 2:
[1 3 | 2 4]        [5 4]
[5 2 | 1 0]   →    [2 7]
[1 0 | 7 3]
[2 1 | 4 2]
```

The **receptive field** is the region of the *input* that influences a given output neuron. Stacking convolutions and pooling **grows** the receptive field: deeper neurons "see" larger input regions, letting early layers detect edges and deep layers detect whole objects. Dilated convolutions expand the receptive field without extra parameters.

### Q32. [Practical] Build a small CNN for image classification in PyTorch.

```python
import torch.nn as nn

class SmallCNN(nn.Module):
    def __init__(self, num_classes=10):
        super().__init__()
        self.features = nn.Sequential(
            nn.Conv2d(3, 32, 3, padding=1), nn.BatchNorm2d(32), nn.ReLU(),
            nn.MaxPool2d(2),                                  # 32×32 → 16×16
            nn.Conv2d(32, 64, 3, padding=1), nn.BatchNorm2d(64), nn.ReLU(),
            nn.MaxPool2d(2),                                  # 16×16 → 8×8
        )
        self.head = nn.Sequential(
            nn.AdaptiveAvgPool2d(1), nn.Flatten(),            # global avg pool
            nn.Dropout(0.3), nn.Linear(64, num_classes),
        )
    def forward(self, x):
        return self.head(self.features(x))
```

The pattern is canonical: stacked `Conv → Norm → ReLU → Pool` blocks that progressively shrink spatial size while growing channel depth, then a global-average-pool head. Global avg pooling avoids a huge dense layer and makes the model input-size-flexible.

### Q33. [Theory] Explain RNNs and why they struggle with long sequences.

A **recurrent neural network** processes a sequence one step at a time, maintaining a **hidden state** that summarizes the past: `h_t = tanh(W_x x_t + W_h h_{t-1} + b)`.

```text
x₁ → [RNN] → h₁ → [RNN] → h₂ → [RNN] → h₃ → ...
              ↑ same weights reused at every step
```

The problem is **long-range dependencies**. Backpropagation-through-time multiplies the same recurrent Jacobian repeatedly, so gradients either **vanish** (the model forgets distant context) or **explode**. In practice vanilla RNNs struggle beyond ~10–20 steps. LSTMs/GRUs were designed to fix the vanishing case via gating.

### Q34. [Theory] How do LSTMs and GRUs solve the vanishing-gradient problem?

Both add **gates** that control information flow, creating a path along which gradients can travel nearly unchanged.

**LSTM** maintains a **cell state** `C_t` plus three gates:
```text
forget gate f → what to erase from C
input gate  i → what new info to write
output gate o → what to expose as hidden state h
C_t = f·C_{t-1} + i·C̃_t      (additive update = gradient highway)
```

The **additive** cell-state update (vs. the RNN's repeated multiplication) lets gradients flow without vanishing — the forget gate can keep `C` nearly constant over many steps.

**GRU** is a streamlined version with two gates (reset, update) and no separate cell state. It has fewer parameters, trains faster, and often matches LSTM performance. Rule of thumb: try GRU first (cheaper); use LSTM when you need maximum capacity. Both have largely been superseded by transformers for long sequences.

### Q35. [Theory] What are embeddings, and why are they better than one-hot encoding?

An **embedding** maps a discrete token (word, item, category) to a dense, learnable vector in a continuous space. It is implemented as a lookup table `(vocab_size × dim)` trained jointly with the model.

```text
one-hot "cat" = [0,0,1,0,...,0]  (10000-dim, sparse, no notion of similarity)
embedding "cat" = [0.2,-1.1,0.7,...]  (256-dim, dense, similar words ≈ nearby)
```

Advantages: (1) **dimensionality** — 256 dims vs. a 50k one-hot; (2) **semantics** — similar tokens land near each other (cosine similarity is meaningful), enabling generalization; (3) **efficiency** — a lookup instead of multiplying a huge sparse vector. Embeddings are the input layer of virtually every NLP and recommendation model.

### Q36. [Coding] Implement the training loop with validation, early stopping, and best-checkpoint saving.

```python
import copy, torch

def train(model, train_loader, val_loader, opt, loss_fn, epochs=50, patience=5):
    best_val, best_state, wait = float("inf"), None, 0
    for epoch in range(epochs):
        model.train()
        for xb, yb in train_loader:
            opt.zero_grad()
            loss = loss_fn(model(xb), yb)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()

        model.eval()
        val_loss = 0.0
        with torch.no_grad():                       # no grad tracking at eval
            for xb, yb in val_loader:
                val_loss += loss_fn(model(xb), yb).item()
        val_loss /= len(val_loader)

        if val_loss < best_val:
            best_val, best_state, wait = val_loss, copy.deepcopy(model.state_dict()), 0
        else:
            wait += 1
            if wait >= patience:
                break                                # early stop
    model.load_state_dict(best_state)                # restore best
    return model
```

Note the `model.train()`/`model.eval()` toggle (switches dropout and batchnorm modes) and `torch.no_grad()` during validation (saves memory, speeds it up).

---

## 🟠 Advanced (8–12 yrs)

### Q37. [Theory] Give a self-contained recap of the transformer and self-attention.

A **transformer** replaces recurrence with **self-attention**, letting every token directly attend to every other token in parallel.

**Scaled dot-product attention**: project inputs to Queries, Keys, Values; score query-key similarity; softmax to weights; take a weighted sum of values.

```text
Attention(Q,K,V) = softmax( Q·Kᵀ / √d_k ) · V
```

The `√d_k` scaling keeps dot products from growing large and saturating the softmax. **Multi-head attention** runs several attention computations in parallel subspaces, then concatenates — capturing different relation types. A transformer block is:

```text
x → MultiHeadAttn → +x (residual) → LayerNorm → FFN → +x → LayerNorm
```

Because attention is order-agnostic, **positional encodings** (sinusoidal or learned, or RoPE in modern LLMs) inject sequence order. Advantages over RNNs: full parallelism (no sequential dependency), direct long-range connections (constant path length between any two tokens), and excellent scaling — which is why transformers dominate NLP, vision, and multimodal models in 2026.

### Q38. [Theory] Why is the `√d_k` scaling needed in attention?

For random Q, K with components of unit variance, the dot product `q·k` has variance `d_k` — so as the head dimension grows, raw scores grow in magnitude. Large scores push softmax into a **saturated** regime where one weight ≈ 1 and the rest ≈ 0, producing **vanishingly small gradients** through the softmax. Dividing by `√d_k` normalizes the score variance back to ~1, keeping the softmax in a well-conditioned range so gradients flow and attention can be learned smoothly.

### Q39. [Theory] Compare self-attention and convolution as inductive biases.

Both mix information across positions, but with opposite priors.

```text
Convolution:    local, fixed-size receptive field, weight sharing, translation-equivariant
Self-attention: global, content-based (data-dependent) connectivity, permutation-equivariant
```

- **CNNs** bake in a strong **locality** prior — great when nearby pixels/tokens matter most and data is limited; sample-efficient.
- **Self-attention** has a **weaker** inductive bias — it can connect any two positions and *learns* what to attend to, which is more flexible but **data-hungry** (Vision Transformers need large datasets or strong augmentation/pretraining to beat CNNs).

The practical synthesis: hybrid models (convolutional stems + attention), and the observation that with enough data/compute, the flexibility of attention wins. Choose based on data scale and whether locality is the right prior.

### Q40. [Theory] What is transfer learning, and contrast feature extraction vs. fine-tuning.

**Transfer learning** reuses a model pretrained on a large source task as the starting point for a related target task, transferring learned representations instead of training from scratch.

```text
Feature extraction:  freeze pretrained backbone, train only a new head
Fine-tuning:         unfreeze some/all layers, continue training at a low lr
```

- **Feature extraction** is fast, needs little data, low overfitting risk — best when the target task is similar to pretraining or data is scarce.
- **Fine-tuning** adapts the representations themselves — better accuracy when you have moderate data and the domains differ. Use a **low learning rate** (and often discriminative/layer-wise rates: smaller for early layers, which encode general features, larger for later task-specific layers).

Pretraining + fine-tuning is the dominant paradigm: ImageNet backbones for vision, and BERT/GPT-style foundation models for NLP.

### Q41. [Practical] How would you fine-tune a pretrained model efficiently? Mention PEFT/LoRA.

For large models, full fine-tuning is expensive (memory, storage, risk of catastrophic forgetting). **Parameter-Efficient Fine-Tuning (PEFT)** updates only a tiny fraction of parameters.

**LoRA (Low-Rank Adaptation)**: freeze the pretrained weight `W`, and learn a low-rank update `ΔW = B·A` where `A (r×d)` and `B (d×r)` with rank `r ≪ d`.

```text
h = W·x + (B·A)·x        only A,B are trained;  W stays frozen
```

```python
from peft import LoraConfig, get_peft_model
cfg = LoraConfig(r=8, lora_alpha=16, target_modules=["q_proj","v_proj"], lora_dropout=0.05)
model = get_peft_model(base_model, cfg)   # trains <1% of parameters
```

Benefits: trains <1% of parameters, fits a 7B+ model on a single GPU, tiny adapter checkpoints (MBs), and you can swap adapters per task. **QLoRA** combines LoRA with a 4-bit quantized frozen base for even lower memory. This is the standard 2026 approach for adapting LLMs.

### Q42. [Practical] What is mixed-precision training and how does it speed things up?

**Mixed-precision** runs most operations in 16-bit floats (FP16 or BF16) while keeping a 32-bit master copy of weights and certain reductions in FP32. Benefits: ~2× throughput and ~half the memory on modern GPU tensor cores, enabling larger batches/models.

```python
from torch.amp import autocast, GradScaler
scaler = GradScaler()
for xb, yb in loader:
    opt.zero_grad()
    with autocast(device_type="cuda", dtype=torch.bfloat16):
        loss = loss_fn(model(xb), yb)        # ops run in low precision
    scaler.scale(loss).backward()            # loss scaling avoids FP16 underflow
    scaler.step(opt); scaler.update()
```

**FP16** has a narrow range, so small gradients underflow to 0 — fixed by **loss scaling** (multiply loss up before backward, unscale before the step). **BF16** has the same exponent range as FP32 (less precision but no underflow), so it often needs no loss scaling and is preferred on Ampere/Hopper and TPUs. This is standard for training large models in 2026.

### Q43. [Practical] How do you systematically tune hyperparameters?

Tune in roughly this priority order, on a validation set:

```text
1. learning rate (+ schedule)   ← highest impact
2. batch size
3. architecture (depth/width)
4. regularization (dropout, weight decay)
5. optimizer params (betas, warmup)
```

Search strategies:
- **Random search** beats grid search — it covers important dimensions more efficiently when only a few hyperparameters matter.
- **Bayesian optimization / TPE** (Optuna, Hyperband/ASHA) — model-based, prunes bad trials early.
- Use a **learning-rate range test** to bracket lr quickly.

```python
import optuna
def objective(trial):
    lr = trial.suggest_float("lr", 1e-5, 1e-1, log=True)
    wd = trial.suggest_float("wd", 1e-6, 1e-2, log=True)
    return train_and_eval(lr, wd)            # returns val metric
study = optuna.create_study(direction="minimize")
study.optimize(objective, n_trials=50)
```

Tune on a subset/fewer epochs first, fix a seed for comparability, and always report the final number on the untouched test set.

### Q44. [Practical] Your training loss is NaN after a few steps. How do you debug it?

Walk a systematic checklist from most-to-least common cause:

```text
1. Learning rate too high      → lower it (most common); loss exploded
2. No gradient clipping        → add clip_grad_norm_ (esp. RNNs/transformers)
3. Bad input data              → check for NaN/Inf in inputs; normalize features
4. log(0) / divide-by-zero     → use log_softmax/eps; avoid manual log(prob)
5. FP16 overflow               → switch to BF16 or add/adjust loss scaling
6. Bad init / exploding grads  → use He/Xavier; add normalization
7. Unstable custom op          → check exp(), sqrt(), division for large/zero args
```

Practical tactics: set `torch.autograd.set_detect_anomaly(True)` to locate the offending op, print gradient norms per layer, reduce lr to a tiny value to confirm the loss can decrease at all, and overfit a single batch first (a healthy model should drive that loss to ~0).

### Q45. [Theory] What is catastrophic forgetting and how do you mitigate it?

**Catastrophic forgetting** is when a network, trained on a new task, abruptly loses performance on previously learned tasks because gradient updates overwrite the weights that encoded the old knowledge. It is acute in sequential/continual learning and aggressive fine-tuning.

Mitigations:
- **Rehearsal/replay**: mix in samples from old tasks (or generated/representative ones).
- **Regularization**: penalize changes to weights important for old tasks (Elastic Weight Consolidation anchors important params).
- **Parameter isolation**: dedicate or add capacity per task (adapters, LoRA, progressive networks) so the old weights stay frozen.
- **Lower learning rate** and fewer epochs when fine-tuning, to limit drift.

For LLMs, PEFT methods like LoRA inherently reduce forgetting because the frozen base preserves general capabilities.

### Q46. [Behavioral] Tell me about a time you debugged a model that trained fine but performed poorly in production.

Use **STAR**. A strong answer surfaces the gap between offline metrics and real behavior.

- **Situation/Task**: state the model, the offline metric that looked good, and the production symptom (e.g. accuracy dropped on live traffic).
- **Action**: describe systematic root-causing — checking for **train/serve skew** (different preprocessing offline vs. online), **data/distribution shift** (live inputs differ from training), **data leakage** that inflated offline numbers, or a feature available at training but not at inference time. Mention how you validated each hypothesis (logging live feature distributions, replaying production data offline, slicing metrics by segment).
- **Result**: the concrete fix (e.g. unified the preprocessing pipeline, added monitoring for input drift) and the measurable improvement, plus the guardrail you put in place to prevent recurrence (shadow deployment, automated skew checks).

Signal the interviewer wants: rigor, hypothesis-driven debugging, and that you treat production ML as a system, not just a model.

---

## 🔴 Expert (15+ yrs)

### Q47. [Theory] How do residual connections enable training very deep networks?

A **residual connection** adds a layer's input to its output: `y = x + F(x)`. This was the key innovation (ResNet) that made 100+ layer networks trainable.

```text
        ┌──────────────┐
   x ──►│   F(x)       │──► + ──► y = x + F(x)
   │    └──────────────┘    ▲
   └────────skip────────────┘
```

Two effects: (1) **gradient flow** — backprop through the skip gives `∂y/∂x = 1 + ∂F/∂x`, so the gradient has an unimpeded "+1" path and cannot vanish even through hundreds of layers; (2) **easier optimization** — the layer only has to learn a **residual** (the difference from identity), and learning `F ≈ 0` to preserve a representation is far easier than learning an identity map from scratch. Residual connections are now ubiquitous — every transformer block uses them.

### Q48. [Theory] Discuss the trade-offs in choosing batch size, including the large-batch generalization gap.

Batch size couples optimization, hardware, and generalization.

```text
Small batch: noisy gradients (regularizing), more steps/epoch, may generalize better,
             underuses GPU
Large batch: accurate gradients, fewer steps, high GPU utilization, but can converge to
             "sharp" minima that generalize worse — the large-batch generalization gap
```

Large batches often need **lr scaling** (linear scaling rule: `lr ∝ batch`) plus **warmup** to train stably, and techniques like **LARS/LAMB** for very large batches. There is also a critical/efficient-batch-size regime: below it, doubling the batch nearly halves steps-to-convergence; above it, returns diminish. The practical answer: pick the largest batch that fits and gives good generalization, scale lr accordingly, and use warmup — but don't assume bigger is strictly better.

### Q49. [Theory] How do you scale training across many GPUs? Contrast data, tensor, and pipeline parallelism.

```text
Data parallel:     replicate model on each GPU, split the batch, all-reduce gradients
                   → easy, scales batch; limited when model doesn't fit on one GPU
Tensor parallel:   split individual layers' matrices across GPUs (intra-layer)
                   → needs fast interconnect (NVLink); for huge layers
Pipeline parallel: put different layers on different GPUs, pipeline micro-batches
                   → bubbles reduce efficiency; for very deep models
```

For training large models you combine all three (**3D parallelism**), plus **ZeRO/FSDP** which shards optimizer states, gradients, and parameters across data-parallel ranks to cut per-GPU memory. **Gradient accumulation** simulates a larger batch when memory is tight; **activation checkpointing** trades compute for memory by recomputing activations in the backward pass. The bottleneck is usually **communication**, so overlapping comm with compute and minimizing cross-node traffic is the real engineering challenge.

### Q50. [Theory] What does the "scaling laws" literature tell us about allocating a fixed compute budget?

**Neural scaling laws** show that loss falls as a smooth power-law in model size `N`, dataset size `D`, and compute `C`. The key practical result (Chinchilla, and refinements through 2026) is that for a **fixed compute budget**, model size and training tokens should be scaled **together** — earlier large models (e.g. GPT-3) were significantly **undertrained** (too big for too few tokens).

```text
For fixed compute C ≈ 6·N·D:  scale N and D in roughly equal proportion
(Chinchilla: ~20 tokens per parameter as a rough compute-optimal heuristic)
```

Implications: (1) data quantity *and quality* matter as much as parameter count; (2) given inference-cost constraints, you may deliberately train a *smaller* model on *more* tokens (over the compute-optimal point) because it's cheaper to serve; (3) extrapolating the curve guides whether more compute is worth spending. The nuance an expert adds: laws have break points, data becomes the bottleneck at the frontier, and inference economics (not just training-compute-optimality) increasingly drive the size choice.

### Q51. [Theory] How would you reduce inference latency and memory of a deployed deep model?

Combine techniques across the stack:

```text
Quantization:    FP32 → INT8/INT4; post-training or quantization-aware → smaller, faster
Pruning:         remove low-importance weights/channels (structured prunes for real speedup)
Distillation:    train a small "student" to mimic a large "teacher"
KV-cache:        cache attention keys/values for autoregressive decoding (LLMs)
Batching:        continuous/dynamic batching to raise throughput
Compilation:     fuse ops & optimize graph (torch.compile, TensorRT, ONNX Runtime)
Speculative dec: draft model proposes tokens, big model verifies (LLM latency)
```

The engineering trade-off is **accuracy vs. latency vs. cost**. Start by profiling to find the real bottleneck (compute-bound vs. memory-bandwidth-bound — LLM decoding is usually memory-bound). Quantization (especially INT8/INT4 with modern kernels) and distillation give the biggest wins; for LLMs, KV-caching, paged attention, and continuous batching dominate serving efficiency. Always validate the quality regression on a held-out eval before shipping.

### Q52. [Behavioral] How do you decide whether a problem actually needs deep learning?

A senior engineer resists defaulting to deep learning. A strong answer reasons about fit and cost.

- **Start from the problem and data**: for **tabular** data, gradient-boosted trees (XGBoost/LightGBM) often beat deep nets with less tuning and more interpretability. Deep learning shines on **unstructured** data (images, audio, text) and when you have **lots** of it.
- **Weigh total cost**: data labeling, compute, latency/serving cost, MLOps complexity, interpretability/regulatory needs, and maintenance — not just headline accuracy.
- **Baselines first**: I establish a simple baseline (heuristic or classical ML), quantify the gap, and only invest in deep learning if the expected lift justifies the cost.
- **Consider buy-vs-build**: a pretrained foundation model + light fine-tuning or even prompting may beat training from scratch.

The signal: pragmatism, cost-awareness, and that you optimize for business outcome and total cost of ownership, not for using the fanciest technique.

---

## 🧩 Extended Questions — Set 1: Deeper theory & internals

These go beneath the standard explanations: the actual math that makes the components work, the failure modes that only show up at the margin, and the internals that frameworks hide. Continues numbering from Q52.

### 🟢 — extended

#### Q53. [Theory] Why is the derivative of ReLU undefined at 0, and what do frameworks do about it?

ReLU is `max(0, x)`. Its derivative is `1` for `x > 0` and `0` for `x < 0`, but at exactly `x = 0` there is a **kink** — the left derivative (0) and right derivative (1) disagree, so the classical derivative does not exist.

In practice this never matters. The probability that a pre-activation lands on **exactly** `0.0` in floating point is essentially zero, and even if it does, frameworks just pick a value from the **subgradient** (any value in `[0, 1]` is a valid subgradient at the kink). PyTorch and TensorFlow define `ReLU'(0) = 0` by convention. Subgradient descent on convex piecewise-linear functions like ReLU is well-founded, so optimization proceeds without issue. The same reasoning applies to other kinked activations (Leaky ReLU, the absolute-value unit, hard-tanh).

#### Q54. [Theory] What is the actual derivative of sigmoid, and why is 0.25 the magic number?

For `σ(x) = 1/(1 + e^{-x})`, the derivative has the elegant closed form `σ'(x) = σ(x)·(1 − σ(x))`. You can compute the gradient directly from the cached forward output `a = σ(x)` as `a·(1−a)` — no need to recompute the exponential.

The maximum of `σ(x)(1−σ(x))` occurs at `x = 0`, where `σ = 0.5`, giving `0.5 · 0.5 = 0.25`. So **every** sigmoid in a chain multiplies the gradient by **at most 0.25**. Through `n` stacked sigmoid layers the gradient is scaled by at most `0.25^n` — e.g. through 10 layers, at most `0.25^10 ≈ 9.5e-7`. This is the quantitative root of the vanishing-gradient problem with sigmoid hidden units, and it is why we moved to ReLU (derivative 1 in the active region).

#### Q55. [Theory] When you call `loss.backward()` in PyTorch, what actually happens under the hood?

PyTorch builds a **dynamic computation graph** as the forward pass runs. Every tensor with `requires_grad=True` records the operation that produced it in a `grad_fn` node, forming a directed acyclic graph from inputs to the scalar loss.

`backward()` then performs **reverse-mode automatic differentiation**:

```text
1. Start at the loss node with an implicit upstream gradient of 1.0 (d loss/d loss).
2. Topologically sort the graph; walk it from loss back toward the leaves.
3. At each node, call its grad_fn: multiply the incoming upstream gradient by the
   local Jacobian (vector-Jacobian product) to get the gradient for each input.
4. Accumulate (+=) the result into each leaf tensor's .grad field.
5. Free the saved intermediate buffers (unless retain_graph=True).
```

The `+=` accumulation is exactly why you must call `zero_grad()` — otherwise gradients from the previous batch persist. The graph is rebuilt every forward pass (define-by-run), which is what makes Python control flow (`if`, loops) work transparently inside models.

#### Q56. [Coding] Implement sigmoid and its derivative, and verify the gradient numerically.

```python
import numpy as np

def sigmoid(x):
    return 1.0 / (1.0 + np.exp(-x))

def sigmoid_grad(x):
    s = sigmoid(x)
    return s * (1.0 - s)          # σ'(x) = σ(x)(1−σ(x))

# Verify against a finite-difference (numerical) gradient
x = np.array([-2.0, -0.5, 0.0, 0.5, 2.0])
eps = 1e-6
numerical = (sigmoid(x + eps) - sigmoid(x - eps)) / (2 * eps)   # central diff
analytic = sigmoid_grad(x)

print("max abs error:", np.max(np.abs(numerical - analytic)))   # ~1e-10
assert np.allclose(numerical, analytic, atol=1e-6)
```

The **central-difference** check `(f(x+ε) − f(x−ε)) / 2ε` is `O(ε²)` accurate and is the standard way to validate a hand-written backward pass — if your analytic and numerical gradients disagree beyond ~1e-5, your backward implementation has a bug.

#### Q57. [Theory] Why do we initialize weights randomly instead of to zero, but biases to zero?

If all weights in a layer are initialized to the **same** value (e.g. zero), every neuron in that layer receives the same input, computes the same output, and therefore receives the **same gradient**. They update identically and forever remain duplicates — the layer has the effective capacity of a single neuron. This is the **symmetry-breaking** problem, and random initialization breaks it by giving each neuron a different starting point.

**Biases**, in contrast, can safely start at zero: the neurons are already differentiated by their distinct random weights, so the symmetry is already broken. Zero is a neutral, unopinionated starting bias. (A common exception: ReLU layers sometimes use a small positive bias like 0.01 to keep units in the active region early and avoid dead neurons from step one.)

#### Q58. [Theory] What is the difference between parameters and hyperparameters, and where do buffers fit in?

- **Parameters** are learned by gradient descent from data: weights and biases. In PyTorch these are `nn.Parameter` objects, appear in `model.parameters()`, and receive gradients.
- **Hyperparameters** are set by you before/around training and are *not* learned by backprop: learning rate, batch size, number of layers, dropout rate, weight-decay coefficient. They are tuned on a validation set.
- **Buffers** are a third category: persistent state that is **not** a learned parameter but must be saved with the model — e.g. BatchNorm's running mean/variance, or a positional-encoding table. In PyTorch you register them with `register_buffer`; they move with `.to(device)` and are saved in `state_dict()`, but get no gradient.

Getting this taxonomy right matters for checkpointing (buffers must be saved or BatchNorm breaks at inference) and for optimization (only parameters go to the optimizer).

#### Q59. [Theory] Why does cross-entropy use a logarithm at all?

The log comes from **maximum likelihood estimation**. Minimizing cross-entropy is exactly equivalent to maximizing the log-likelihood of the data under the model's predicted distribution. We maximize the *log*-likelihood rather than the raw likelihood for three reasons:

1. **Products become sums** — the likelihood of independent examples is a product `∏ p_i`; taking the log turns it into a sum `Σ log p_i`, which is numerically stable (products of many small probabilities underflow) and gives clean additive gradients.
2. **Strong gradient when wrong** — `−log(p)` blows up as `p → 0`, so a confidently-wrong prediction produces a large loss and a large corrective gradient. A linear penalty would punish a 0.01 prediction barely more than a 0.4 one.
3. **Information-theoretic meaning** — cross-entropy `−Σ y log ŷ` is the expected number of bits to encode the true label using the model's distribution, and equals the entropy plus the KL divergence between true and predicted distributions.

### 🟡 — extended

#### Q60. [Theory] Walk through the full backward pass of one BatchNorm layer. Why is it more than just normalizing?

BatchNorm's forward is `y = γ·x̂ + β` where `x̂ = (x − μ)/√(σ² + ε)`, and `μ, σ²` are computed **from the batch**. The subtlety in the backward pass is that `μ` and `σ²` **depend on every input in the batch**, so the gradient of one output flows back to *all* inputs, not just its own.

```text
Given upstream dL/dy:
  dγ = Σ (dL/dy · x̂)            # sum over batch
  dβ = Σ (dL/dy)                # sum over batch
  dx̂ = dL/dy · γ
  Then dL/dx must account for x̂'s dependence on μ and σ² (which involve all x):
  dx = (1/(N·√(σ²+ε))) · (N·dx̂ − Σdx̂ − x̂·Σ(dx̂·x̂))
```

The three terms inside say: the gradient to each input is its own normalized gradient, minus the **mean** gradient (because shifting all inputs shifts μ), minus a term proportional to `x̂` (because scaling all inputs changes σ²). This coupling across the batch is precisely why BatchNorm behaves badly with batch size 1 (variance undefined) and why it injects batch-dependent noise that acts as regularization.

#### Q61. [Theory] Why does the residual `y = x + F(x)` formulation specifically help, beyond "gradient flow"?

The standard answer is the `+1` in `∂y/∂x = 1 + ∂F/∂x`. The deeper view is **optimization landscape and identity reachability**:

1. **Identity is the default, not a thing to learn.** Without skips, a layer that should preserve its input must *learn* an identity mapping with its weight matrix — surprisingly hard for non-linear layers. With a skip, identity is achieved by driving `F → 0`, which weight decay and small init naturally encourage. So adding depth can never (in principle) hurt: extra blocks can collapse to no-ops.
2. **Loss landscape smoothing.** Empirically (Li et al., visualizing loss landscapes), skip connections dramatically smooth an otherwise chaotic, non-convex surface into one with a clear minimum, making SGD far more effective.
3. **Ensemble / unrolled view.** A residual network behaves like an **ensemble of many shallower paths** of varying length (Veit et al.) — the network does not depend on any single deep path, which adds robustness and explains why dropping individual blocks at test time barely hurts.

#### Q62. [Theory] What is internal covariate shift, and is it actually why BatchNorm works?

**Internal covariate shift** was the original explanation (Ioffe & Szegedy, 2015): as earlier layers update, the distribution of inputs to later layers keeps shifting, forcing them to constantly re-adapt — and BatchNorm fixes this by stabilizing each layer's input distribution.

The honest 2026 answer is that this explanation is **largely discredited as the primary mechanism**. Santurkar et al. (2018) showed you can *inject* artificial covariate shift after BatchNorm and it still trains fine, and that the real effect is **smoothing the loss landscape** — BatchNorm makes the loss and its gradients more **Lipschitz** (less jumpy), so larger, more stable steps are possible. A strong candidate names internal covariate shift as the historical motivation but explains that the empirically supported reason is gradient/loss smoothing, plus the regularizing batch noise.

#### Q63. [Coding] Implement label smoothing in a cross-entropy loss and explain its effect.

```python
import torch
import torch.nn.functional as F

def smoothed_cross_entropy(logits, targets, n_classes, smoothing=0.1):
    # Build the soft target: (1-ε) on the true class, ε/K spread over all classes
    log_probs = F.log_softmax(logits, dim=-1)
    with torch.no_grad():
        true_dist = torch.full_like(log_probs, smoothing / n_classes)
        true_dist.scatter_(1, targets.unsqueeze(1), 1.0 - smoothing + smoothing / n_classes)
    return torch.mean(torch.sum(-true_dist * log_probs, dim=-1))

# PyTorch has this built in:  F.cross_entropy(logits, targets, label_smoothing=0.1)
```

Label smoothing replaces the hard one-hot target (1 on the true class, 0 elsewhere) with a softened distribution. **Effect**: it discourages the model from becoming **over-confident** — a one-hot target pushes the correct logit toward `+∞`, which hurts calibration and generalization. Smoothing caps the target probability at `1−ε`, yielding better-calibrated probabilities, tighter clustering of representations, and usually a small accuracy gain. It is standard in training image classifiers and transformers (e.g. ε = 0.1).

#### Q64. [Theory] Why does Adam's bias correction matter, and what goes wrong without it?

Adam initializes the moment estimates `m₀ = 0` and `v₀ = 0`. Because they are exponential moving averages starting from zero, in the **early steps** they are biased toward zero — `m_t` and `v_t` systematically **underestimate** the true mean and variance of the gradients.

Without correction, `v_t` is too small early on, so `1/√v_t` is too **large**, producing **enormous, unstable first steps** right when the model is most fragile. The bias-correction terms `m̂ = m_t/(1−β₁ᵗ)` and `v̂ = v_t/(1−β₂ᵗ)` exactly cancel this initialization bias — at `t=1` the denominator `(1−β)` inflates the estimate back to the true scale, and as `t→∞` the correction `→ 1` and vanishes. With `β₂ = 0.999`, the variance estimate would otherwise take **thousands** of steps to warm up, so the correction is essential. (This is also part of why transformers add an explicit warmup on top — defense in depth against early instability.)

#### Q65. [Theory] What is the dying-ReLU problem mechanically, and how do Leaky ReLU / ELU / GELU each address it?

A ReLU unit is **dead** when its pre-activation is negative for **all** inputs in the data. Since `ReLU'(x) = 0` for `x < 0`, that unit receives **zero gradient**, its weights never update, and it is stuck outputting 0 forever — wasted capacity. A common trigger is a large gradient step (often from too-high a learning rate) that pushes the bias very negative.

How variants fix it:

```text
Leaky ReLU: f(x) = x if x>0 else αx   (α≈0.01) → small non-zero slope keeps gradient alive
PReLU:      same, but α is learned per-channel
ELU:        f(x) = x if x>0 else α(eˣ−1)  → smooth, negative saturation, mean closer to 0
GELU:       x·Φ(x)  → smooth gating; small negative region passes gradient probabilistically
```

The common thread: give the negative region a **non-zero gradient** so a "dead" unit can recover. GELU and SiLU/Swish are preferred in transformers because the smoothness also helps optimization, not just liveness.

#### Q66. [Theory] Explain teacher forcing and exposure bias in sequence models.

**Teacher forcing** is the standard way to train an autoregressive model (RNN or decoder transformer): at each step you feed the **ground-truth** previous token as input, rather than the model's own prediction. This makes training fully parallelizable (the whole target sequence is known) and stable, since the model always conditions on a correct prefix.

The downside is **exposure bias**: at **inference** time there is no ground truth — the model must consume **its own** (possibly wrong) previous predictions. It was never trained on its own error distribution, so a single mistake can compound, pushing the sequence into states it never saw during training. Mitigations include **scheduled sampling** (gradually mixing in model predictions during training), sequence-level training (minimizing a sequence metric directly, e.g. via REINFORCE/minimum-risk training), and, for modern LLMs, the sheer scale of training plus RLHF/DPO which expose the model to its own generations. It is worth naming because it is a structural mismatch between train and test, not a tuning issue.

#### Q67. [Coding] Implement gradient checking for an arbitrary scalar-output function.

```python
import numpy as np

def gradient_check(f, grad_f, x, eps=1e-7, tol=1e-5):
    """Compare analytic grad_f(x) against a central finite-difference estimate."""
    analytic = grad_f(x)
    numerical = np.zeros_like(x, dtype=float)
    it = np.nditer(x, flags=["multi_index"])
    while not it.finished:
        idx = it.multi_index
        old = x[idx]
        x[idx] = old + eps; fp = f(x)
        x[idx] = old - eps; fm = f(x)
        x[idx] = old                       # restore
        numerical[idx] = (fp - fm) / (2 * eps)
        it.iternext()
    # relative error is the robust comparison metric
    denom = np.maximum(np.abs(analytic) + np.abs(numerical), 1e-12)
    rel_err = np.max(np.abs(analytic - numerical) / denom)
    return rel_err, rel_err < tol

# Example: f(x) = sum(x^2), grad = 2x
f = lambda x: np.sum(x ** 2)
grad_f = lambda x: 2 * x
x = np.random.randn(5)
print(gradient_check(f, grad_f, x))        # (tiny_rel_err, True)
```

Use **relative** error, not absolute — a 1e-3 absolute error is fine for gradients of magnitude 1000 but catastrophic for gradients of magnitude 1e-4. The central difference with `eps ≈ 1e-7` balances truncation error (grows with eps) against floating-point round-off (grows as eps shrinks). This is the canonical way to verify a from-scratch backprop before trusting it.

### 🟠 — extended

#### Q68. [Theory] Derive why the softmax+cross-entropy gradient simplifies to `ŷ − y`.

Let `z` be the logits, `p = softmax(z)`, and `y` a one-hot target. The loss is `L = −Σ_k y_k log p_k`. We want `∂L/∂z_i`.

```text
∂L/∂z_i = Σ_k (∂L/∂p_k)(∂p_k/∂z_i)

The softmax Jacobian:  ∂p_k/∂z_i = p_k(δ_{ki} − p_i)
And ∂L/∂p_k = −y_k / p_k.

Substitute:
∂L/∂z_i = Σ_k (−y_k/p_k) · p_k(δ_{ki} − p_i)
        = Σ_k −y_k(δ_{ki} − p_i)
        = −y_i + p_i·Σ_k y_k
        = p_i − y_i           (since Σ_k y_k = 1 for a one-hot target)
```

The messy softmax Jacobian `p_k(δ_{ki} − p_i)` is exactly cancelled by the `1/p_k` from the log derivative. The result `p − y` is the prediction error — clean, cheap, and bounded in `[−1, 1]`. This cancellation is *why* the two are paired and why frameworks fuse them into a single `cross_entropy` op (better numerics and one less kernel).

#### Q69. [Theory] What is the effective learning rate per parameter in Adam, and why can it cause generalization issues?

In Adam the update is `Δθ_i = −η · m̂_i / (√v̂_i + ε)`. The factor `1/√v̂_i` gives each parameter its **own effective step size**: parameters with small, consistent gradients (small `v`) get a *large* effective rate; parameters with large or noisy gradients (large `v`) get a *small* one. So Adam is roughly invariant to gradient scale — convenient, fast, robust to lr choice.

The catch on **generalization**: this per-coordinate adaptive scaling tends to drive the model toward **sharper** minima than SGD finds, and several studies show SGD-with-momentum generalizes better on vision tasks despite Adam converging faster. The adaptive rates can also over-shrink steps in flat directions that SGD would explore. This is why (a) AdamW (decoupled decay) was needed to even make regularization work properly under Adam, and (b) some practitioners switch from Adam to SGD late in training, or use Adam for transformers/NLP (where it clearly wins) but SGD+momentum for CNNs.

#### Q70. [Theory] How do RoPE (rotary position embeddings) work and why are they preferred over learned absolute positions?

**RoPE** encodes position by **rotating** the query and key vectors by an angle proportional to the token's absolute position, with different frequencies across embedding dimension pairs. Concretely, it splits the head dimension into 2D pairs and rotates each pair `(x_{2i}, x_{2i+1})` by angle `mθ_i` for position `m`, where `θ_i = base^{−2i/d}`.

The crucial property: because attention scores depend on `qₘ·kₙ`, and rotation is orthogonal, the dot product of a rotated query at position `m` and rotated key at position `n` depends **only on the relative offset `m − n`**, not on absolute `m` and `n`. So RoPE injects **relative** position information directly into the attention dot product without any extra parameters.

Advantages over learned absolute embeddings: (1) **relative** by construction — better for sequences where relative distance matters; (2) **no parameters** to learn; (3) **length extrapolation** — you can run on longer sequences than trained on, especially with frequency-scaling tricks (NTK-aware / YaRN scaling), which is why RoPE dominates modern LLMs (LLaMA, Qwen, etc.) over the original sinusoidal/learned schemes.

#### Q71. [Coding] Implement scaled dot-product attention with a causal mask from scratch.

```python
import torch
import torch.nn.functional as F

def scaled_dot_product_attention(Q, K, V, causal=False):
    # Q,K,V: (batch, heads, seq, d_k)
    d_k = Q.size(-1)
    scores = (Q @ K.transpose(-2, -1)) / (d_k ** 0.5)     # (b, h, seq, seq)

    if causal:
        seq = scores.size(-1)
        # upper-triangular (future positions) set to -inf BEFORE softmax
        mask = torch.triu(torch.ones(seq, seq, device=Q.device), diagonal=1).bool()
        scores = scores.masked_fill(mask, float("-inf"))

    weights = F.softmax(scores, dim=-1)                    # rows sum to 1
    return weights @ V, weights

b, h, s, d = 2, 4, 5, 16
Q = K = V = torch.randn(b, h, s, d)
out, attn = scaled_dot_product_attention(Q, K, V, causal=True)
# Verify causality: position 0 attends only to itself
assert torch.allclose(attn[0, 0, 0, 1:], torch.zeros(s - 1), atol=1e-6)
```

The causal mask sets future positions to `−∞` **before** softmax, so `e^{−∞} = 0` zeroes their weight, guaranteeing each token attends only to itself and earlier tokens — the autoregressive property. In production you'd use `F.scaled_dot_product_attention` (FlashAttention kernels) which fuses these steps and never materializes the full `seq×seq` matrix.

#### Q72. [Theory] What is the memory and compute complexity of self-attention, and how does FlashAttention change the picture?

Standard attention computes the full `seq × seq` score matrix. For sequence length `L` and dimension `d`:

```text
Compute:  O(L²·d)    — the Q·Kᵀ matmul and the weights·V matmul
Memory:   O(L²)      — materializing the L×L attention matrix (the real bottleneck)
```

The `O(L²)` **memory** is what limits context length — at L = 100k the score matrix alone is ~10¹⁰ entries. **FlashAttention** keeps the same `O(L²·d)` compute but reduces memory to **`O(L)`** by never materializing the full matrix: it tiles Q, K, V into blocks that fit in fast SRAM, computes attention block-by-block, and uses the **online softmax** trick (running max and running normalizer) to combine block results without storing all scores. It is also faster in wall-clock terms because it is **IO-aware** — it minimizes slow reads/writes to GPU high-bandwidth memory, which is the actual bottleneck. This (and its successors FlashAttention-2/3 through 2026) is what makes 100k–1M token contexts practical. Separately, **sparse/linear attention** variants attack the `O(L²)` compute itself by approximating the attention pattern.

#### Q73. [Theory] Explain the pre-norm vs post-norm transformer distinction and why pre-norm won for deep stacks.

The original transformer placed LayerNorm **after** the residual add (**post-norm**): `x → Sublayer → +x → LayerNorm`. Modern deep transformers place it **inside**, before the sublayer (**pre-norm**): `x → LayerNorm → Sublayer → +x`.

```text
Post-norm:  out = LayerNorm(x + Sublayer(x))
Pre-norm:   out = x + Sublayer(LayerNorm(x))
```

The key difference is the **residual path**. In pre-norm, the skip connection is a **clean, unnormalized identity** from input to output — gradients flow through it untouched, so very deep stacks (dozens to 100+ layers) train stably, often **without warmup**. In post-norm, every residual passes through a LayerNorm, which can attenuate the gradient and makes deep models unstable and warmup-sensitive. The trade-off: post-norm, when it *can* be trained (with careful warmup), sometimes reaches slightly better final quality, which is why some large models use hybrids (e.g. sandwich/DeepNorm normalization) to get pre-norm stability with post-norm quality. Pre-norm + RMSNorm is the common 2026 default.

#### Q74. [Theory] What is the straight-through estimator and when do you need it?

Some operations have **zero or undefined gradient** everywhere — e.g. `argmax`, hard thresholding, rounding to int (quantization), sampling a discrete token. You cannot backprop through them normally, which blocks end-to-end training of models that contain a discrete bottleneck.

The **straight-through estimator (STE)** is a pragmatic hack: use the non-differentiable op in the **forward** pass, but in the **backward** pass pretend it was the **identity** (pass the gradient straight through unchanged), or substitute a smooth surrogate's gradient.

```text
forward:   y = round(x)          # or sign(x), one-hot(argmax x), quantize(x)
backward:  dy/dx ≈ 1             # treat as identity; gradient flows through
```

It is the workhorse of **quantization-aware training** (round in forward, identity gradient so weights keep learning), **VQ-VAE** (copy the gradient from the quantized code to the encoder output), and **binary/discrete networks**. The estimator is **biased** — you are lying about the gradient — but it works remarkably well in practice. Cleaner alternatives for the sampling case include the **Gumbel-Softmax** relaxation, which provides a genuinely differentiable approximation to discrete sampling.

### 🔴 — extended

#### Q75. [Theory] Explain the Neural Tangent Kernel view of wide networks. What does it tell us, and where does it break?

The **Neural Tangent Kernel (NTK)** theory (Jacot et al., 2018) shows that as a network's width → ∞ (with appropriate parameterization), training by gradient descent behaves like **kernel regression** with a fixed kernel — the NTK — determined at initialization. In this **lazy training** regime the parameters barely move from their init, the network is effectively **linear in its parameters**, and the whole training dynamics become **analytically tractable** (a linear ODE), with provable convergence to a global minimum.

What it tells us: it gives a rigorous explanation for why hugely over-parameterized nets can fit anything yet still optimize easily, and it connects deep nets to classical kernel methods.

Where it **breaks** — and why an expert flags this: the NTK regime corresponds to **no feature learning**. Real, finite-width networks at standard (μP / mean-field) scaling are in the **rich/feature-learning regime** where representations *do* change substantially during training — and feature learning is precisely what makes deep learning powerful (transfer learning, emergent structure). So the NTK is a beautiful **limiting** theory that explains optimization tractability but **fails to capture** the most important empirical phenomenon. It also predicts the wrong scaling behavior for the best practical models. It is a "spherical-cow" model: illuminating, but not where the magic happens.

#### Q76. [Theory] What is double descent, and how does it overturn the classical bias-variance picture?

Classical statistics says test error is U-shaped in model complexity: too simple → underfit (high bias), too complex → overfit (high variance), with a sweet spot in between. **Double descent** (Belkin et al., 2019; Nakkiran et al.) shows that as you keep **increasing** capacity past the **interpolation threshold** (where the model exactly fits/memorizes the training data, train error → 0), the test error, after the classical rise, **descends again** — often to a *lower* value than the classical sweet spot.

```text
test error
 │   classical U        modern regime
 │  ╱‾╲              ╲
 │ ╱   ╲            ╱ ‾‾‾‾─____    ← second descent
 │╱     ╲__________╱   (over-parameterized)
 └───────────────|────────────────► model capacity / epochs
            interpolation threshold (peak)
```

It appears along **three axes**: model size (more parameters), **epochs** (epoch-wise double descent), and even **dataset size** (more data can transiently hurt near the threshold). The mechanism: among the many parameter settings that interpolate the data, gradient descent has an **implicit bias** toward low-norm / "simple" solutions, so massively over-parameterized models pick a smooth interpolant that generalizes. This is a core reason the field stopped fearing over-parameterization and why "just make it bigger" often works — though regularization can soften or remove the peak.

#### Q77. [Theory] Why does training in BF16 work without loss scaling while FP16 needs it? Be precise about the bit layouts.

It comes down to **exponent range vs. mantissa precision**.

```text
FP32:  1 sign | 8 exponent | 23 mantissa   → range ~1e±38, ~7 decimal digits
FP16:  1 sign | 5 exponent | 10 mantissa   → range ~6e-5 .. 65504, ~3 digits
BF16:  1 sign | 8 exponent |  7 mantissa   → range ~1e±38, ~2-3 digits
```

FP16 spends bits on a **10-bit mantissa** but only a **5-bit exponent**, so its smallest normal positive number is ~6e-5. Gradients in deep nets routinely fall below this and **underflow to zero** — the gradient signal silently vanishes. **Loss scaling** (multiply the loss by, say, 2¹⁶ before backward, then unscale before the optimizer step) shifts those tiny gradients up into FP16's representable range.

**BF16** keeps the **full 8-bit exponent** of FP32 — same dynamic range, ~1e-38 — so gradients essentially never underflow; it just has a coarser 7-bit mantissa (less precision per value), which deep-net training tolerates well thanks to its inherent noise. Because there is no underflow problem, BF16 needs **no loss scaling**, which simplifies the training loop. That trade — sacrifice precision, keep range — is exactly why BF16 became the default on Ampere/Hopper/TPU hardware for large-model training.

#### Q78. [Theory] What is the implicit regularization of SGD, and why does it matter for generalization theory?

For an over-parameterized network there are **infinitely many** weight settings that achieve zero training loss, and they generalize very differently. Classical capacity bounds (VC dimension, parameter count) would predict such models *should* overfit catastrophically — yet they generalize. The resolution is that the **optimizer's trajectory**, not just the loss, selects *which* zero-loss solution you land on. SGD has an **implicit bias** toward "nice" solutions.

Concretely, this bias has several characterized forms: SGD tends toward **minimum-norm / max-margin** solutions (provably so for linear models and separable data), prefers **flat minima** over sharp ones (the gradient noise acts like a temperature that escapes sharp basins), and the **noise covariance** of mini-batch gradients steers it toward regions where the loss is flat in many directions. Why it matters: it means **generalization is a property of the (data, architecture, optimizer) triple**, not of the model class alone — you cannot explain deep learning's success from the hypothesis space size. It also has practical teeth: batch size, learning rate, and momentum change the implicit bias and thus the test error, which is why "the same model" can generalize differently under different optimization settings.

#### Q79. [Coding] Implement a numerically stable log-sum-exp and explain where it appears in training.

```python
import numpy as np

def logsumexp(x, axis=None):
    """Stable log(Σ exp(x)) = m + log(Σ exp(x - m)), m = max(x)."""
    m = np.max(x, axis=axis, keepdims=True)
    # subtract the max so the largest exponent is exp(0)=1, no overflow
    stable = m + np.log(np.sum(np.exp(x - m), axis=axis, keepdims=True))
    return stable if axis is None else np.squeeze(stable, axis=axis)

def log_softmax(x, axis=-1):
    return x - logsumexp(x, axis=axis)[..., None]   # log p = z - logsumexp(z)

z = np.array([1000.0, 1001.0, 1002.0])   # naive exp(z) overflows to inf
print(logsumexp(z))                        # ≈ 1002.407, no overflow
print(np.exp(log_softmax(z)))              # valid probabilities, sum to 1
```

The trick factors out the max: `log Σ eˣⁱ = m + log Σ e^{xᵢ − m}`. Since the largest shifted exponent is `e⁰ = 1`, nothing overflows; underflow of the small terms is harmless (they contribute ~0). **Where it appears**: it *is* the denominator of softmax, so it sits inside every classification cross-entropy, every attention softmax, the online-softmax core of FlashAttention, CRF partition functions, and any normalizing constant of a categorical/energy model. This is why frameworks expose fused `log_softmax` and `cross_entropy` rather than letting you compose `log` and `softmax` yourself.

#### Q80. [Theory] What is the lottery ticket hypothesis and what is its practical significance for pruning?

The **lottery ticket hypothesis** (Frankle & Carbin, 2019) claims that a randomly-initialized dense network contains a small **sparse subnetwork** — a "winning ticket" — that, **when trained in isolation from the same initialization**, can match the full network's accuracy in a comparable number of steps. The init is essential: reset the same subnetwork to a *fresh* random init and it trains poorly, so the winning structure *and* its original initial weights together form the ticket.

The procedure that finds them is **iterative magnitude pruning**: train, prune the smallest-magnitude weights, **rewind** the survivors to their original init (or an early-training checkpoint — "rewinding"), and repeat. 

Practical significance and caveats: (1) it suggests dense over-parameterization is mainly an **optimization aid** (more tickets = better odds one wins), not strictly a capacity need; (2) it motivates **sparse training** research — if we could find tickets cheaply we'd skip training the dense net — but finding them currently *requires* training the dense network first, so it is not yet a free lunch for compute savings; (3) for actual inference speedups you need **structured** sparsity (whole channels/heads), whereas lottery tickets are typically **unstructured** (scattered zero weights) which most hardware can't accelerate without special kernels. So its biggest impact is conceptual — it reframed what over-parameterization buys us.

#### Q81. [Theory] Explain grokking and what it implies about the relationship between memorization and generalization.

**Grokking** (Power et al., 2022) is a striking phenomenon on small algorithmic datasets: a network first **memorizes** the training set (train accuracy → 100%, test accuracy stays at chance) and then, after a **long** further period of training — sometimes orders of magnitude more steps with the training loss already near zero — test accuracy **suddenly jumps to ~100%**. Generalization arrives *long after* memorization, seemingly out of nowhere.

```text
acc │ train ─────────────────────────────  (100%, reached early)
    │                          ╱‾‾‾‾‾‾‾  test (sudden late jump = "grok")
    │ test ──chance──────────╱
    └──────────────────────────────────────► steps (log scale)
```

What it implies: (1) **memorization and generalization are distinct mechanisms** that can be cleanly separated in time — the model finds a memorizing solution first, then a *qualitatively different* generalizing circuit; (2) **weight decay is usually critical** — grokking is driven by regularization slowly pushing the network off the high-norm memorizing solution toward a low-norm, structured (generalizing) one, consistent with the implicit/explicit-bias view of generalization; (3) mechanistic-interpretability work has *reverse-engineered* the grokked solution (e.g. networks learning to do modular addition via discrete Fourier features), giving rare ground-truth insight into what "understanding" looks like inside weights. The caution an expert adds: grokking is most cleanly observed on small synthetic tasks and specific regularization regimes — it is a profound illustration of train-loss being a poor proxy for what the model has actually learned, more than a routine training-dynamics you'll hit on real data.

#### Q82. [Theory] How does mechanistic interpretability approach understanding a trained network, and what is superposition?

**Mechanistic interpretability** aims to **reverse-engineer** the algorithms a trained network implements — not just correlate inputs with outputs, but identify the specific weights, neurons, and **circuits** that compute a behavior, the way one would decompile a program. Core notions: a **feature** (a property of the input the model represents, e.g. "is in quotes," "is a Python keyword"), a **circuit** (a subgraph of features and weights that computes something, e.g. induction heads that implement in-context copying), and tools like **activation patching / causal tracing** (intervening on activations to establish causal, not correlational, roles).

**Superposition** is the key obstacle and insight (Elhage et al., 2022): networks represent **more features than they have neurons** by encoding features as **directions in activation space** that are *not* axis-aligned and that **overlap**. A single neuron is **polysemantic** — it activates for several unrelated features — because the model packs many sparse features into a lower-dimensional space, tolerating slight interference. This is why reading individual neurons rarely gives clean concepts.

The 2026 workhorse for undoing superposition is the **sparse autoencoder (SAE)**: train an overcomplete, sparse dictionary on a layer's activations to decompose them into many **monosemantic** features, then study and even **steer** the model by manipulating those features. Why it matters: it is the most promising path to **auditing** models for deception, dangerous capabilities, or hidden biases, and to **steering** behavior at the representation level — moving safety from black-box evaluation toward white-box understanding.

#### Q83. [Practical] You need a custom autograd operation whose naive gradient is unstable. How do you implement a stable custom backward in PyTorch?

When the mathematically-correct backward is numerically fragile (e.g. it divides by an activation that can be tiny, or recomputes an `exp` that overflows), you write a **custom `torch.autograd.Function`** and hand-derive a stable gradient, often reusing the forward output.

```python
import torch

class StableSoftplus(torch.autograd.Function):
    # forward: softplus(x) = log(1+e^x);  derivative = sigmoid(x)
    @staticmethod
    def forward(ctx, x):
        # numerically stable softplus: avoids overflow for large x
        out = torch.where(x > 20, x, torch.log1p(torch.exp(x)))
        ctx.save_for_backward(x)
        return out

    @staticmethod
    def backward(ctx, grad_out):
        (x,) = ctx.saved_tensors
        # d/dx softplus = sigmoid(x); torch.sigmoid is internally stable
        return grad_out * torch.sigmoid(x)

x = torch.tensor([-30., 0., 30., 100.], requires_grad=True)
y = StableSoftplus.apply(x).sum()
y.backward()
print(x.grad)            # sigmoid: ~[0, 0.5, 1, 1], no NaN even at x=100
```

Key principles: (1) **derive the gradient analytically** rather than letting autograd compose unstable sub-ops; (2) **save only what you need** with `ctx.save_for_backward` (saving the input vs. output can change stability and memory); (3) prefer stable primitives (`log1p`, `expm1`, `logsumexp`, `torch.sigmoid`) and branch (`torch.where`) for asymptotic regimes; (4) **always gradient-check** the custom op against `torch.autograd.gradcheck` with double precision before trusting it. This is exactly how library kernels (FlashAttention, fused cross-entropy) achieve stability the naive composition can't.

#### Q84. [Theory] What does it mean for an optimizer or architecture to be "scale-invariant," and why is μP (maximal update parametrization) important?

A component is **scale-invariant** when its behavior doesn't change as you rescale something. Two important cases: (1) layers followed by normalization (BatchNorm/LayerNorm) are **invariant to the scale of their incoming weights** — multiply a pre-norm weight by 10 and the normalized output is unchanged, which interacts subtly with weight decay (it changes the *effective* learning rate rather than the function). (2) The deeper, frontier-relevant case is **width** scale-invariance.

**μP (maximal update parametrization)** (Yang et al.) prescribes how to scale **initialization variance, learning rates, and multipliers as a function of layer width** so that the *training dynamics* — the typical size of activations, the size of weight updates, and the optimal learning rate — stay **stable as width → ∞**. Under standard parametrization, the optimal learning rate **shifts** as you widen the model, so a hyperparameter tuned on a small model is wrong for a large one. Under μP, the optimal hyperparameters become (approximately) **width-independent**.

Why it is a big deal at the frontier: it enables **μTransfer** — tune learning rate, init, etc. **cheaply on a small proxy model**, then transfer those hyperparameters directly to the giant target model with **no re-tuning**, saving enormous compute. Practically, μP/μTransfer (and related "tensor programs" results) is how several 2024–2026 large-model efforts de-risked hyperparameter selection at scale. The conceptual payoff: it puts width-scaling on a principled footing, ensuring every layer gets a "maximal" (neither vanishing nor exploding) update regardless of size, which is the architectural analogue of good initialization but for the *entire training trajectory*.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

This set is hands-on: the symptoms you actually see on a loss curve, the diagnostic moves that isolate a root cause, and the small reproducible snippets that fix or detect each failure. Where Set 1 went into theory and internals, this goes into the day-to-day engineering of getting a model to train, generalize, and ship. Continues numbering from Q84.

### 🟢 — extended

#### Q85. [Practical] Your training loss is stuck flat from step 0 and never moves. Walk through the diagnosis.

A loss that is completely flat (not noisy, not slowly falling — *flat*) almost always means **no gradient is reaching the parameters**, or the step size is effectively zero. Diagnose in this order:

```text
1. learning rate is 0 (or schedule warming up from 0 and you only ran a few steps)
2. requires_grad=False on the params (frozen backbone you forgot to unfreeze)
3. you never called loss.backward(), or called opt.step() before backward()
4. the params aren't in the optimizer (built optimizer before moving model to GPU,
   or passed model.parameters() of the wrong module)
5. a detach()/torch.no_grad()/.item() in the forward path cut the graph
6. gradient is genuinely 0 — all-dead ReLUs, or saturated sigmoid/tanh everywhere
```

Fast confirmation: after `loss.backward()`, print `sum(p.grad.abs().sum() for p in model.parameters() if p.grad is not None)`. If it is `0.0` or there are `None` grads, the graph is broken upstream of the parameters. If grads are non-zero but the loss is flat, your learning rate is too small or the optimizer isn't stepping the right tensors.

#### Q86. [Coding] Write a one-batch overfit sanity check, the first thing to run on any new model.

```python
import torch

def overfit_one_batch(model, xb, yb, loss_fn, steps=200, lr=1e-3):
    """A healthy model must drive a SINGLE batch's loss to ~0. If it can't,
    the bug is in the model/loss/data wiring, not in generalization."""
    opt = torch.optim.Adam(model.parameters(), lr=lr)
    model.train()
    for i in range(steps):
        opt.zero_grad()
        loss = loss_fn(model(xb), yb)
        loss.backward()
        opt.step()
        if i % 50 == 0 or i == steps - 1:
            print(f"step {i:4d}  loss={loss.item():.6f}")
    return loss.item()

# Usage: grab ONE batch and overfit it before any real training run.
# loss should collapse toward 0 within a couple hundred steps.
```

This is the single highest-value debugging habit. If the model **cannot** overfit one batch, you have a wiring bug — wrong loss, label/logit shape mismatch, a `detach` in the graph, targets that don't match inputs, or a learning rate of 0 — and there is no point launching a full run. If it **can** overfit but the real run doesn't generalize, the problem is data/regularization, a completely different investigation.

#### Q87. [Practical] Training and validation curves both plateau at a high loss (underfitting). What do you change?

Underfitting means the model **lacks the capacity or the optimization to fit even the training data** — both curves are high and flat, with a small gap between them. The fixes are the opposite of the overfitting playbook:

```text
Increase capacity:   more layers / wider layers / a more expressive architecture
Train longer:        more epochs; the loss may still be slowly descending
Raise the lr:        too-low lr stalls in a plateau — run an lr range test
Reduce regularization: lower weight decay / dropout; they may be too aggressive
Better features:     normalize/scale inputs; add informative features
Fix optimization:    switch SGD→Adam, add normalization layers, check init
```

The key tell that separates underfitting from overfitting is the **train/val gap**: small gap + high loss = underfitting (add capacity, train harder); large gap = overfitting (regularize, add data). Always confirm the model *can* fit by passing the one-batch overfit test first — if it can overfit one batch but underfits the full set, the bottleneck is usually too little training, too-low lr, or too much regularization.

#### Q88. [Coding] You suspect your input pipeline is the bottleneck, not the GPU. How do you confirm and fix it?

```python
import time, torch

def time_data_vs_compute(model, loader, loss_fn, opt, device, n=50):
    model.train()
    data_t, compute_t = 0.0, 0.0
    t = time.perf_counter()
    for i, (xb, yb) in enumerate(loader):
        torch.cuda.synchronize() if device == "cuda" else None
        data_t += time.perf_counter() - t            # time waiting for the batch
        xb, yb = xb.to(device), yb.to(device)
        opt.zero_grad()
        loss = loss_fn(model(xb), yb); loss.backward(); opt.step()
        torch.cuda.synchronize() if device == "cuda" else None
        compute_t += time.perf_counter() - t          # rough; see note
        t = time.perf_counter()
        if i + 1 == n: break
    print(f"data wait: {data_t:.2f}s   compute: {compute_t:.2f}s")
```

If `data wait` dominates, the GPU is **starving** between batches. Fixes: raise `DataLoader(num_workers=...)` (parallel CPU loading), set `pin_memory=True` and use `.to(device, non_blocking=True)`, enable `persistent_workers=True`, do heavy preprocessing once and cache it, decode/resize images on the GPU, and use `prefetch_factor` to stage batches ahead. A common smoking gun is **low GPU utilization** in `nvidia-smi` (e.g. spiking 0%→90%→0%) — that sawtooth means the input pipeline, not the model, is the bottleneck.

#### Q89. [Practical] Your model trains fine but uses far more GPU memory than expected / OOMs. What are the levers?

Out-of-memory is dominated by **activations stored for backward**, not just the weights. Pull these levers in increasing order of effort:

```text
1. Lower batch size            ← simplest; pair with gradient accumulation to keep effective batch
2. Mixed precision (BF16)      ← ~halves activation memory and weights
3. Gradient accumulation       ← simulate a big batch with small micro-batches
4. Activation checkpointing    ← recompute activations in backward instead of storing them
5. torch.no_grad() at eval     ← don't build a graph during validation/inference
6. Free references             ← detach/.item() logged tensors; don't keep loss tensors in a list
7. Smaller model / FSDP/ZeRO   ← shard params+optimizer states across GPUs
```

Two classic memory leaks worth calling out: **accumulating the loss tensor** (`total += loss` keeps the whole graph alive — use `loss.item()`), and **validating without `torch.no_grad()`** (builds a backward graph you never use). `torch.cuda.memory_summary()` and `max_memory_allocated()` tell you where the memory actually goes.

#### Q90. [Coding] Write a reproducible-seeding setup and explain why runs still differ.

```python
import random, numpy as np, torch

def set_seed(seed=42, deterministic=False):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)
    if deterministic:
        torch.backends.cudnn.deterministic = True
        torch.backends.cudnn.benchmark = False        # disable autotuner (slower)
        torch.use_deterministic_algorithms(True)      # error on nondeterministic ops

set_seed(42, deterministic=True)
# DataLoader workers also need seeding:
def seed_worker(worker_id):
    s = torch.initial_seed() % 2**32
    np.random.seed(s); random.seed(s)
# DataLoader(..., worker_init_fn=seed_worker, generator=torch.Generator().manual_seed(42))
```

Even with all seeds fixed, runs can still differ because: (1) some **cuDNN/atomic GPU kernels are nondeterministic** by default (floating-point reduction order varies) — `use_deterministic_algorithms(True)` forces deterministic paths at a speed cost; (2) **`cudnn.benchmark=True`** autotunes kernels per input shape, picking different (faster) algorithms run-to-run; (3) **multi-worker DataLoaders** need their own seeding via `worker_init_fn`; (4) different hardware/library versions change kernel selection. Full bitwise reproducibility costs performance, so most teams settle for "seeded and deterministic enough to compare runs."

#### Q91. [Practical] How do you normalize input features correctly without leaking the test set?

Compute normalization statistics on the **training split only**, then apply those same fixed statistics to validation and test. Computing mean/std over the full dataset before splitting leaks information about the held-out data into your preprocessing — a subtle but real form of data leakage that inflates your reported metric.

```python
mu = X_train.mean(axis=0)
sd = X_train.std(axis=0) + 1e-8        # eps avoids divide-by-zero on constant features
X_train = (X_train - mu) / sd
X_val   = (X_val   - mu) / sd          # SAME train stats, not val's own
X_test  = (X_test  - mu) / sd          # SAME train stats
```

The same rule applies to any fitted transform — PCA, target encoding, imputation, tokenizer vocab, image channel means. Fit on train, freeze, apply everywhere. In sklearn, this is exactly why you `fit` the scaler inside a `Pipeline` on the training fold only (and why cross-validation must re-fit the scaler per fold). For images, the convention is to use well-known dataset means/stds (e.g. ImageNet) when fine-tuning a pretrained model so inputs match the backbone's training distribution.

#### Q92. [Coding] Implement a learning-rate range test (the "LR finder") to pick a learning rate.

```python
import torch, copy

def lr_range_test(model, loader, loss_fn, lr_min=1e-7, lr_max=1.0, n_steps=100):
    state = copy.deepcopy(model.state_dict())     # so we can restore after the probe
    opt = torch.optim.SGD(model.parameters(), lr=lr_min)
    mult = (lr_max / lr_min) ** (1 / n_steps)     # geometric ramp
    lrs, losses, lr = [], [], lr_min
    model.train()
    it = iter(loader)
    for _ in range(n_steps):
        try: xb, yb = next(it)
        except StopIteration: it = iter(loader); xb, yb = next(it)
        for g in opt.param_groups: g["lr"] = lr
        opt.zero_grad()
        loss = loss_fn(model(xb), yb); loss.backward(); opt.step()
        lrs.append(lr); losses.append(loss.item())
        lr *= mult
        if loss.item() > 4 * min(losses): break   # diverged; stop early
    model.load_state_dict(state)                  # undo the probe's damage
    return lrs, losses     # plot loss vs log(lr); pick lr ~1 order below the minimum
```

You ramp the learning rate **exponentially** over ~100 mini-batches and plot loss against `log(lr)`. The curve falls, bottoms out, then shoots up as the lr gets too large. Pick a learning rate roughly **one order of magnitude below the point of steepest descent / the minimum** — that's the largest rate that still trains stably. This (Smith's LR finder) gives a good starting lr in seconds instead of a multi-run sweep, and it's the basis of one-cycle scheduling. Remember to restore the model's weights afterward, since the probe perturbed them.

### 🟡 — extended

#### Q93. [Practical] Loss is decreasing but validation accuracy is flat or jumps around randomly. What's going on?

A *falling loss with non-moving accuracy* points to a mismatch between the loss surface and the metric, or a label/eval bug. Check these:

```text
1. Label↔logit shape/index bug: classes off-by-one, argmax over wrong axis,
   or comparing probabilities to class indices in your accuracy fn.
2. Severe class imbalance: loss drops by predicting the majority class;
   accuracy is "flat" because it's pinned at the base rate. Check per-class metrics.
3. model.eval() not called: dropout/BN active at eval → noisy, wrong predictions.
4. Metric computed on the wrong split, or accumulated incorrectly (resetting per batch).
5. lr too high: loss trends down on average but predictions thrash → accuracy jitters.
6. The loss is improving in a region the metric doesn't care about (e.g. calibrated
   probabilities improving while the argmax is unchanged).
```

The fastest disambiguation: print a **confusion matrix** and **per-class precision/recall**, not just top-line accuracy. If everything is one class, it's imbalance or a collapse; if it's scattered, suspect an eval/labeling bug or `model.eval()`. Also verify accuracy is computed under `model.eval()` + `torch.no_grad()`.

#### Q94. [Coding] Handle class imbalance three ways (weighted loss, weighted sampler, focal loss).

```python
import torch, torch.nn as nn, torch.nn.functional as F
from torch.utils.data import WeightedRandomSampler

# 1) Class-weighted cross-entropy: weight ∝ inverse class frequency
counts = torch.tensor([9000., 1000.])               # e.g. 90/10 split
class_w = (counts.sum() / (len(counts) * counts))    # balanced weights
loss_fn = nn.CrossEntropyLoss(weight=class_w)

# 2) Weighted sampler: rebalance the BATCHES instead of the loss
sample_w = class_w[train_labels]                     # per-example weight
sampler = WeightedRandomSampler(sample_w, num_samples=len(sample_w), replacement=True)
# DataLoader(dataset, sampler=sampler, batch_size=64)

# 3) Focal loss: down-weight easy examples, focus on hard/minority ones
def focal_loss(logits, targets, gamma=2.0, alpha=0.25):
    ce = F.cross_entropy(logits, targets, reduction="none")
    pt = torch.exp(-ce)                               # prob of the true class
    return (alpha * (1 - pt) ** gamma * ce).mean()
```

Pick by situation: **weighted loss** is simplest and usually first to try; a **weighted sampler** physically rebalances batches (helps when BatchNorm statistics are skewed by imbalance); **focal loss** (RetinaNet) is best for *extreme* imbalance like dense object detection, since `(1−pₜ)^γ` shrinks the contribution of confidently-correct easy examples so the gradient focuses on the hard minority. Crucially, **never** evaluate an imbalanced problem with accuracy alone — use PR-AUC, F1, or per-class recall, and keep the **test set at the true (imbalanced) distribution**.

#### Q95. [Practical] Offline metrics are great but the model is worse in production. How do you root-cause train/serve skew?

This is the canonical production-ML failure. Work through the likely causes systematically, validating each with data rather than guessing:

```text
1. Train/serve preprocessing skew: features computed differently offline vs. online
   (different tokenizer, different normalization stats, a fillna that differs). Most common.
2. Data/distribution shift: live inputs differ from training (new users, seasonality,
   a changed upstream feature). Log live feature distributions; compare to training (PSI/KL).
3. Data leakage offline: a feature available at train time but not (or with a different
   value) at inference — e.g. a label-derived or future feature inflated offline numbers.
4. Feature freshness/latency: a feature that's hours stale in serving but fresh in training.
5. Eval/label skew: offline labels collected differently than the production objective.
```

The decisive tactics: **replay production traffic through the offline pipeline** and check predictions match; **log the exact feature vector at serving time** and diff it against the offline-computed one for the same entity; **slice metrics by segment** (the drop is often concentrated in one cohort). The durable fix is a **single shared feature-transformation path** (a feature store or shared library) so training and serving cannot diverge, plus **monitoring on input distributions** and a **shadow/canary deployment** to catch skew before full rollout.

#### Q96. [Coding] Implement gradient accumulation to simulate a large batch under a memory limit.

```python
import torch

def train_with_accumulation(model, loader, loss_fn, opt, accum_steps=4):
    model.train()
    opt.zero_grad()
    for i, (xb, yb) in enumerate(loader):
        loss = loss_fn(model(xb), yb)
        loss = loss / accum_steps          # scale: gradients sum, so average them
        loss.backward()                    # accumulates into .grad (no zero_grad here)
        if (i + 1) % accum_steps == 0:
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            opt.zero_grad()                # only reset AFTER the optimizer step
```

Gradient accumulation runs `accum_steps` micro-batches, lets their gradients **sum** in `.grad`, and only calls `opt.step()` once — giving the same update as one large batch of size `micro_batch × accum_steps`, but with the memory of a single micro-batch. The two correctness details people miss: **divide the loss by `accum_steps`** (otherwise you sum gradients instead of averaging, effectively `accum_steps`× the learning rate), and **only `zero_grad()` after stepping**, not every micro-batch. Caveat: **BatchNorm statistics** are still computed per micro-batch, so accumulation doesn't perfectly replicate a true large batch for BN-heavy models (LayerNorm models like transformers are unaffected).

#### Q97. [Practical] How do you decide between fixing the data and tuning the model when results are bad?

Senior engineers spend their effort where the leverage is, and on most real problems the leverage is in the **data**. A disciplined approach:

```text
1. Error-analyze first: pull 50–100 of the worst errors and read them. Patterns emerge —
   mislabeled examples, a missing slice, ambiguous cases, a systematic preprocessing bug.
2. Estimate the ceiling: measure human/label agreement. If labels are 8% inconsistent,
   no model tuning gets you past ~92% — the cap is data quality, not architecture.
3. Quantify slices: if errors concentrate in one segment, fix that segment's data/coverage
   rather than globally tuning hyperparameters.
4. Cheap model levers first: lr, regularization, more epochs — minutes of effort.
   Only escalate to architecture changes if data and basic tuning are exhausted.
```

The rule of thumb: **if the model can overfit the training set but generalizes poorly, and error analysis shows label noise or missing coverage, fix the data**; if it can't even fit the training set, fix the model/optimization. "Data-centric AI" exists because, beyond a baseline architecture, cleaning labels, adding targeted examples, and improving coverage usually beats yet another hyperparameter sweep. Always read your errors before touching a knob.

#### Q98. [Coding] Profile a training step to find the actual bottleneck with the PyTorch profiler.

```python
import torch
from torch.profiler import profile, ProfilerActivity, schedule

def profile_training(model, batch, loss_fn, opt, device="cuda"):
    xb, yb = batch
    acts = [ProfilerActivity.CPU, ProfilerActivity.CUDA]
    with profile(activities=acts, record_shapes=True, profile_memory=True,
                 schedule=schedule(wait=1, warmup=1, active=3)) as prof:
        for _ in range(5):
            opt.zero_grad()
            loss = loss_fn(model(xb), yb)
            loss.backward()
            opt.step()
            prof.step()
    # Sort by self CUDA time to find the hottest GPU kernels
    print(prof.key_averages().table(sort_by="self_cuda_time_total", row_limit=15))
    # prof.export_chrome_trace("trace.json")  # view in chrome://tracing / Perfetto
```

Profile before optimizing — intuition about bottlenecks is usually wrong. The profiler attributes time to specific ops (matmuls, the data copy, a Python loop, kernel launches) and flags **memory** hotspots. Common findings: tiny kernels dominated by **launch overhead** (fix with `torch.compile` to fuse them), an unexpected **CPU↔GPU sync** (a `.item()` or `.cpu()` in the hot loop), or the **data copy** (`H2D`) eclipsing compute (fix with `pin_memory` + `non_blocking`). The `wait/warmup/active` schedule skips the noisy first steps so you measure steady state.

#### Q99. [Practical] Your loss curve has a sudden spike mid-training (was converging, then jumped). Diagnose and recover.

A mid-training spike — loss was smoothly falling, then leaps up (sometimes to NaN) — usually means a single pathological update destabilized the model. Causes and responses:

```text
Causes:                                  Responses:
- a bad/outlier batch → huge gradient    → gradient clipping (clip_grad_norm_)
- lr too high for the current curvature  → lower lr / use warmup / cosine decay
- FP16 overflow on a rare large value    → switch to BF16 or adjust loss scaling
- a corrupt/NaN sample slipped in         → validate/clean inputs; skip-on-NaN guard
- learning-rate schedule bug (lr jumped)  → check the scheduler
```

Recovery and prevention: train with **checkpointing** so you can **resume from the last good checkpoint** (before the spike) rather than restarting; add a guard that **skips the optimizer step if the loss/grad-norm is non-finite** so one bad batch can't poison the run:

```python
loss.backward()
gn = torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
if torch.isfinite(loss) and torch.isfinite(gn):
    opt.step()
else:
    opt.zero_grad()          # drop this batch's update, keep training
```

This skip-on-non-finite pattern, plus gradient clipping and frequent checkpoints, is standard practice in large-model training where a few bad batches over millions of steps are inevitable.

#### Q100. [Coding] Implement EMA (exponential moving average) of weights and explain why it helps.

```python
import copy, torch

class EMA:
    def __init__(self, model, decay=0.999):
        self.decay = decay
        self.shadow = copy.deepcopy(model).eval()
        for p in self.shadow.parameters():
            p.requires_grad_(False)

    @torch.no_grad()
    def update(self, model):                       # call AFTER opt.step()
        for s, p in zip(self.shadow.parameters(), model.parameters()):
            s.mul_(self.decay).add_(p, alpha=1 - self.decay)
        for s, p in zip(self.shadow.buffers(), model.buffers()):
            s.copy_(p)                              # copy BN running stats verbatim

# train as usual, then EVALUATE with ema.shadow, not the raw model
ema = EMA(model, decay=0.999)
# ... inside loop: opt.step(); ema.update(model)
```

EMA maintains a slowly-moving average of the weights: `θ_ema ← decay·θ_ema + (1−decay)·θ`. Because SGD/Adam weights **oscillate** around a minimum (especially late in training with a non-zero lr), the averaged weights sit closer to the **center of the basin** — a flatter, better-generalizing point — so the EMA model almost always evaluates **better and more stably** than the raw weights. It costs one extra copy of the parameters and a cheap update per step. It is standard in diffusion models, self-supervised methods (the teacher in BYOL/DINO is an EMA of the student), semi-supervised learning (Mean Teacher), and many SOTA image classifiers. Evaluate and ship the EMA weights, not the training weights.

#### Q101. [Practical] How do you set up checkpointing so you can resume training exactly where it left off?

Resuming *exactly* requires saving more than the model weights — you must restore the **full training state** so the next step is identical to what it would have been without interruption:

```python
import torch
# SAVE (periodically + on best val)
torch.save({
    "epoch": epoch, "global_step": step,
    "model": model.state_dict(),
    "optimizer": opt.state_dict(),       # Adam's m,v moments live here — essential
    "scheduler": sched.state_dict(),     # so the lr schedule continues correctly
    "scaler": scaler.state_dict(),       # AMP loss-scaler state
    "rng": torch.get_rng_state(),        # for reproducible data order/augmentation
    "best_val": best_val,
}, "ckpt.pt")

# RESUME
ckpt = torch.load("ckpt.pt", map_location="cpu")
model.load_state_dict(ckpt["model"]); opt.load_state_dict(ckpt["optimizer"])
sched.load_state_dict(ckpt["scheduler"]); scaler.load_state_dict(ckpt["scaler"])
start_epoch = ckpt["epoch"] + 1
```

The frequent mistake is saving **only `model.state_dict()`**, then resuming — the optimizer restarts with zeroed Adam moments and the scheduler restarts its lr, causing a visible loss bump at the resume point. Save the optimizer, scheduler, AMP scaler, and (for strict reproducibility) RNG state. Keep both a **rolling "latest"** checkpoint (for crash recovery) and a **"best val"** checkpoint (for final model selection), and save atomically (write to a temp file, then rename) so a crash mid-save can't corrupt your only checkpoint.

#### Q102. [Coding] Write a quick test that catches train/eval-mode and no_grad bugs before they cost you a run.

```python
import torch

def assert_eval_mode_correct(model, sample_batch):
    """Catch the two most common eval bugs: forgetting model.eval()
    and forgetting torch.no_grad()."""
    xb, _ = sample_batch

    # 1) eval() must make dropout/BN deterministic: two eval passes must match.
    model.eval()
    with torch.no_grad():
        a = model(xb); b = model(xb)
    assert torch.allclose(a, b), "Non-deterministic in eval mode — dropout/BN still active?"

    # 2) train() with dropout should generally differ across passes.
    model.train()
    with torch.no_grad():
        c = model(xb); d = model(xb)
    if not torch.allclose(c, d):
        pass  # expected when dropout is present — good

    # 3) no_grad must actually stop graph building (no grad_fn on the output).
    model.eval()
    with torch.no_grad():
        out = model(xb)
    assert out.grad_fn is None, "Graph still built under no_grad — memory will balloon"
    print("eval-mode and no_grad checks passed")
```

These two bugs — leaving the model in `train()` mode at inference (so dropout randomly zeros activations and BatchNorm uses noisy batch stats instead of running stats) and forgetting `torch.no_grad()` (which silently builds a backward graph during eval, wasting memory and risking OOM) — are responsible for a large share of "my validation numbers are noisy/wrong" reports. A tiny assertion like this in your test suite catches both deterministically, before a long run wastes hours.

### 🟠 — extended

#### Q103. [Practical] A 7B-parameter model won't fit for fine-tuning on your single GPU. Lay out the options in order.

The memory budget for full fine-tuning is roughly `weights + gradients + optimizer states + activations`. For Adam in FP32 that's ~`16 bytes × params` *before* activations — ~112 GB for 7B, far over a single GPU. Reduce it in this order:

```text
1. PEFT (LoRA/QLoRA): freeze the base, train tiny low-rank adapters. QLoRA 4-bit-quantizes
   the frozen base → a 7B fine-tunes comfortably on a single 24 GB GPU. First choice.
2. Mixed precision (BF16): halves weight+activation memory vs FP32.
3. 8-bit / paged optimizers (bitsandbytes): shrink Adam's m,v states.
4. Gradient checkpointing: trade compute for activation memory.
5. Gradient accumulation: keep effective batch large with a tiny micro-batch.
6. CPU/NVMe offload (ZeRO-Offload) or multi-GPU sharding (FSDP/ZeRO-3) if you have more hardware.
```

The decisive 2026 answer is **QLoRA**: load the base model in 4-bit (NF4), keep it frozen, and train LoRA adapters in BF16 — this collapses the dominant `weights + optimizer-states` cost because you only optimize <1% of parameters, and the 4-bit frozen base is a quarter of the memory. Combined with gradient checkpointing it routinely fine-tunes 7B–13B models on a single consumer GPU with minimal quality loss versus full fine-tuning.

#### Q104. [Coding] Implement a custom Dataset/DataLoader with on-the-fly augmentation and a collate function.

```python
import torch
from torch.utils.data import Dataset, DataLoader

class TextDataset(Dataset):
    def __init__(self, samples, tokenizer, max_len=128, augment=None):
        self.samples, self.tok, self.max_len, self.aug = samples, tokenizer, max_len, augment

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, idx):
        text, label = self.samples[idx]
        if self.aug:                       # augmentation applied per-access (so it varies per epoch)
            text = self.aug(text)
        ids = self.tok.encode(text)[: self.max_len]
        return torch.tensor(ids), torch.tensor(label)

def pad_collate(batch):
    """Dynamic padding: pad each batch to ITS longest sequence, not a global max."""
    seqs, labels = zip(*batch)
    lengths = torch.tensor([len(s) for s in seqs])
    maxlen = lengths.max()
    padded = torch.zeros(len(seqs), maxlen, dtype=torch.long)
    for i, s in enumerate(seqs):
        padded[i, : len(s)] = s
    attn_mask = (padded != 0).long()
    return padded, attn_mask, lengths, torch.stack(labels)

loader = DataLoader(dataset, batch_size=32, shuffle=True, collate_fn=pad_collate,
                    num_workers=4, pin_memory=True, persistent_workers=True)
```

Two ideas worth articulating in an interview: augmentation belongs in `__getitem__` so it is **re-sampled every epoch** (caching the augmented result would defeat its purpose), and a custom **`collate_fn`** lets you do **dynamic padding** — padding each batch only to its own longest sequence rather than a fixed global maximum, which can cut wasted compute dramatically on variable-length data. Pair it with a length-bucketing sampler to minimize padding further.

#### Q105. [Practical] How do you debug a multi-GPU (DDP) run that hangs, deadlocks, or diverges from single-GPU?

Distributed bugs have a distinct signature set. Triage by symptom:

```text
HANG / DEADLOCK at start or mid-step:
 - A collective (all_reduce) called on some ranks but not others → conditional code that
   runs forward/backward on only some ranks. Every rank must hit every collective.
 - Uneven batches: the last batch leaves one rank with no data → use DistributedSampler
   with drop_last, or join() context. Mismatched data counts deadlock the all_reduce.
 - A parameter that gets no gradient (unused in the loss) → set find_unused_parameters=True
   (or better, fix the model so all params contribute).
DIVERGES vs SINGLE-GPU / metrics look off:
 - Forgot DistributedSampler → every rank sees the SAME data (no real data parallelism).
 - Not adjusting lr for the larger effective batch (linear scaling rule + warmup).
 - Logging/metric only correct if you all_reduce the metric across ranks.
 - BatchNorm computing stats per-GPU on a tiny local batch → use SyncBatchNorm.
```

Practical workflow: **reproduce on 2 GPUs** before scaling out; set `NCCL_DEBUG=INFO` and `TORCH_DISTRIBUTED_DEBUG=DETAIL` to surface which collective/rank stalls; confirm the **same code path executes on every rank** (no rank-conditional `if rank == 0: forward`); use a `DistributedSampler` (and call `set_epoch` each epoch so shuffling differs); and remember that **only rank 0** should write checkpoints/logs while metrics must be **all-reduced** to be correct. The most common silent bug is forgetting `DistributedSampler`, which makes every GPU train on identical data — training "works" but you get no speedup in convergence.

#### Q106. [Coding] Implement a cosine schedule with linear warmup from scratch (no library).

```python
import math

class WarmupCosine:
    """Linear warmup for `warmup` steps, then cosine decay to lr_min over `total` steps."""
    def __init__(self, optimizer, warmup, total, lr_max, lr_min=0.0):
        self.opt, self.warmup, self.total = optimizer, warmup, total
        self.lr_max, self.lr_min, self.step_num = lr_max, lr_min, 0

    def step(self):
        self.step_num += 1
        t = self.step_num
        if t <= self.warmup:                          # linear ramp 0 → lr_max
            lr = self.lr_max * t / max(1, self.warmup)
        else:                                         # cosine decay lr_max → lr_min
            progress = (t - self.warmup) / max(1, self.total - self.warmup)
            progress = min(progress, 1.0)
            lr = self.lr_min + 0.5 * (self.lr_max - self.lr_min) * (1 + math.cos(math.pi * progress))
        for g in self.opt.param_groups:
            g["lr"] = lr
        return lr

# call sched.step() once PER OPTIMIZER STEP (not per epoch) for transformer-style training
```

Implementing this by hand is a common interview ask because it forces you to get the details right: warmup is **linear from 0 to lr_max** over the first `warmup` steps (stabilizing Adam while its variance estimates are still noisy — critical for transformers), then a **half-cosine** decays smoothly from `lr_max` to `lr_min`. The two bugs to avoid: stepping the scheduler **per epoch instead of per step** (transformers schedule per step), and forgetting to **clamp `progress ≤ 1`** so the lr doesn't go negative if you train past `total` steps.

#### Q107. [Practical] Inference is too slow / too expensive in production. Walk through the optimization decision tree.

Always **profile first** to learn whether you're **compute-bound** or **memory-bandwidth-bound** — the fix differs completely, and for LLM decoding it's almost always memory-bandwidth-bound (you re-read the weights every token).

```text
Step 1 — Profile: latency breakdown, GPU util, is it compute- or memory-bound?
Step 2 — Cheap wins (no quality loss):
   - torch.compile / TensorRT / ONNX Runtime: fuse ops, optimize the graph
   - Batching: dynamic/continuous batching to amortize fixed costs (huge for throughput)
   - KV-cache for autoregressive decoding (don't recompute past tokens)
Step 3 — Quality-preserving compression:
   - Quantization: INT8 (often ~lossless) → INT4 for LLM weights with modern kernels
   - Right-size the model / use a distilled student
Step 4 — Algorithmic (LLM-specific):
   - PagedAttention (vLLM) for memory-efficient KV-cache + high concurrency
   - Speculative decoding: small draft model proposes, big model verifies → lower latency
Step 5 — Validate: re-run your held-out eval after EACH change; never ship blind compression.
```

The discipline that separates a senior answer: **measure the bottleneck before optimizing** (latency vs. throughput are different goals — batching helps throughput but can hurt single-request latency), apply **lossless** wins first (compilation, batching, KV-cache), then **compression with an accuracy gate**, and recognize that for LLM serving the dominant levers are **KV-cache management, continuous batching, and quantization**, not shaving FLOPs. Every compression step must be followed by a regression check on a real eval set.

#### Q108. [Coding] Quantize a trained model to INT8 (dynamic and static) in PyTorch and note the trade-offs.

```python
import torch

# --- Dynamic quantization: weights INT8, activations quantized on the fly. ---
# Best for Linear/LSTM-heavy models (NLP). Zero calibration data needed.
model_int8 = torch.quantization.quantize_dynamic(
    model, {torch.nn.Linear}, dtype=torch.qint8
)

# --- Static (post-training) quantization: weights AND activations INT8. ---
# Needs CALIBRATION data to record activation ranges. Best for CNNs.
model.eval()
model.qconfig = torch.quantization.get_default_qconfig("fbgemm")   # x86 backend
torch.quantization.prepare(model, inplace=True)                    # insert observers
with torch.no_grad():
    for xb, _ in calibration_loader:                               # run a few hundred batches
        model(xb)                                                  # observers record ranges
torch.quantization.convert(model, inplace=True)                    # fold to real INT8

# Always re-measure accuracy on a held-out eval after quantizing.
```

The trade-offs to articulate: **dynamic** quantization is the easiest (no calibration, quantizes activations at runtime) and shines for **Linear/RNN-dominated** models where weight loading dominates; **static** PTQ quantizes activations too using ranges learned from **calibration data**, giving more speedup for **CNNs** but needing a representative calibration set; and when PTQ drops accuracy too far, **quantization-aware training (QAT)** simulates quantization (round in forward, straight-through gradient in backward) during fine-tuning to recover most of the gap. INT8 is usually near-lossless; INT4 (common for LLM *weights*) needs careful, modern kernels. The non-negotiable step is **re-evaluating accuracy after quantizing** — never assume the speedup is free.

#### Q109. [Practical] How do you detect and handle distribution shift / model staleness in production?

A deployed model silently degrades as the world drifts from its training distribution. You need **monitoring, detection, and a response policy** because ground-truth labels often arrive late or never.

```text
Monitor (no labels needed):
 - Input drift: track feature distributions; alert on PSI / KL / KS-test vs. a training baseline.
 - Prediction drift: watch the output-score distribution and class mix over time.
 - Embedding drift: compare distributions of internal representations.
Monitor (with delayed labels):
 - Rolling accuracy/AUC as labels land; segment-sliced so a localized drop isn't masked.
Operational signals:
 - Confidence collapse, rising abstention rate, more out-of-distribution / novel inputs.
```

Detection should distinguish **covariate shift** (inputs change, `P(x)`) from **concept drift** (the input→label relationship changes, `P(y|x)`) — the latter is more dangerous and needs new labels to confirm. The response ladder: **alert → investigate the slice → decide**. For covariate shift, options are retraining on fresh data, importance-weighting, or expanding coverage; for concept drift, you generally must **retrain/relabel**. Operationally: keep a **rolling retraining pipeline**, gate every new model behind a **shadow then canary** rollout with automatic rollback on metric regression, and treat the model as a **continuously-maintained system**, not a one-time artifact. Out-of-distribution detection (e.g. flagging low-density inputs) lets you route uncertain cases to a human fallback.

#### Q110. [Coding] Implement MC-Dropout to get calibrated uncertainty estimates at inference.

```python
import torch
import torch.nn.functional as F

@torch.no_grad()
def mc_dropout_predict(model, x, n_samples=30):
    """Keep dropout ON at inference; multiple stochastic passes approximate
    a Bayesian posterior predictive → mean prediction + uncertainty."""
    model.eval()
    for m in model.modules():                 # re-enable ONLY dropout layers
        if isinstance(m, (torch.nn.Dropout, torch.nn.Dropout2d)):
            m.train()
    preds = torch.stack([F.softmax(model(x), dim=-1) for _ in range(n_samples)])  # (T, B, C)
    mean = preds.mean(0)                       # predictive mean
    # Two uncertainty signals:
    epistemic = preds.var(0).sum(-1)           # disagreement across passes (model uncertainty)
    entropy = -(mean * (mean + 1e-12).log()).sum(-1)   # total predictive entropy
    return mean, epistemic, entropy
```

MC-Dropout interprets dropout as approximate **Bayesian inference**: by leaving dropout **active at inference** and running `T` stochastic forward passes, you sample from an approximate posterior over the network. The **mean** is your prediction; the **variance/disagreement** across passes is **epistemic uncertainty** (what the model doesn't know — high on novel inputs), distinct from the inherent **aleatoric** noise captured by the predictive entropy. It's nearly free to add (no retraining, just keep dropout on and loop), which is why it's a popular baseline for uncertainty in production — useful for **abstaining** on low-confidence inputs, routing them to a human, or flagging out-of-distribution data. The subtlety to get right: enable **only the dropout modules** in train mode while keeping BatchNorm in eval mode (you want stochastic dropout, not noisy batch statistics).

### 🔴 — extended

#### Q111. [Practical] You're training a large model and the loss diverges only after thousands of steps (not immediately). How do you find and fix it?

A *late* divergence (smooth for thousands of steps, then a spike or slow blow-up) is harder than an immediate one because the trigger is subtle. The expert workflow:

```text
Instrument continuously (cheap, always-on):
 - per-layer gradient norms and weight norms over time (look for a slow-growing layer)
 - the max activation magnitude per layer (an activation creeping toward FP16/BF16 limits)
 - the loss-scaler value (AMP) — repeated halving signals recurring overflow
 - attention-logit max for transformers (the classic culprit; see below)
Likely causes of LATE divergence specifically:
 - Attention/logit growth: unbounded attention logits grow over training until softmax
   saturates → use QK-LayerNorm / logit soft-capping / z-loss on the logits.
 - Slow weight-norm growth from too little weight decay → loss eventually destabilizes.
 - An optimizer-state pathology (Adam v underestimating a rare-but-large gradient direction).
 - A rare bad batch finally appearing in the data (long-tail outlier).
```

The decisive moves used at scale: keep **frequent checkpoints** and, when divergence hits, **rewind to a checkpoint before it and lower the lr or add regularization** for that region; add a **z-loss** (a small penalty on the log-partition / logit magnitude) and **QK-normalization** to keep attention logits bounded — these are now standard in frontier LLM training precisely because they prevent the slow logit-growth divergence; tighten **gradient clipping**; and add a **skip-step-on-non-finite** guard so a single long-tail batch can't end the run. The general principle: late divergence is usually a **slowly accumulating quantity** (a norm, a logit, a scaler) crossing a threshold — so you must **log trends, not just the loss**, to catch it.

#### Q112. [Coding] Implement activation checkpointing manually and quantify the compute/memory trade-off.

```python
import torch
import torch.utils.checkpoint as cp

class CheckpointedBlock(torch.nn.Module):
    def __init__(self, block):
        super().__init__()
        self.block = block

    def forward(self, x):
        # Don't store this block's internal activations; recompute them in backward.
        return cp.checkpoint(self.block, x, use_reentrant=False)

# Or checkpoint a whole sequence of layers in segments:
def forward_checkpointed(layers, x, segments=4):
    chunk = max(1, len(layers) // segments)
    def run(start):
        def fn(inp):
            for layer in layers[start:start + chunk]:
                inp = layer(inp)
            return inp
        return fn
    for start in range(0, len(layers), chunk):
        x = cp.checkpoint(run(start), x, use_reentrant=False)
    return x
```

Activation checkpointing (gradient checkpointing) trades **compute for memory**: instead of storing every layer's activations for the backward pass, you store only the inputs at **segment boundaries** and **recompute** the intermediate activations during backward. The trade-off is quantifiable: storing activations at `√N` evenly spaced points across `N` layers cuts activation memory from `O(N)` to `O(√N)` at the cost of **one extra forward pass** (~33% more compute, since backward already costs ~2× a forward). This is what makes very deep / long-context models trainable on limited memory, and it composes with mixed precision, gradient accumulation, and FSDP. The detail to get right in current PyTorch is `use_reentrant=False` (the non-reentrant implementation handles RNG state for dropout and works with more features), and ensuring any RNG-dependent ops (dropout) are seeded consistently between the original and recomputed forward so the recomputation matches.

#### Q113. [Practical] A model passes offline eval but produces rare catastrophic outputs in production. How do you find and guard against the tail?

Aggregate metrics (accuracy, AUC) hide the **tail** — the rare, high-severity failures that dominate real-world risk. The senior approach treats this as a **safety/robustness** problem, not an accuracy problem:

```text
Find the tail (you can't fix what you can't see):
 - Slice-based eval: measure worst-performing cohorts, not just the average (e.g. via a
   slice-discovery / error-clustering tool). The mean can be great while a slice is terrible.
 - Adversarial / stress testing: hand-crafted hard cases, perturbations, OOD inputs,
   prompt-injection or boundary inputs for LLMs.
 - Mine production logs for low-confidence, high-entropy, and OOD inputs; review them.
Guard at serving time (defense in depth):
 - Confidence thresholding / abstention → route low-confidence cases to a fallback or human.
 - OOD detection to reject inputs far from the training distribution.
 - Output validators / guardrails (rule checks, a verifier model, constrained decoding).
 - Canary + shadow deployment with automatic rollback on tail-metric regression.
```

The framing that signals seniority: **optimize for the worst case, not just the mean** — in high-stakes settings the cost is asymmetric, so you measure tail and per-slice metrics, you **stress-test adversarially**, and you build **runtime guardrails** (abstention, OOD rejection, output validation) plus a **human-in-the-loop fallback** rather than trusting the model to be right on every input. You also close the loop: every production failure becomes a new test case and training example so the same catastrophe can't recur. Aggregate offline accuracy is necessary but never sufficient for deployment readiness.

#### Q114. [Coding] Implement knowledge distillation (logit + feature matching) to compress a model.

```python
import torch
import torch.nn.functional as F

def distillation_loss(student_logits, teacher_logits, labels,
                      T=4.0, alpha=0.5, student_feat=None, teacher_feat=None, beta=0.0):
    # 1) Soft targets: match the teacher's softened distribution (the "dark knowledge").
    soft = F.kl_div(
        F.log_softmax(student_logits / T, dim=-1),
        F.softmax(teacher_logits / T, dim=-1),
        reduction="batchmean",
    ) * (T * T)                                   # T² restores gradient scale
    # 2) Hard targets: the student should still fit the true labels.
    hard = F.cross_entropy(student_logits, labels)
    loss = alpha * soft + (1 - alpha) * hard
    # 3) Optional feature/hint matching on an intermediate layer (project dims if they differ).
    if student_feat is not None and beta > 0:
        loss = loss + beta * F.mse_loss(student_feat, teacher_feat)
    return loss
```

Distillation trains a small **student** to mimic a large **teacher**. The core idea is **soft targets**: the teacher's full softened probability distribution carries "dark knowledge" — the relative similarities between classes (a "3" is more like an "8" than a "cat") — which is far richer supervision than a one-hot label, so the student learns more per example. The **temperature `T`** softens both distributions to expose those relationships (and the `T²` factor rescales the gradient that the softening shrinks); `alpha` balances mimicking the teacher against fitting the ground-truth labels. **Feature/hint matching** (FitNets) adds an MSE term aligning intermediate representations for deeper supervision, usually via a learned projection when student and teacher widths differ. Distillation is how production-grade small models (DistilBERT, TinyLLaMA-style students) retain most of a large model's quality at a fraction of the inference cost — and it composes with quantization and pruning for further compression.

#### Q115. [Practical] You're asked to estimate the compute, memory, and cost to train a model before committing budget. How do you do it back-of-envelope?

A senior engineer can size a training run on paper before burning GPU-hours. The core relations:

```text
Training FLOPs  ≈ 6 · N · D        (N = params, D = training tokens; 6 = fwd+bwd factor)
Inference FLOPs ≈ 2 · N            per token (forward only)
Compute-optimal (Chinchilla)      ≈ 20 tokens per parameter for a fixed compute budget
Memory (full FT, Adam, mixed)     ≈ 16 bytes × N (weights+grads+Adam m,v) + activations
Wall-clock     = total_FLOPs / (num_GPUs · peak_FLOPS · MFU)   MFU ≈ 0.3–0.5 in practice
Cost           = GPU-hours × $/GPU-hour
```

Worked sketch: a 7B model trained Chinchilla-optimally on ~140B tokens needs ≈ `6 × 7e9 × 1.4e11 ≈ 5.9e21` FLOPs. On GPUs delivering, say, an effective `~3e14` FLOP/s each at ~40% MFU, that's `5.9e21 / 3e14 ≈ 2e7` GPU-seconds ≈ ~5,500 GPU-hours — then multiply by `$/GPU-hour` and divide by the number of GPUs for wall-clock. The judgment an expert layers on top: **MFU (model FLOPs utilization) of 30–50% is realistic**, not the hardware's peak — communication, memory bandwidth, and pipeline bubbles eat the rest; **memory** (not FLOPs) often sets the *minimum* GPU count because the model+optimizer+activations must fit; and you should **scope a small pilot run first** to measure your *actual* throughput and MFU, then extrapolate, rather than trusting the spec sheet. This converts an open-ended "can we afford this?" into a defensible number with stated assumptions.

#### Q116. [Coding] Implement a minimal but correct DDP training script and call out the must-get-right details.

```python
import os, torch, torch.nn as nn
import torch.distributed as dist
from torch.nn.parallel import DistributedDataParallel as DDP
from torch.utils.data import DataLoader, DistributedSampler

def main():
    dist.init_process_group("nccl")                       # torchrun sets the env vars
    rank = dist.get_rank()
    local_rank = int(os.environ["LOCAL_RANK"])
    torch.cuda.set_device(local_rank)

    model = build_model().to(local_rank)
    model = DDP(model, device_ids=[local_rank])           # wraps + hooks gradient all-reduce

    sampler = DistributedSampler(dataset, shuffle=True)   # each rank gets a DISJOINT shard
    loader = DataLoader(dataset, batch_size=64, sampler=sampler,
                        num_workers=4, pin_memory=True)
    opt = torch.optim.AdamW(model.parameters(), lr=3e-4)
    loss_fn = nn.CrossEntropyLoss()

    for epoch in range(epochs):
        sampler.set_epoch(epoch)                          # MUST: re-shuffles shards each epoch
        model.train()
        for xb, yb in loader:
            xb, yb = xb.to(local_rank), yb.to(local_rank)
            opt.zero_grad()
            loss = loss_fn(model(xb), yb)
            loss.backward()                               # DDP all-reduces grads here
            opt.step()
        if rank == 0:                                     # only ONE rank writes checkpoints
            torch.save(model.module.state_dict(), "ckpt.pt")   # .module unwraps DDP
    dist.destroy_process_group()

# launch:  torchrun --nproc_per_node=4 train.py
```

The details that separate a working DDP script from a subtly broken one: use a **`DistributedSampler`** (without it every rank trains on identical data — no convergence speedup) and call **`sampler.set_epoch(epoch)`** each epoch (otherwise the shuffle is identical every epoch); DDP **all-reduces gradients automatically** inside `backward()`, so the optimizer step is the *averaged* update — meaning you should **scale the learning rate for the larger effective batch** and add warmup; **only rank 0** writes checkpoints/logs (to avoid races and duplicate files) and you save **`model.module.state_dict()`** to strip the DDP wrapper; for models with **BatchNorm**, convert to `SyncBatchNorm` so statistics are computed across all GPUs rather than per-tiny-local-batch; and remember every rank must execute **every collective** — any rank-conditional forward/backward will deadlock the all-reduce. This minimal skeleton is the foundation; FSDP/ZeRO swap in when the model itself no longer fits on one GPU.

## ✅ Key Takeaways

- Non-linear activations are what give neural nets their power; without them depth collapses to a single linear map. ReLU/GELU for hidden layers, softmax/sigmoid for outputs.
- Backpropagation is just the chain rule applied backward over a cached forward pass — gradient cost ≈ one forward pass.
- Pair softmax with cross-entropy (clean `ŷ − y` gradient); use MSE for regression.
- The optimizer/schedule defaults that work: **AdamW** + warmup + cosine decay, with the learning rate as your highest-priority hyperparameter.
- Fight vanishing/exploding gradients with good init (He/Xavier), normalization (batch/layer norm), residual connections, and gradient clipping.
- Regularize with data augmentation, dropout, weight decay, and early stopping; detect overfitting via the train/val gap.
- Match architecture to data: CNNs for local/spatial structure, RNN/LSTM/GRU for shorter sequences, transformers (self-attention) for long-range dependencies and scale.
- Transfer learning + PEFT (LoRA/QLoRA) and mixed precision (BF16) are the standard, cost-efficient way to adapt large models in 2026.

## ⚠️ Common Pitfalls

- Forgetting `optimizer.zero_grad()` — gradients accumulate across batches and training diverges.
- Leaving dropout/batchnorm in training mode at inference (forgetting `model.eval()`), or leaking the test set into tuning.
- Using L2-in-the-loss with Adam instead of decoupled weight decay (AdamW) — the regularization is silently distorted.
- Computing `log(softmax(x))` manually instead of `log_softmax` — numerical instability and NaNs.
- Letting the learning rate be too high (loss → NaN) or too low (stalls); not using warmup with Adam/transformers.
- Data augmentation that changes the label, or applying train-time augmentation at inference.
- Normalizing/standardizing using statistics computed over the full dataset before the train/test split (data leakage).
- Assuming larger batch size is strictly better — it can widen the generalization gap and needs lr scaling + warmup.

## 📚 Further Reading

- Goodfellow, Bengio & Courville — *Deep Learning* (the foundational textbook).
- Vaswani et al., *Attention Is All You Need* (2017) — the transformer.
- He et al., *Deep Residual Learning* (2015) — residual connections; Ioffe & Szegedy, *Batch Normalization* (2015).
- Kingma & Ba, *Adam* (2014); Loshchilov & Hutter, *Decoupled Weight Decay Regularization* (AdamW, 2019).
- Hu et al., *LoRA* (2021) and Dettmers et al., *QLoRA* (2023) — parameter-efficient fine-tuning.
- Hoffmann et al., *Training Compute-Optimal LLMs* (Chinchilla, 2022) — scaling laws.
- The PyTorch documentation and tutorials (pytorch.org) for hands-on `torch.amp`, `torch.compile`, and FSDP.
