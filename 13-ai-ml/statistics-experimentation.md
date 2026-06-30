# Statistics & Experimentation

[← Back to master index](../README.md)

An interview-grade reference for the statistics and online experimentation every engineer working on data-driven products is expected to know — descriptive statistics, the core distributions, the central limit theorem, sampling, confidence intervals, hypothesis testing, and the full A/B-testing toolkit (sample-size and MDE planning, randomization units, guardrail metrics, novelty effects, the peeking problem, sequential testing, CUPED, multiple-comparisons correction). Every answer explains the *why* and the engineering trade-offs, with Python snippets for the practical and coding questions. Current through 2026.

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

### Q1. [Theory] What is the difference between the mean, median, and mode, and when do you prefer each?

All three are **measures of central tendency** — single numbers that summarize "where the data sits" — but they answer slightly different questions.

- **Mean** (arithmetic average) = sum of values / count. Uses every data point, so it is the right summary for symmetric data and feeds directly into variance, the CLT, and most parametric tests. Its weakness: it is **sensitive to outliers** and skew.
- **Median** = the middle value when sorted (the 50th percentile). It is **robust to outliers** and is the honest summary for skewed distributions like income, latency, or house prices.
- **Mode** = the most frequent value. The only one that works for **categorical** data ("most common browser") and useful for spotting multimodality.

```text
Symmetric data:   mean ≈ median ≈ mode
Right-skewed:      mode < median < mean   (mean pulled toward the long right tail)
```

Rule of thumb: report the **median** for anything money- or latency-related (right-skewed), the **mean** for symmetric, well-behaved data, and always look at both — a large gap between them is itself a signal of skew.

### Q2. [Theory] Explain variance and standard deviation. Why divide by n−1?

**Variance** measures spread: the average squared distance of each point from the mean. **Standard deviation (SD)** is its square root, which puts the spread back into the original units (dollars, ms), making it interpretable.

```text
Population variance:  σ² = (1/N)   Σ (xᵢ − μ)²
Sample variance:      s² = (1/(n−1)) Σ (xᵢ − x̄)²
```

We divide the **sample** variance by **n−1** (Bessel's correction) rather than n because we estimate the mean `x̄` from the same data. The points are, on average, closer to *their own* sample mean than to the true population mean, so dividing by n would **systematically underestimate** the true variance (a biased estimator). Dividing by n−1 — the **degrees of freedom** — corrects that bias. Intuitively, once you fix the mean of n numbers, only n−1 of them are free to vary.

### Q3. [Theory] What is the difference between a population and a sample?

A **population** is the entire set of entities you care about (all users who will ever visit the site). A **sample** is a subset you actually observe and measure. We almost never have the population, so we **infer** population quantities (called **parameters**, written with Greek letters: μ, σ, p) from sample quantities (called **statistics**: x̄, s, p̂).

```text
Population  ──draw sample──►  Sample  ──compute──►  Statistic (x̄)
   μ (unknown)                                       estimates μ
```

The whole field of inferential statistics is about quantifying how much a sample statistic can be trusted as an estimate of the population parameter — that uncertainty is what standard errors, confidence intervals, and p-values capture.

### Q4. [Theory] What is a normal (Gaussian) distribution and why does it appear everywhere?

The **normal distribution** is the symmetric, bell-shaped continuous distribution defined entirely by its mean μ (center) and standard deviation σ (spread). Its key properties:

- Symmetric about the mean; mean = median = mode.
- The **68–95–99.7 rule**: ~68% of mass lies within ±1σ, ~95% within ±2σ (more precisely ±1.96σ), ~99.7% within ±3σ.

```text
        ┌─ 68% ─┐
    ┌──── 95% ────┐
 ┌─────── 99.7% ───────┐
 │     │     │     │    │
-3σ   -1σ    μ    +1σ  +3σ
```

It appears everywhere for two reasons: (1) many natural quantities are sums of many small independent effects, and the **central limit theorem** drives such sums toward normality; (2) it is mathematically convenient — closed under linear combinations and the basis of most parametric tests. **Caution:** lots of real data (latency, income, counts) is *not* normal, so check before assuming it.

### Q5. [Theory] What is a binomial distribution? Give a concrete product example.

The **binomial** distribution models the number of **successes in n independent yes/no trials**, each with the same success probability p. It is the natural model for conversion-style metrics.

- Parameters: n (trials), p (per-trial success probability).
- Mean = np; variance = np(1−p).
- Example: you show a checkout button to **n = 1,000** users and each converts independently with probability **p = 0.04**. The number of conversions follows Binomial(1000, 0.04), with expected value 40 and SD ≈ √(1000·0.04·0.96) ≈ 6.2.

A single Bernoulli trial is just Binomial(1, p). When n is large and p isn't too extreme, the binomial is well-approximated by a normal distribution — which is exactly why we can run z-tests on conversion rates.

### Q6. [Theory] What is a Poisson distribution and when do you use it?

The **Poisson** distribution models the **count of independent events in a fixed interval** of time or space, given a constant average rate λ. Examples: number of requests hitting a server per second, support tickets per day, defects per wafer.

- Single parameter λ = mean = variance (a useful diagnostic: if your count data's variance ≫ mean, it's **overdispersed** and Poisson is the wrong model — consider negative binomial).
- It is the limit of a binomial with large n and small p where np → λ — i.e. many trials, each rarely succeeding.

```text
Binomial(n, p)  ──as n→∞, p→0, np=λ──►  Poisson(λ)
```

### Q7. [Theory] Explain the Central Limit Theorem (CLT) in plain terms.

The CLT says: if you take **many independent samples** of size n from *any* distribution with a finite mean μ and variance σ², the distribution of the **sample means** approaches a **normal** distribution as n grows — regardless of the shape of the original data.

```text
Population (any shape, even skewed/bimodal)
        │ repeatedly draw samples of size n, take each mean
        ▼
Distribution of sample means  →  Normal(μ, σ/√n)
```

Two consequences make it the workhorse of inference:
1. The sample mean is centered on the true mean μ.
2. Its spread, the **standard error**, is **σ/√n** — shrinking as √n, so quadrupling the sample size halves the error.

This is *why* we can build confidence intervals and run t-/z-tests on means even when the raw data is non-normal — provided n is reasonably large (often n ≥ 30 as a rough rule, larger for very skewed data).

### Q8. [Coding] Demonstrate the CLT with a simulation.

Draw repeated samples from a heavily skewed (exponential) population and watch the sample means become normal.

```python
import numpy as np

rng = np.random.default_rng(42)
pop_mean = 1.0                      # exponential(scale=1) has mean 1, and is very skewed

def sample_means(n, num_samples=10_000):
    # each row is one sample of size n; take the mean of each row
    data = rng.exponential(scale=1.0, size=(num_samples, n))
    return data.mean(axis=1)

for n in [1, 5, 30, 100]:
    means = sample_means(n)
    se_theory = 1.0 / np.sqrt(n)   # σ/√n, and σ = 1 for exp(1)
    print(f"n={n:3d}  mean≈{means.mean():.3f}  "
          f"SE_observed≈{means.std():.3f}  SE_theory≈{se_theory:.3f}")
```

```text
n=  1  mean≈0.999  SE_observed≈1.001  SE_theory≈1.000   (still skewed)
n=  5  mean≈1.000  SE_observed≈0.448  SE_theory≈0.447
n= 30  mean≈1.000  SE_observed≈0.183  SE_theory≈0.183   (looks normal)
n=100  mean≈1.000  SE_observed≈0.100  SE_theory≈0.100
```

Even though the population is exponential, by n = 30 the sample-mean distribution is bell-shaped and its spread matches σ/√n.

### Q9. [Theory] What is a standard error, and how does it differ from standard deviation?

- **Standard deviation (SD)** describes the spread of the **raw data** — how much individual observations vary.
- **Standard error (SE)** describes the spread of a **statistic** (usually the mean) across hypothetical repeated samples — how much your *estimate* would wobble if you re-ran the study.

For the sample mean, **SE = SD / √n**. The key takeaway: SE shrinks as you collect more data, but SD does not — more data makes your *estimate of the mean* more precise without making the underlying data any less variable. Confusing the two is one of the most common statistics errors in interviews.

### Q10. [Theory] What is a confidence interval, and what does "95% confident" actually mean?

A **confidence interval (CI)** is a range, computed from the sample, that is designed to contain the true parameter with a stated long-run frequency. A 95% CI for a mean is roughly:

```text
x̄ ± 1.96 × SE      (1.96 is the z-value capturing the central 95% of a normal)
```

The precise (frequentist) meaning is subtle: **"95% confident" refers to the procedure, not a single interval.** If you repeated the experiment many times and built a CI each time, about 95% of those intervals would contain the true parameter. It does **not** mean "there is a 95% probability the true value is in *this* interval" — in the frequentist view the true value is fixed and either is or isn't inside; the randomness is in the interval, not the parameter. (The probability-statement interpretation belongs to Bayesian *credible* intervals.)

### Q11. [Coding] Compute a 95% confidence interval for a conversion rate.

For a proportion (conversion rate), use the standard error of a proportion, `√(p̂(1−p̂)/n)`.

```python
import numpy as np
from scipy import stats

conversions, n = 80, 2000
p_hat = conversions / n                       # 0.04
se = np.sqrt(p_hat * (1 - p_hat) / n)         # SE of a proportion
z = stats.norm.ppf(0.975)                      # 1.96 for 95%
lo, hi = p_hat - z * se, p_hat + z * se
print(f"p̂ = {p_hat:.3%}   95% CI = [{lo:.3%}, {hi:.3%}]")
# p̂ = 4.000%   95% CI = [3.141%, 4.859%]
```

The interval width is driven by `√(1/n)`, so to halve it you need **4×** the sample. For very small counts the normal approximation is poor — prefer the **Wilson** or **Agresti–Coull** interval instead of this "Wald" interval.

### Q12. [Theory] Define the null and alternative hypotheses with an example.

In hypothesis testing you state two competing claims:

- **Null hypothesis (H₀):** the "no effect / status quo" claim. For an A/B test: *"the treatment conversion rate equals the control rate"* (the difference is 0).
- **Alternative hypothesis (H₁ or Hₐ):** the claim you're trying to find evidence for. *"the treatment rate differs from (or is greater than) the control rate."*

A test never *proves* H₁; it only asks whether the data is surprising enough **under H₀** to justify rejecting H₀. This is deliberately conservative — like a courtroom presuming innocence (H₀) until the evidence is strong enough to convict. A **two-sided** alternative tests for any difference; a **one-sided** alternative tests for a difference in a specific direction (commit to the direction *before* seeing data).

### Q13. [Theory] What is a p-value? State the precise definition and the most common misinterpretation.

A **p-value** is the probability of observing a result **at least as extreme** as the one you got, **assuming the null hypothesis is true**.

```text
p = P( data this extreme or more  |  H₀ true )
```

A small p-value means your data would be unlikely *if there were no effect*, which is evidence against H₀. If p < α (commonly 0.05), you "reject the null."

The crucial misinterpretation to avoid: **the p-value is NOT the probability that the null hypothesis is true**, nor the probability your result happened "by chance." It is computed *assuming* H₀, so it cannot also tell you the probability that H₀ holds. It also says nothing about effect *size* — with a huge sample, a trivially small, business-irrelevant difference can produce a tiny p-value.

### Q14. [Theory] Explain Type I and Type II errors and how they relate to α and β.

The two ways a test can be wrong:

```text
                    H₀ true              H₀ false (real effect)
Reject H₀     Type I error (FP)          Correct  ✓  (power)
              prob = α                    prob = 1−β
Fail to       Correct  ✓                  Type II error (FN)
reject H₀     prob = 1−α                  prob = β
```

- **Type I error (false positive):** you declare an effect that isn't real. Its probability is **α** — the significance level you choose (e.g. 0.05).
- **Type II error (false negative):** you miss a real effect. Its probability is **β**.
- **Power = 1 − β** is the probability of detecting a real effect when it exists.

There is a tension: lowering α (being stricter) raises β unless you compensate with a larger sample. In a shipping decision, a Type I error means launching a feature that doesn't actually help (or hurts); a Type II error means killing a feature that would have helped.

### Q15. [Theory] What is statistical power, and what four levers control it?

**Power** is the probability that a test correctly rejects a false null — i.e. detects a real effect of a given size. The industry default target is **80%** (β = 0.20). Four interrelated levers determine it:

```text
Power ↑ when:
  • Sample size  n        ↑   (more data, less noise)
  • Effect size  (MDE)    ↑   (bigger true effects are easier to see)
  • Significance α         ↑   (looser bar, but more Type I risk)
  • Variance     σ²        ↓   (less noisy metric; CUPED helps here)
```

Fix any three and the fourth is determined, which is exactly how sample-size calculators work. Underpowered tests are the silent killer of experimentation programs: they produce inconclusive results *and* inflate the exaggeration of any "significant" effects that do squeak through (the winner's curse / Type M error).

### Q16. [Practical] What is an A/B test and what are the essential steps to run one correctly?

An **A/B test** is a randomized controlled experiment: users are randomly split into a **control** group (current experience, A) and one or more **treatment** groups (the change, B). Because assignment is random, the groups are statistically equivalent on average, so any difference in the metric can be **causally** attributed to the change.

```python
# Essential checklist for a sound A/B test:
steps = [
    "1. Pick ONE primary metric (the OEC) + guardrail metrics up front",
    "2. State H0/H1 and choose alpha (0.05) and power (0.80)",
    "3. Compute required sample size from the MDE BEFORE launching",
    "4. Choose the randomization unit (usually user, not request)",
    "5. Randomize; verify a balanced split (sample-ratio mismatch check)",
    "6. Run for the FULL pre-committed duration (>= 1-2 weeks, full cycles)",
    "7. Do NOT peek-and-stop; analyze once at the end (or use sequential)",
    "8. Check guardrails; interpret effect size + CI, not just p-value",
]
```

The discipline is everything: deciding the metric, sample size, and duration *before* you look at data is what protects you from fooling yourself.

### Q17. [Theory] What is the difference between correlation and causation? Give a real example.

**Correlation** means two variables move together; **causation** means changing one *produces* a change in the other. Correlation does not imply causation because of two classic traps:

1. **Confounding:** a third variable drives both. Ice-cream sales correlate with drownings — but the cause of both is hot weather (the confounder), not ice cream.
2. **Reverse causation:** X correlates with Y because Y actually causes X.

```text
Confounding:   Weather ──► Ice cream sales
                  └──────► Drownings        (spurious correlation between the two)
```

The **only** reliable way to establish causation is to **intervene** — randomly assign the treatment (a randomized controlled experiment / A/B test) so that confounders are balanced across groups on average. This is precisely why A/B tests are the gold standard for product decisions.

---

## 🟡 Intermediate (3–7 yrs)

### Q18. [Theory] When do you use a t-test vs a z-test, and what are the assumptions?

Both compare means, but differ in what you know about the variance:

- **z-test:** use when the population variance σ² is **known**, or n is large enough that the sample variance is essentially as good (the normal and t distributions converge for large n). Common for proportion/conversion tests at scale.
- **t-test:** use when σ² is **unknown** and estimated from the sample, especially for **small samples**. The t-distribution has heavier tails than the normal to account for the extra uncertainty from estimating σ, controlled by the **degrees of freedom**.

Assumptions for the classic two-sample t-test: observations are **independent**, the metric is roughly **normal** (or n is large enough for the CLT to rescue you), and — for the pooled version — **equal variances**. In practice prefer **Welch's t-test**, which does *not* assume equal variances and is the safe default. For very heavy-tailed or ordinal data, consider a nonparametric alternative (Mann–Whitney U).

### Q19. [Coding] Run a two-sample t-test comparing two groups in Python.

```python
import numpy as np
from scipy import stats

rng = np.random.default_rng(0)
control   = rng.normal(loc=100, scale=15, size=500)   # mean session time, control
treatment = rng.normal(loc=103, scale=15, size=500)   # +3s in treatment

# Welch's t-test: does NOT assume equal variances (the safe default)
t_stat, p_value = stats.ttest_ind(treatment, control, equal_var=False)

diff = treatment.mean() - control.mean()
se = np.sqrt(treatment.var(ddof=1)/len(treatment) + control.var(ddof=1)/len(control))
ci = (diff - 1.96*se, diff + 1.96*se)

print(f"diff = {diff:.2f}s   t = {t_stat:.2f}   p = {p_value:.4f}")
print(f"95% CI for the difference: [{ci[0]:.2f}, {ci[1]:.2f}]")
```

Always report the **effect size and its CI**, not just the p-value — "p = 0.03" tells you *something* happened, the CI tells you *how much* and whether it's worth shipping.

### Q20. [Theory] What is a chi-square test and when do you use it?

The **chi-square (χ²) test** works on **categorical** data by comparing **observed** counts against the counts you'd **expect** under the null. Two common forms:

- **Test of independence:** is there an association between two categorical variables (e.g. experiment variant × converted/not)? This is the natural test for an A/B test reported as a contingency table.
- **Goodness of fit:** does an observed frequency distribution match an expected one (e.g. is the traffic split actually 50/50 — a **sample-ratio-mismatch** check)?

```text
χ² = Σ (Observed − Expected)² / Expected
```

Assumptions: independent observations and reasonably large expected counts in each cell (a common rule: expected ≥ 5). For 2×2 tables with small counts, use **Fisher's exact test** instead.

### Q21. [Coding] Use a chi-square test to detect sample-ratio mismatch (SRM).

If you intended a 50/50 split but the observed counts are lopsided, your randomization or logging is broken and the experiment is untrustworthy — even before looking at the metric.

```python
from scipy import stats

observed = [50_120, 49_300]          # users bucketed into A and B
total = sum(observed)
expected = [total * 0.5, total * 0.5]

chi2, p = stats.chisquare(f_obs=observed, f_exp=expected)
print(f"chi2 = {chi2:.2f}   p = {p:.4f}")
if p < 0.001:                         # SRM uses a strict threshold
    print("SRM DETECTED — do not trust this experiment; debug assignment/logging.")
```

SRM is checked at a **very strict** p-threshold (e.g. 0.0005) because even a tiny imbalance signals a systematic bug (bot filtering applied unevenly, redirect latency, broken hashing) that can bias the whole result.

### Q22. [Theory] What is ANOVA and why not just run many t-tests?

**ANOVA (Analysis of Variance)** tests whether **three or more** group means are all equal, by comparing the variance *between* groups to the variance *within* groups (the F-statistic). H₀: all group means are equal; a large F (small p) says at least one group differs.

You don't just run all pairwise t-tests because each test carries its own Type I risk, and running many **inflates the family-wise error rate**: with k groups you'd run k(k−1)/2 comparisons, and the chance of *some* false positive balloons. ANOVA gives one **omnibus** test at a controlled α. If it's significant, you then run **post-hoc** pairwise comparisons with a correction (e.g. Tukey's HSD) to find *which* groups differ. This is the same multiple-comparisons concern that arises with multi-arm experiments.

### Q23. [Theory] What is the Minimum Detectable Effect (MDE), and how does it drive sample size?

The **MDE** is the **smallest true effect you care about detecting** — the smallest lift that would change your decision. It is a *product* choice, not a statistical one: "a 1% relative lift in conversion is worth shipping."

It drives sample size through an inverse-square relationship:

```text
n  ∝  σ² / MDE²       (per group, roughly)
```

So **halving** the MDE you want to detect requires roughly **4×** the sample. This is why teams must commit to an MDE up front: choose it too small and the test needs impractically huge traffic; too large and you'll miss real, shippable wins. The MDE, baseline metric, variance, α, and power together pin down n.

### Q24. [Coding] Compute the required sample size for an A/B test on conversion rate.

```python
import numpy as np
from scipy import stats

def sample_size_per_group(p_baseline, mde_relative, alpha=0.05, power=0.80):
    """Required n per group for a two-sided test on a proportion."""
    p1 = p_baseline
    p2 = p_baseline * (1 + mde_relative)          # treatment rate at the MDE
    z_alpha = stats.norm.ppf(1 - alpha/2)         # 1.96
    z_beta  = stats.norm.ppf(power)               # 0.84 for 80% power
    p_bar = (p1 + p2) / 2
    # standard two-proportion sample-size formula
    n = ((z_alpha * np.sqrt(2*p_bar*(1-p_bar)) +
          z_beta  * np.sqrt(p1*(1-p1) + p2*(1-p2)))**2) / (p2 - p1)**2
    return int(np.ceil(n))

n = sample_size_per_group(p_baseline=0.10, mde_relative=0.05)   # detect a 5% rel. lift on a 10% base
print(f"Need ~{n:,} users per group (~{2*n:,} total)")
# Need ~31,234 users per group (~62,468 total)
```

Plug in your daily eligible traffic to convert that into experiment **duration**, then round *up* to whole business cycles (weeks) to avoid day-of-week bias.

### Q25. [Practical] What is the randomization unit, and why does it usually have to be the user?

The **randomization unit** is the entity you randomly assign to control or treatment — request, session, user, or even cluster (account/region). It must be chosen so that observations are **independent** and there is **no leakage** between arms.

- **Request/session-level** randomization gives the most units (tighter CIs) but breaks down if the change affects experience *across* requests — a user could see the new UI then the old one, causing an **inconsistent experience** and contaminating the comparison.
- **User-level** randomization (hash the stable user ID into a bucket) is the standard choice: it guarantees a consistent experience and respects that a user's repeated events are **correlated**, not independent.
- **Cluster-level** randomization is required for **network effects** (social features, marketplaces, two-sided platforms) where one user's treatment spills over to others — randomize whole communities/markets and analyze at that level.

Mismatching the **randomization unit** and the **analysis unit** is a classic bug: if you randomize by user but compute variance as if each *event* were independent, you understate variance and get false positives. Use clustered/delta-method standard errors.

### Q26. [Theory] What is a guardrail metric, and why does every experiment need one?

A **guardrail** (or counter) metric is a metric you are **not trying to improve** but that you refuse to harm — it guards against unintended damage while you chase the primary metric.

Typical guardrails: **page latency / load time**, **crash/error rate**, **revenue per user**, **unsubscribe/opt-out rate**, **support-ticket volume**. The pattern they catch: a change that lifts the primary metric while quietly degrading the product — e.g. an aggressive popup that boosts signups (primary ↑) but tanks long-term retention and spikes complaints (guardrails ↓).

In practice you require the primary metric to move **and** all guardrails to stay within a tolerance band before shipping. Guardrails are usually evaluated as **non-inferiority** checks ("did not get worse by more than X"), not as targets to beat.

### Q27. [Theory] What are novelty and primacy effects, and how do you handle them?

Both are **time-varying treatment effects** that make the *early* days of an experiment unrepresentative of the long-run effect:

- **Novelty effect:** users engage with something *because it's new and shiny*. The treatment looks great at first, then the effect decays as the novelty wears off. Risk: shipping a "winner" whose lift evaporates.
- **Primacy effect (change aversion):** existing users are initially confused or annoyed by the change and underperform, then adapt and recover (or exceed). Risk: killing a good feature that just needed a learning period.

```text
Novelty:  effect starts HIGH, decays ──►  ~~~╲___
Primacy:  effect starts LOW,  recovers ──►  ___╱~~~
```

Mitigations: **run longer** to let the effect stabilize; **segment by new vs. returning users** (novelty hits returning users hardest); plot the **treatment effect over time** and look for a trend rather than reading one final number; or analyze only users who joined *after* launch (no prior experience to be primed by).

### Q28. [Theory] What is the "peeking problem" in A/B testing?

The **peeking problem** is repeatedly checking a running experiment and stopping as soon as it shows significance. Each look is another chance to cross the p < 0.05 line **by chance**, so continuously monitoring a fixed-horizon test and stopping at the first "win" can inflate the false-positive rate to **30–50%** instead of 5%.

```text
True FPR if you peek daily for ~20 days at α=0.05  ≈  high (way above 5%)
because the random p-value walk crosses 0.05 at *some* point with high probability.
```

The fix for a **classic** fixed-horizon test: pre-commit to a sample size/duration and analyze **once** at the end. If you genuinely need to monitor continuously and stop early, you must switch to a method designed for it — **sequential testing** or **always-valid p-values / group-sequential boundaries** (next questions). Peeking isn't wrong; peeking *with a test that assumes you didn't* is.

### Q29. [Theory] What is multiple-comparisons correction, and when is it needed?

When you run **many** hypothesis tests, the probability that **at least one** crosses α purely by chance grows fast. With m independent tests at α = 0.05, the family-wise false-positive probability is `1 − (1−α)^m` — about **40% at m = 10**, **64% at m = 20**.

You need correction whenever a single decision rests on many tests: multiple metrics, multiple variants, multiple segments/subgroups, or repeated looks. Two families of fixes:

- **Bonferroni:** test each at α/m. Controls the **family-wise error rate (FWER)** — the chance of *any* false positive. Simple but **conservative** (kills power) when m is large.
- **Benjamini–Hochberg (FDR):** controls the **false discovery rate** — the expected *proportion* of false positives among your "discoveries." Far more powerful, the right choice for exploratory/many-metric settings.

Rule of thumb: designate **one primary metric** to avoid the problem on the decision metric, and apply BH/FDR to the secondary/exploratory metrics.

### Q30. [Coding] Apply Bonferroni and Benjamini–Hochberg corrections to a set of p-values.

```python
import numpy as np
from statsmodels.stats.multitest import multipletests

p_values = [0.001, 0.012, 0.030, 0.04, 0.20, 0.51]

# Bonferroni: controls FWER (chance of ANY false positive) — conservative
rej_bonf, p_bonf, _, _ = multipletests(p_values, alpha=0.05, method="bonferroni")

# Benjamini-Hochberg: controls FDR (proportion of false discoveries) — more power
rej_bh, p_bh, _, _ = multipletests(p_values, alpha=0.05, method="fdr_bh")

for p, b, h in zip(p_values, rej_bonf, rej_bh):
    print(f"p={p:<6}  Bonferroni reject={str(b):<5}  BH reject={h}")
```

```text
p=0.001   Bonferroni reject=True   BH reject=True
p=0.012   Bonferroni reject=False  BH reject=True
p=0.03    Bonferroni reject=False  BH reject=True
...
```

Notice BH rejects more hypotheses than Bonferroni at the same α — that extra power is the reason FDR is preferred for large metric/segment sweeps.

### Q31. [Theory] What is a confounder, and how do randomization and controlling differ in handling it?

A **confounder** is a variable that influences **both** the treatment assignment and the outcome, creating a spurious (or distorted) association between them.

```text
        Confounder (e.g. user tenure)
         ╱                    ╲
   Treatment  ───?───►   Outcome
```

Two ways to defuse it:
- **Randomization (experiments):** random assignment makes the confounder **balanced on average** across arms — including confounders you never measured or thought of. This is the unique strength of A/B tests and why they're causal.
- **Controlling/adjusting (observational data):** you can't randomize, so you statistically adjust — stratification, regression with the confounder as a covariate, matching, propensity scores. The fatal limitation: you can only adjust for confounders you **measured and identified**; **unmeasured** confounders still bias your estimate. That's the core reason observational causal claims are weaker than experimental ones.

### Q32. [Theory] Explain Simpson's paradox with a concrete example.

**Simpson's paradox** is when a trend that appears in aggregated data **reverses** when the data is split by a confounding subgroup.

Classic example — comparing two treatments by recovery rate:

```text
                 Treatment A        Treatment B
Small stones     81% (81/87)        87% (234/270)    ← B wins
Large stones     73% (192/263)      69% (55/80)      ← B wins
─────────────────────────────────────────────────
Overall          78% (273/350)      83% (289/350)    ← A wins ?!
```

A is better within *both* stone-size groups, yet B looks better overall. The cause: stone size is a **confounder** — A was disproportionately given to the hard (large-stone) cases. Aggregating mixes the groups in unequal proportions and flips the conclusion. The lesson: always ask whether an aggregate comparison is hiding an unbalanced confounder, and **randomization** is what prevents this from biasing an experiment in the first place.

### Q33. [Practical] How long should you run an A/B test, and when can you stop?

Duration is driven by three things, and you take the **maximum** of them:

1. **Statistical:** enough users to reach your required sample size at the target MDE/power (from the sample-size calculation).
2. **Temporal cycles:** at least **one to two full weeks** so every day-of-week is represented — weekday and weekend behavior differ sharply; stopping mid-cycle bakes in day-of-week bias. Cover monthly cycles (paydays, billing) if relevant.
3. **Effect stabilization:** long enough for **novelty/primacy** effects to settle.

You can stop when the **pre-committed** duration *and* sample size are both met — not when significance first appears (that's peeking). Concretely: never run shorter than a week even if "significant" on day 2; never run so long that seasonality or external shocks (holidays, launches) contaminate the comparison.

### Q34. [Practical] Your A/B test shows p = 0.045 with a 0.2% relative lift on 5 million users. Do you ship? 

Be skeptical — statistical significance is not practical significance. Walk through it:

1. **Effect size vs. cost.** A 0.2% relative lift is tiny. With 5M users, even a trivial difference becomes "significant" because SE shrinks with √n. Compare the lift against the **engineering/maintenance cost** and the **MDE you pre-registered** — was 0.2% even worth detecting?
2. **Is it above your MDE?** If your decision threshold was a 1% lift, a 0.2% result is a *practical null* regardless of the p-value.
3. **Look at the CI, not the point.** p = 0.045 means the 95% CI for the lift just barely excludes zero — its lower bound is near 0, so the *plausible* effect could be negligible.
4. **Multiple comparisons / peeking.** Was this the only metric and the only look? If you tested many metrics or peeked, p = 0.045 is even weaker.
5. **Guardrails.** Did latency, revenue, or errors move?

Usual answer: **don't ship on this alone.** Either it's below the MDE (skip it), or replicate/extend to tighten the CI before committing real maintenance cost.

---

## 🟠 Advanced (8–12 yrs)

### Q35. [Theory] Contrast the Bayesian and frequentist approaches to A/B testing.

They answer different questions:

- **Frequentist:** parameters are fixed-but-unknown; probability is long-run frequency. You compute a **p-value** (P(data | H₀)) and a **confidence interval**, and you must fix the sample size in advance. The output is "reject / fail to reject H₀."
- **Bayesian:** parameters are random variables with a **prior**; you update to a **posterior** via Bayes' rule. The output is directly decision-relevant: **P(treatment > control | data)** and the **expected loss** of each choice, plus a **credible interval** that genuinely means "95% probability the parameter is in here."

```text
Frequentist:  P(data | hypothesis)   →  p-value, CI
Bayesian:     P(hypothesis | data)    →  posterior, credible interval, P(B>A)
```

Practical trade-offs: Bayesian results are easier for stakeholders to interpret ("82% chance B is better"), naturally support **continuous monitoring** without the same peeking penalty (the posterior is always valid given the prior), and incorporate prior knowledge — but they require choosing a prior, and a strong/biased prior can distort conclusions. Frequentist is the regulatory/industry default and needs no prior. Most mature platforms (e.g. internal tools at large tech companies) offer both; the choice is about decision framework and interpretability, not "which is correct."

### Q36. [Theory] What is CUPED and how does it increase experiment power?

**CUPED** (Controlled-experiment Using Pre-Experiment Data, Microsoft 2013) is a **variance-reduction** technique. Power depends on metric variance (`n ∝ σ²/MDE²`), so cutting variance lets you detect the same effect with **less traffic / shorter runtime** — often a **30–50% variance reduction**.

The idea: a user's behavior *before* the experiment (a covariate X, e.g. their pre-period spend) is highly correlated with their behavior *during* it, but is **unaffected by the treatment** (it happened before assignment). So you subtract off the predictable part:

```text
Y_cuped = Y − θ (X − E[X]),   where θ = Cov(Y, X) / Var(X)
```

Because X is pre-treatment, this adjustment is **unbiased** for the treatment effect — it removes noise, not signal. The variance shrinks by a factor of `(1 − ρ²)` where ρ is the correlation between Y and the pre-period covariate. The stronger the pre-period predictor, the bigger the win. CUPED is essentially regression adjustment / a special case of using covariates (CUPAC generalizes it to ML-predicted covariates).

### Q37. [Coding] Implement CUPED variance reduction.

```python
import numpy as np

rng = np.random.default_rng(7)
n = 20_000
# Pre-period metric X, correlated with in-experiment metric Y
x_pre = rng.normal(50, 10, size=2*n)
noise = rng.normal(0, 8, size=2*n)
y = 0.8 * x_pre + noise                 # Y correlated with X (rho ~ 0.7)

group = np.r_[np.zeros(n), np.ones(n)]  # 0 = control, 1 = treatment
y[group == 1] += 1.5                    # inject a +1.5 treatment effect

# CUPED adjustment using the pre-period covariate
theta = np.cov(y, x_pre)[0, 1] / np.var(x_pre)
y_cuped = y - theta * (x_pre - x_pre.mean())

def effect_and_se(metric):
    t, c = metric[group == 1], metric[group == 0]
    diff = t.mean() - c.mean()
    se = np.sqrt(t.var(ddof=1)/len(t) + c.var(ddof=1)/len(c))
    return diff, se

print("Raw  :", "diff=%.3f se=%.4f" % effect_and_se(y))
print("CUPED:", "diff=%.3f se=%.4f" % effect_and_se(y_cuped))
# CUPED keeps the ~1.5 effect estimate but the SE drops substantially -> more power
```

The point estimate of the effect is unchanged (CUPED is unbiased), but the **standard error shrinks**, which is exactly equivalent to having collected more data.

### Q38. [Theory] What is sequential testing, and how does it solve the peeking problem?

**Sequential testing** is a family of methods that let you **monitor an experiment continuously and stop as soon as there's enough evidence**, while *keeping the false-positive rate controlled* — directly solving the peeking problem.

Instead of one decision at a fixed n, you evaluate after each batch using a boundary that's been adjusted for repeated looks:

- **Group-sequential (O'Brien–Fleming / Pocock):** pre-plan k interim analyses and spend your α budget across them via an **alpha-spending function**; early looks use a *very* strict threshold so the cumulative Type I rate still totals α.
- **Always-valid inference / mSPRT:** **always-valid p-values** and **confidence sequences** that are valid at *every* moment, so you can peek as often as you like. This is what powers "stop anytime" dashboards at modern experimentation platforms (Optimizely, Statsig, etc.).

```text
Fixed-horizon:   look once at n ──► p < α?               (peeking breaks it)
Sequential:      look continuously, stricter boundary ──► valid stop anytime
```

The trade-off: sequential methods are slightly **less powerful** at the planned horizon (you "pay" for the option to stop early), but in exchange you get the freedom to stop early on big effects or cut losses on clear losers — usually a big operational win.

### Q39. [Practical] How would you design an A/B testing platform's statistics engine end to end?

Lay out the pipeline and the statistical decisions at each stage:

```text
Assignment ─► Logging ─► Metric computation ─► Inference ─► Decision
```

1. **Assignment:** deterministic hashing of (user_id, experiment_id, salt) → bucket, so it's stable, reproducible, and independent across experiments. Support mutually-exclusive **layers** so overlapping tests don't interfere.
2. **Data quality gates:** automatic **SRM check** (chi-square) on every experiment; flag and quarantine experiments that fail before anyone reads results.
3. **Metric framework:** declarative metric definitions; classify each as **primary / secondary / guardrail**; handle **ratio metrics** (e.g. CTR = clicks/impressions) with the **delta method** for correct variance.
4. **Variance reduction:** apply **CUPED** by default using pre-period covariates.
5. **Inference:** **clustered standard errors** to match randomization unit to analysis unit; **sequential/always-valid p-values** so PMs can monitor without peeking penalties; **FDR correction** across the secondary-metric panel.
6. **Decision layer:** require primary ↑, guardrails within tolerance; surface **effect size + CI**, heterogeneity by segment, and a power/SRM health summary — not a lone p-value.

The recurring theme: every statistical pitfall in this doc (peeking, SRM, multiple comparisons, randomization/analysis-unit mismatch, novelty) is encoded as an automated guardrail so individual analysts can't accidentally fool themselves.

### Q40. [Theory] What is the delta method and why is it needed for ratio metrics?

Many product metrics are **ratios** where the denominator is itself random and the unit of analysis differs from the randomization unit — e.g. **click-through rate = total clicks / total impressions**, randomized by *user* but aggregated over *events*. You can't just treat each impression as independent (they're clustered within users), and the variance of a ratio of two random sums isn't the variance of either part.

The **delta method** uses a first-order Taylor expansion to approximate the variance of a function of random variables — here, the ratio `R = X/Y`:

```text
Var(X/Y) ≈ (1/μ_Y²) [ Var(X) − 2(μ_X/μ_Y)Cov(X,Y) + (μ_X/μ_Y)² Var(Y) ]
```

Computed at the **user** level (each user contributes a numerator and denominator), this gives correct, cluster-aware standard errors for ratio metrics. Ignoring it and using naive per-event variance **understates** uncertainty and produces false positives — one of the most common subtle bugs in homegrown experiment analysis.

### Q41. [Theory] How do you analyze experiments with heterogeneous treatment effects (HTE)?

The average treatment effect (ATE) can hide that a change **helps some users and hurts others**. Heterogeneity analysis asks *for whom* the treatment works.

- **Pre-registered segmentation:** define a small number of segments up front (new vs. returning, platform, country, power-user tier) and report the effect within each — *pre-registered* to avoid the multiple-comparisons trap of slicing until something turns significant.
- **Modern HTE / uplift modeling:** estimate a per-unit **conditional average treatment effect (CATE)** with methods like **causal forests**, **meta-learners (S/T/X-learners)**, or **double machine learning**. These let you target the treatment only to users predicted to benefit (uplift modeling / policy learning).

Cautions: subgroup analysis is a notorious source of false discoveries — apply **multiple-comparisons correction**, validate any discovered segment on **held-out data or a follow-up confirmatory experiment**, and beware that subgroups have smaller n (lower power, noisier estimates).

### Q42. [Behavioral] Describe a time you had to convince stakeholders to kill a feature the data didn't support, or to wait for a properly powered test.

Use a STAR structure and emphasize statistical rigor plus stakeholder empathy:

- **Situation:** a launch was being pushed based on an early "significant" result. 
- **Task:** I needed to prevent a premature ship without being the person who just says "no."
- **Action:** I showed that the result came from **peeking** (the p-value had wandered below 0.05 mid-run and would likely regress), that the experiment hadn't reached its **pre-registered sample size**, and that a **guardrail** (latency) had drifted. I reframed the decision around **risk**: shipping now risked a Type I error and a metric we'd have to roll back; waiting one more week cost little. I proposed a concrete stop date and an agreed decision rule *before* we looked again.
- **Result:** we waited; the effect regressed toward zero, confirming it was noise — saving us a rollback and, more importantly, establishing **pre-registration and no-peeking** as team norms.

The interviewer is checking that you can hold a statistical line *and* bring people along — translating "underpowered, peeked, guardrail-breaching" into business risk language.

### Q43. [Practical] What is a switchback / cluster-randomized experiment, and when do you need one?

When the standard "randomize individual users" assumption breaks because of **interference / network effects** — one unit's treatment affects another's outcome — independent-unit A/B tests are biased. Two designs handle this:

- **Cluster randomization:** randomize whole **clusters** (cities, markets, social communities) and analyze at the cluster level. Used for marketplaces and social products where treating one side spills over (e.g. a pricing change affects all riders/drivers in a city).
- **Switchback experiments:** randomize **time windows** within the same unit — the whole city is on treatment for some intervals and control for others, alternating. Common in two-sided marketplaces (ride-hailing, food delivery) where you can't cleanly split users without contaminating supply/demand.

```text
Switchback over time in one market:
  | A | B | B | A | B | A | A | B |   ← alternate treatment by time block
```

Cost: far **fewer effective units** (markets or time-blocks instead of users) → much lower power and wider CIs, plus the need to handle **temporal autocorrelation** in the analysis. You accept that to get an **unbiased** estimate in the presence of interference.

### Q44. [Theory] How do you distinguish a real long-term effect from a short-term metric movement?

Short-term experiment wins can fail to translate into long-term value (or even reverse). Techniques to bridge the gap:

- **Holdback / long-term holdout:** keep a small fraction of users in control for **weeks or months** after launch and keep measuring — directly observes whether the lift persists or decays (catches novelty effects).
- **Surrogate / proxy metrics:** validate that your short-term OEC actually **predicts** the long-term north-star metric, using historical experiments; ship on the surrogate only once that link is established.
- **Plot effect over time:** a stable plateau supports a real effect; a steady decay flags novelty; a delayed rise flags primacy.
- **Guardrails on retention-adjacent metrics** so a short-term engagement bump that cannibalizes long-term retention is caught.

The mature framing: optimize for **long-term user value**, treat short-term metrics as *instruments* for it, and continuously validate that the instruments still point the same direction.

---

## 🔴 Expert (15+ yrs)

### Q45. [Theory] How do you build and maintain trust in an organization-wide experimentation program at scale?

Beyond individual test correctness, the expert concern is **systemic trustworthiness** across thousands of concurrent experiments:

- **Trustworthy-by-default platform:** SRM detection, power checks, peeking-safe sequential inference, FDR correction, and CUPED are **built in**, not optional — analysts can't accidentally produce a bad result.
- **A/A tests and calibration:** run **A/A experiments** continuously to verify the engine's false-positive rate really is ~5%; if A/A tests fail, the pipeline (logging, randomization, variance estimation) is broken.
- **Institutional memory:** a searchable **experiment registry** with pre-registered hypotheses, decisions, and outcomes — prevents re-litigating settled questions and exposes a base rate (most experiments don't win, which itself calibrates expectations).
- **Meta-analysis & culture:** track the **win rate** and effect-size distribution across experiments to detect publication-bias-style inflation; teach the org that **most ideas fail** and that a rigorous "no" is a successful experiment. 
- **Governance:** ethical review for risky changes, and a clear decision framework that separates statistical significance from product judgment.

### Q46. [Theory] What are the threats to validity in online experiments, and how do you categorize them?

A senior taxonomy mirrors classic experimental design, adapted to product:

- **Internal validity** (is the causal estimate correct *for this experiment*?): **SRM**, instrumentation/logging bugs, **interference/network effects** between arms, **carryover** from prior experiments, survivorship, and **dilution** (users who never saw the change but are counted).
- **External validity** (does it **generalize**?): seasonality, the experiment population differing from the launch population, **primacy/novelty**, and segment-specific effects that don't hold globally.
- **Statistical conclusion validity** (are the inferences sound?): **peeking**, **multiple comparisons**, underpowering, wrong variance (ratio metrics, clustering), and assumption violations.
- **Construct validity** (does the metric measure what you intend?): a proxy OEC that doesn't actually track long-term value; metric definition gaming.

The expert move is to map each known failure mode to an **automated guardrail or design choice** so the platform systematically defends each category, and to know which threats a given design (switchback, holdback, cluster) is specifically buying down.

### Q47. [Theory] How do experimentation, causal inference from observational data, and reinforcement learning / bandits relate as a decision toolkit?

A mature org uses **different causal tools for different constraints**, not just A/B tests:

- **Randomized experiments (A/B):** gold standard for causation; use when you can randomize and can afford to wait for a fixed horizon.
- **Multi-armed / contextual bandits:** when you want to **minimize regret** by shifting traffic toward winning arms *during* the test (Thompson sampling, UCB) — ideal for many short-lived variants (headlines, layouts) where exploration cost is high. Trade-off: harder to get a clean, unbiased per-arm effect and CI than a fixed A/B test.
- **Observational causal inference:** when randomization is impossible/unethical — use **difference-in-differences**, **regression discontinuity**, **instrumental variables**, **synthetic control**, or **propensity-score matching**. These rely on identifying assumptions (parallel trends, valid instrument, no unmeasured confounding) that are **untestable**, so conclusions are weaker and must be stress-tested with sensitivity analysis.

```text
Can randomize cheaply?            ── yes ─► A/B test (or sequential)
Many variants, want low regret?   ── yes ─► bandits / contextual bandits
Can't randomize at all?           ── yes ─► DiD / RDD / IV / synthetic control
```

The expertise is choosing the **weakest-assumption tool the situation allows** and being explicit about the assumptions you're buying.

### Q48. [Behavioral] Tell me about a time a "statistically significant" result turned out to be wrong in production. What did you change?

Frame it as a learning-and-systems story:

- **Situation:** a winning experiment shipped on a clearly significant primary-metric lift, but the metric flattened (or regressed) after full rollout.
- **Task:** diagnose why a rigorous-looking result didn't replicate, and prevent recurrence.
- **Action:** root-cause analysis revealed a combination — a **novelty effect** (returning users drove the early lift, which decayed), and the analysis had used **per-event variance** on a ratio metric, **understating uncertainty**. I re-ran with **delta-method clustered SEs** and a **long-term holdback**, which showed the true effect was within noise.
- **Result:** I drove platform changes: delta-method variance for all ratio metrics, mandatory long-term holdbacks for headline launches, and an effect-over-time plot in the default report. The broader lesson I emphasize: **one significant number is a hypothesis, not a conclusion** — trust comes from replication, correct variance, and time, and the fix is almost always **systemic** (improve the platform) rather than blaming an individual analyst.

The interviewer wants intellectual honesty about a real failure, correct statistical diagnosis, and evidence you turned a one-off mistake into a durable organizational safeguard.

---

## 🧩 Extended Questions — Set 1: Deeper theory & internals

This set drills below the working knowledge of the earlier questions into the *mathematical machinery* that makes the tools work: where the t-distribution actually comes from, why the bootstrap is valid, how p-values are constructed as random variables, the duality between tests and intervals, the geometry behind degrees of freedom, and the asymptotic theory (MLE, Wald/score/LRT, delta method) underneath modern experimentation engines. The lens is "open the black box."

### 🟢 — extended

#### Q49. [Theory] Why does the variance of the sample mean equal σ²/n? Derive it from first principles.

Start from the definition of the sample mean of i.i.d. observations X₁,…,Xₙ, each with variance σ²:

```text
x̄ = (1/n) Σ Xᵢ
Var(x̄) = Var( (1/n) Σ Xᵢ )
        = (1/n²) Var( Σ Xᵢ )            (scaling: Var(aZ) = a² Var(Z))
        = (1/n²) Σ Var(Xᵢ)              (independence ⇒ no covariance terms)
        = (1/n²) · n σ²
        = σ² / n
```

The two load-bearing steps are: (1) pulling the constant 1/n out squares it; (2) the variance of a **sum** equals the sum of variances **only because the observations are independent** — the cross-covariance terms vanish. Take the square root and you get the standard error, σ/√n. This also exposes the failure mode: if observations are *positively correlated* (e.g. multiple events from the same user), the covariance terms are positive, the true variance is **larger** than σ²/n, and naive analysis understates uncertainty — exactly the clustering problem that clustered/delta-method SEs fix.

#### Q50. [Theory] What is a sampling distribution, and how is it different from the data distribution and the population distribution?

There are three distinct distributions people constantly conflate:

- **Population distribution:** the distribution of the raw quantity over every entity in the population (e.g. session times of all users). Has mean μ, variance σ².
- **Data (sample) distribution:** the empirical distribution of the n values you actually collected — a histogram of your data. As n→∞ it converges to the population distribution.
- **Sampling distribution of a statistic:** the distribution of a *computed statistic* (like x̄, or a t-statistic, or a p-value) across hypothetical repeated samples of size n. This is the one inference is built on.

```text
Population ──draw n──► one sample ──compute──► one value of x̄
            repeat the whole process many times
            ──► the spread of those x̄ values = sampling distribution of x̄
```

The CLT is a statement about the **sampling distribution of the mean** (it becomes normal), *not* about the data distribution (which keeps whatever shape the population has, no matter how large n is). Standard error is the standard deviation *of the sampling distribution*. Keeping these three straight resolves most beginner confusion.

#### Q51. [Theory] Why is the sample mean an unbiased estimator of μ, and what does "unbiased" precisely mean?

An estimator θ̂ is **unbiased** for a parameter θ if its expected value over repeated sampling equals the true value: E[θ̂] = θ. "Bias" is E[θ̂] − θ. For the sample mean:

```text
E[x̄] = E[(1/n) Σ Xᵢ] = (1/n) Σ E[Xᵢ] = (1/n) · n μ = μ
```

So the sample mean is exactly unbiased for any n, for any distribution with a finite mean (no normality needed). Crucial nuances: (1) unbiasedness is about the *average* over many hypothetical samples, not about any single estimate being correct; (2) unbiasedness is not the only virtue — a biased estimator with much lower variance can have smaller mean-squared error (MSE = bias² + variance), which is the bias–variance tradeoff; (3) unbiasedness does not survive nonlinear transforms — E[x̄²] ≠ μ², and the sample standard deviation s is actually a *slightly biased* estimator of σ even though s² is unbiased (because the square root is concave, by Jensen's inequality).

#### Q52. [Coding] Empirically show that dividing by n (instead of n−1) underestimates the population variance.

```python
import numpy as np

rng = np.random.default_rng(0)
true_var = 4.0                     # population variance (sigma^2), sigma = 2
n = 5                              # small sample makes the bias obvious
trials = 200_000

biased = np.empty(trials)          # divide by n
unbiased = np.empty(trials)        # divide by n-1 (Bessel)

for i in range(trials):
    s = rng.normal(0.0, 2.0, size=n)
    dev2 = ((s - s.mean())**2).sum()
    biased[i]   = dev2 / n
    unbiased[i] = dev2 / (n - 1)

print(f"true variance         = {true_var:.3f}")
print(f"E[divide by n]    ≈ {biased.mean():.3f}   (under by ~factor (n-1)/n = {(n-1)/n:.2f})")
print(f"E[divide by n-1]  ≈ {unbiased.mean():.3f}  (matches true variance)")
# E[divide by n] ≈ 3.20 ≈ 4 * 4/5;  E[divide by n-1] ≈ 4.00
```

The biased estimator converges to `σ² · (n−1)/n`, which is why the correction is exactly the factor `n/(n−1)`. The effect is large at small n and negligible as n grows — which is also why the distinction stops mattering for big experiments.

#### Q53. [Theory] What is a quantile/percentile, and how is it defined precisely (the inverse-CDF view)?

A **quantile** at level q ∈ (0,1) is a value x_q such that a fraction q of the distribution's mass lies at or below it: F(x_q) = q, where F is the cumulative distribution function (CDF). The quantile function is therefore the **inverse CDF**, Q(q) = F⁻¹(q). The median is Q(0.5); the 95th percentile is Q(0.95).

```text
CDF F:  value ──► cumulative probability
Quantile Q = F⁻¹:  probability ──► value
```

Subtleties that matter in practice: (1) for discrete or sampled data the inverse isn't unique, so libraries use **interpolation methods** (numpy's `method=` argument: linear, lower, higher, nearest, midpoint) and different conventions give slightly different answers — a real source of "why don't our dashboards agree?" bugs; (2) quantiles are exactly what you compute for **latency SLOs** (p50/p95/p99) and for **percentile bootstrap CIs**; (3) the inverse-CDF identity is the basis of **inverse-transform sampling** — feed Uniform(0,1) draws through F⁻¹ to generate samples from any distribution.

#### Q54. [Theory] What does "degrees of freedom" actually mean, geometrically?

Degrees of freedom (df) is the number of **independent dimensions of variation** left in your data after you've used some of them to estimate parameters. The cleanest mental model is geometric: your n data points live in n-dimensional space. When you compute the residuals `xᵢ − x̄`, you've forced them to satisfy one **linear constraint** — they must sum to zero (`Σ(xᵢ − x̄) = 0`), because that's what defines the mean. That constraint pins the residual vector to an (n−1)-dimensional subspace.

```text
n data points  →  n dimensions
estimate the mean  →  residuals constrained to sum to 0  →  one dimension lost
→  residuals live in an (n−1)-dimensional space  →  df = n−1
```

So dividing the sum of squared residuals by n−1 (not n) divides by the *true* dimensionality of the space the residuals can move in — that's the deep reason Bessel's correction uses n−1. The same accounting generalizes: a regression with p estimated coefficients leaves n−p residual degrees of freedom; a chi-square contingency table with r rows and c columns has (r−1)(c−1) df because of the row/column total constraints.

#### Q55. [Practical] What is the difference between a parameter, an estimator, and an estimate? Why does the distinction matter?

- **Parameter:** a fixed (usually unknown) property of the population — μ, σ, the true conversion rate p. It is a constant, not random.
- **Estimator:** a *rule/function* of the data used to guess the parameter — e.g. "take the sample mean." Because it's a function of random data, the estimator itself is a **random variable** with its own distribution (the sampling distribution), bias, and variance.
- **Estimate:** the single number you get when you plug your actual data into the estimator — e.g. x̄ = 4.02%. It is a fixed realized value.

```text
Parameter μ  ──estimated by──►  Estimator x̄ (random variable)  ──realized as──►  Estimate 4.02%
```

Why it matters: confidence intervals, standard errors, bias, and consistency are all statements about the **estimator** (the procedure), not about any single estimate. This is exactly why "95% confident" refers to the long-run behavior of the interval-construction *procedure*, not to the one interval you computed. Interview answers that blur these three usually also fumble the CI interpretation.

#### Q56. [Coding] Estimate the standard error of the median by bootstrap (a statistic with no simple closed form).

The mean has a clean SE (σ/√n), but many statistics (median, trimmed mean, ratios, percentiles) don't. The **bootstrap** estimates the sampling distribution by resampling the data with replacement.

```python
import numpy as np

rng = np.random.default_rng(1)
# right-skewed sample (e.g. latencies in ms) where the median is the honest summary
data = rng.lognormal(mean=3.0, sigma=0.5, size=400)

def bootstrap_se(x, stat=np.median, B=10_000, seed=1):
    r = np.random.default_rng(seed)
    n = len(x)
    boot_stats = np.empty(B)
    for b in range(B):
        sample = x[r.integers(0, n, size=n)]   # resample WITH replacement, same size n
        boot_stats[b] = stat(sample)
    return boot_stats.std(ddof=1), boot_stats

se_median, boot = bootstrap_se(data)
ci_lo, ci_hi = np.percentile(boot, [2.5, 97.5])    # percentile bootstrap CI
print(f"median estimate = {np.median(data):.2f}")
print(f"bootstrap SE    = {se_median:.3f}")
print(f"95% percentile CI = [{ci_lo:.2f}, {ci_hi:.2f}]")
```

The key idea: the empirical distribution of the data is treated as a stand-in for the population, and resampling from it mimics drawing fresh samples. It's almost assumption-free and works for any statistic, which is why it's the go-to when no formula exists — at the cost of compute and a need for enough data that the empirical distribution is a decent proxy.

### 🟡 — extended

#### Q57. [Theory] Where does the t-distribution actually come from? Derive its structure.

The t-statistic for a sample mean is `t = (x̄ − μ) / (s/√n)` — it's the standardized mean, but using the *estimated* standard deviation s instead of the true σ. Decompose it:

```text
        (x̄ − μ)/(σ/√n)            Z              standard normal
t  =  ─────────────────────  =  ─────────  =  ────────────────────────────
            s/σ                √(χ²_{n−1}/(n−1))    √(chi-square / its df)
```

For normal data, the numerator `Z = (x̄−μ)/(σ/√n)` is exactly **standard normal**, and `(n−1)s²/σ²` follows a **chi-square distribution with n−1 degrees of freedom**, *independent* of x̄ (a special property of the normal). A standard normal divided by the square root of an independent chi-square-over-its-df is, by definition, a **Student's t** with n−1 df. The intuition: the denominator s wobbles around σ, and that extra randomness fattens the tails relative to a normal — most pronounced at small n. As n→∞, s→σ, the chi-square term concentrates at 1, and t converges to the standard normal — which is why the z-test is the large-sample limit of the t-test.

#### Q58. [Theory] Walk through the duality between hypothesis tests and confidence intervals.

Tests and intervals are two views of the same object. The **inversion** principle:

```text
A 95% confidence interval = the set of all parameter values θ₀ that you would
NOT reject at α = 0.05 in a two-sided test of H₀: θ = θ₀.
```

So a 95% CI for a treatment effect *excludes* zero **if and only if** the two-sided test of "no effect" rejects at α = 0.05. This is why "the CI for the lift excludes 0" and "p < 0.05" always agree (for the same test). Practical payoffs: (1) you can read significance straight off a CI plot — does the interval cross the null value? (2) the CI carries strictly more information than the p-value: it also tells you the *plausible range* of effect sizes and lets you check whether the whole interval lies inside your practical-significance / non-inferiority band; (3) it makes guardrail (non-inferiority) testing natural — "the CI's lower bound is above the harm tolerance." Reporting the CI is therefore almost always better than reporting the p-value alone.

#### Q59. [Coding] Verify the test–CI duality empirically on a two-sample difference.

```python
import numpy as np
from scipy import stats

rng = np.random.default_rng(3)
a = rng.normal(0.0, 1.0, size=300)
b = rng.normal(0.25, 1.0, size=300)        # small true effect

diff = b.mean() - a.mean()
se = np.sqrt(a.var(ddof=1)/len(a) + b.var(ddof=1)/len(b))
t_stat, p = stats.ttest_ind(b, a, equal_var=False)

z = stats.norm.ppf(0.975)
ci = (diff - z*se, diff + z*se)

excludes_zero = (ci[0] > 0) or (ci[1] < 0)
rejects = p < 0.05
print(f"diff={diff:.3f}  95% CI=({ci[0]:.3f}, {ci[1]:.3f})  p={p:.4f}")
print(f"CI excludes 0? {excludes_zero}   test rejects at 0.05? {rejects}")
# The two booleans always agree -> that IS the duality.
```

The two booleans move together: whenever the interval clears zero, the test rejects, and vice versa. The CI is the inverted test.

#### Q60. [Theory] What is a p-value as a random variable, and why is it Uniform(0,1) under the null?

A p-value is computed from random data, so it is itself a random variable. The clean theorem: **for a continuous test statistic, the p-value is Uniform(0,1) when H₀ is true.**

```text
p = 1 − F(T)  (or the appropriate tail prob), where F is the null CDF of the statistic T.
Under H₀, T ~ F, and the "probability integral transform" says F(T) ~ Uniform(0,1).
Hence p ~ Uniform(0,1) under H₀.
```

Two profound consequences follow. (1) **Calibration of α:** P(p ≤ α | H₀) = α exactly — that's *why* rejecting when p < 0.05 gives a 5% false-positive rate; the threshold is honest only because the null distribution of p is uniform. (2) **The peeking problem demystified:** if p is uniform on each look, then over many looks the *running minimum* p drifts below 0.05 far more than 5% of the time — repeatedly sampling a uniform and stopping at the first small value is guaranteed to find one. The whole machinery of sequential testing exists to restore a controlled error rate when the single-look uniformity is exploited by repeated looking. (Note: for discrete statistics the p-value is only *stochastically* ≥ uniform, making exact tests slightly conservative.)

#### Q61. [Coding] Show that the p-value is uniformly distributed under the null.

```python
import numpy as np
from scipy import stats

rng = np.random.default_rng(5)
trials = 50_000
pvals = np.empty(trials)

for i in range(trials):
    # H0 is TRUE: both groups drawn from the SAME distribution
    a = rng.normal(0, 1, size=200)
    b = rng.normal(0, 1, size=200)
    _, pvals[i] = stats.ttest_ind(a, b, equal_var=False)

# Under H0 the p-values should be ~Uniform(0,1):
for thr in [0.01, 0.05, 0.10, 0.50]:
    print(f"P(p <= {thr}) ≈ {(pvals <= thr).mean():.3f}  (expected {thr})")

ks_stat, ks_p = stats.kstest(pvals, "uniform")   # test uniformity
print(f"KS test vs Uniform(0,1): stat={ks_stat:.4f}, p={ks_p:.3f} (large p ⇒ looks uniform)")
# P(p<=0.05) ≈ 0.05 confirms the 5% false-positive rate is honest.
```

`P(p ≤ 0.05) ≈ 0.05` is the empirical demonstration that the 5% significance level delivers a 5% Type I error rate — *because* the null p-values are uniform. Re-run this peeking after every observation and you'd see the rejection rate balloon, which is the peeking problem in code.

#### Q62. [Theory] Explain the bias–variance decomposition of mean-squared error and connect it to CUPED and regularization.

For any estimator θ̂ of a target θ, the mean-squared error decomposes cleanly:

```text
MSE(θ̂) = E[(θ̂ − θ)²] = Bias(θ̂)² + Var(θ̂)
         where Bias(θ̂) = E[θ̂] − θ
```

This is the master tradeoff: you can often *reduce variance at the cost of some bias* (or vice versa) and come out ahead on total error. Connections across the doc: (1) **CUPED** is a pure-variance-reduction move that stays **unbiased** (because the covariate is pre-treatment), so it strictly lowers MSE — a free lunch precisely because it adds no bias; (2) **regularization** (ridge/lasso) deliberately *introduces* bias to cut variance, lowering MSE for prediction; (3) the n vs n−1 choice for variance is a bias question; (4) shrinkage/empirical-Bayes estimators (James–Stein) beat the unbiased sample mean in ≥3 dimensions by trading a little bias for a large variance reduction. The decomposition is the unifying lens for "should I accept some bias here?"

#### Q63. [Theory] Why are A/A tests run, and what exactly do they validate?

An **A/A test** splits traffic into two arms that receive the *identical* experience, so the true effect is exactly zero by construction. It is the integration test for the experimentation *platform itself*. What it validates:

- **False-positive calibration:** across many A/A tests, the fraction that come back "significant" at α should be ≈ α (≈5%). If it's much higher, your variance estimation is too tight (e.g. per-event variance on a clustered/ratio metric) or there's a systematic bug; if much lower, you're over-conservative and leaving power on the table.
- **No sample-ratio mismatch:** the split should hit its intended ratio; persistent SRM in A/A reveals broken hashing, uneven bot filtering, or logging loss.
- **Metric/pipeline sanity:** p-values from A/A tests should be **Uniform(0,1)** (per Q60). Plotting their histogram is a direct, powerful diagnostic — a spike near zero means the engine manufactures false positives.

```text
Healthy engine:  A/A p-values ~ Uniform(0,1),  ~5% "significant",  no SRM
Broken engine:   p-value histogram piled near 0,  >5% significant  ⇒ STOP and fix
```

A/A testing is how mature programs earn the right to trust their A/B results: you can't believe a positive result from an engine that flags noise as signal.

#### Q64. [Practical] What is the relationship between confidence level, interval width, sample size, and precision — and how do you reason about the tradeoffs?

The half-width of a normal-based CI for a mean is `margin = z_{1−α/2} · σ/√n`. Three levers:

- **Confidence level ↑** (e.g. 90% → 99%) raises z (1.645 → 2.576), so the interval gets **wider** — more confidence, less precision, for fixed n. There is no free lunch: you can't simultaneously be more certain *and* more precise without more data.
- **Sample size ↑** shrinks the margin as **1/√n** — quadrupling n halves the width. This is the only lever that improves precision without sacrificing confidence (or, equivalently, the lever behind sample-size planning).
- **Variance ↓** (via CUPED, stratification, a less noisy metric) shrinks the margin proportionally to σ — often cheaper than buying 4× traffic.

```text
margin ∝ z(confidence) · σ(noise) / √n(data)
        ↑ pick precision OR confidence for fixed n; buy both only with n or σ
```

The practical framing for stakeholders: "to halve our error bars we need 4× the users, *or* we can apply variance reduction and get there with the traffic we have." This reframes a statistical constraint as a resource decision.

### 🟠 — extended

#### Q65. [Theory] Derive the delta-method variance for a ratio metric and explain each term.

For a ratio metric R = X/Y (e.g. clicks/impressions) where X̄ and Ȳ are means over the **randomization unit** (users), take a first-order Taylor expansion of g(X,Y)=X/Y around the means (μ_X, μ_Y):

```text
R ≈ μ_X/μ_Y + (1/μ_Y)(X̄−μ_X) − (μ_X/μ_Y²)(Ȳ−μ_Y)

Var(R) ≈ (1/μ_Y²)·Var(X̄) + (μ_X²/μ_Y⁴)·Var(Ȳ) − 2(μ_X/μ_Y³)·Cov(X̄,Ȳ)
       = (1/μ_Y²)[ Var(X̄) − 2(μ_X/μ_Y)Cov(X̄,Ȳ) + (μ_X/μ_Y)²Var(Ȳ) ]
```

Reading the three terms: the **Var(X̄)** term is numerator noise; the **Var(Ȳ)** term is denominator noise (often forgotten — the denominator is random too!); the **Cov(X̄,Ȳ)** term captures that users with more clicks also tend to have more impressions, and ignoring this positive covariance *overstates* the variance. Because X̄ and Ȳ are computed per user, this is automatically **cluster-aware** — it respects that a user's events are correlated. The naive bug is computing variance as if each impression were an independent Bernoulli trial; that ignores the denominator's randomness *and* the within-user correlation, badly understating the SE and manufacturing false positives.

#### Q66. [Coding] Implement delta-method standard error for a ratio metric and compare it to the naive per-event SE.

```python
import numpy as np

rng = np.random.default_rng(11)
n_users = 5_000
# each user has a random number of impressions and clicks correlated with them
impressions = rng.poisson(20, size=n_users) + 1
ctr_user = rng.beta(2, 38, size=n_users)              # per-user true CTR, mean ~0.05
clicks = rng.binomial(impressions, ctr_user)

X, Y = clicks, impressions                            # per-user numerator, denominator
R = X.sum() / Y.sum()                                 # overall CTR

# ---- Delta-method (cluster-aware) SE ----
mx, my = X.mean(), Y.mean()
n = n_users
var_x, var_y = X.var(ddof=1), Y.var(ddof=1)
cov_xy = np.cov(X, Y, ddof=1)[0, 1]
var_R = (1/my**2) * (var_x - 2*(mx/my)*cov_xy + (mx/my)**2 * var_y) / n
se_delta = np.sqrt(var_R)

# ---- Naive per-event SE (WRONG: treats each impression as independent) ----
N_events = Y.sum()
se_naive = np.sqrt(R * (1 - R) / N_events)

print(f"overall CTR     = {R:.4f}")
print(f"delta-method SE = {se_delta:.5f}   (correct, cluster-aware)")
print(f"naive event  SE = {se_naive:.5f}   (too small ⇒ false positives)")
print(f"naive understates SE by ~{se_delta/se_naive:.1f}x")
```

The naive SE is typically several times too small because it ignores both the denominator's randomness and the clustering of events within users. Using it is one of the most common — and most consequential — bugs in homegrown experiment analysis.

#### Q67. [Theory] Explain maximum likelihood estimation and why MLEs are asymptotically normal.

**Maximum likelihood** picks the parameter value that makes the observed data most probable. You write the likelihood L(θ) = Πᵢ f(xᵢ; θ), maximize its log (sums are easier and numerically stabler than products), and take θ̂ = argmax ℓ(θ).

```text
ℓ(θ) = Σ log f(xᵢ; θ)        score: U(θ) = ℓ'(θ)        set U(θ̂)=0
Fisher information: I(θ) = −E[ℓ''(θ)] = Var(U(θ))
```

Under regularity conditions, the MLE is **consistent** (→ true θ) and **asymptotically normal**:

```text
θ̂ ≈ Normal( θ,  1 / (n·I(θ)) )        for large n
```

The intuition for the normality: a Taylor expansion of the score around the true θ, plus the CLT applied to the score (which is a sum of i.i.d. terms with mean zero), yields a normal limit. Fisher information I(θ) measures the curvature of the log-likelihood at its peak — a sharply peaked likelihood means the data pin down θ tightly, so the variance is small. The **Cramér–Rao bound** says no unbiased estimator can do better than 1/(nI(θ)), and the MLE *achieves* it asymptotically (it's efficient). This is the theoretical foundation under logistic-regression coefficients, GLM-based experiment analysis, and the variance estimates your stats engine reports.

#### Q68. [Theory] Compare the Wald, score (Lagrange-multiplier), and likelihood-ratio tests. When do they disagree?

All three test H₀: θ = θ₀ using the likelihood, and all three are asymptotically equivalent (χ²), but they probe the log-likelihood differently:

```text
Wald:   how far is θ̂ from θ₀, scaled by curvature at θ̂?   uses (θ̂−θ₀)²·I(θ̂)
Score:  how steep is the log-likelihood AT θ₀?            uses U(θ₀)²/I(θ₀)   (only needs the null fit)
LRT:    how much does the log-likelihood DROP from θ̂ to θ₀?  uses 2[ℓ(θ̂)−ℓ(θ₀)]
```

```text
ℓ(θ)
 │        ___peak at θ̂ (Wald: horizontal distance to θ₀)
 │      /│   \
 │     / │    \   LRT: vertical drop between the two heights
 │    /  │     \
 │___/___│______\___ θ
       θ₀   θ̂
   (Score: slope of the curve at θ₀)
```

They **disagree** when the log-likelihood is asymmetric or the sample is small (so the asymptotics haven't kicked in). The **LRT** is generally the most reliable (invariant to reparameterization, respects the actual likelihood shape). The **Wald** test is the most convenient (it's just estimate/SE, which is what every regression table prints) but can misbehave badly near boundaries — e.g. the **Hauck–Donner effect**, where a very strong effect paradoxically produces a *small* Wald statistic because the SE blows up. The **score** test is handy when fitting the full model is hard but the null model is easy. Knowing which one your tooling uses explains discrepancies between a regression's reported p-value and a likelihood-ratio test of the same coefficient.

#### Q69. [Theory] What is the general delta method (beyond ratios), and how do you apply it to a log-odds or a relative lift?

The delta method gives the asymptotic variance of a smooth transform g(θ̂) of an estimator: if θ̂ ≈ Normal(θ, V), then to first order

```text
g(θ̂) ≈ Normal( g(θ),  g'(θ)² · V )           (scalar)
Var(g(θ̂)) ≈ ∇g(θ)ᵀ · Σ · ∇g(θ)               (vector/multivariate)
```

Applications that constantly appear in experiment analysis:

- **Relative lift** `(p_T − p_C)/p_C`: a function of two estimated proportions; the delta method propagates both groups' variances (and they're independent across arms, so no covariance term) to get a CI on the *percentage* lift, which is what stakeholders actually quote.
- **Log-odds / logit** `log(p/(1−p))`: g'(p) = 1/(p(1−p)), so Var(logit) ≈ Var(p̂)/(p(1−p))² — and CIs are often built on the logit scale (where the sampling distribution is more symmetric and the interval can't escape [0,1]) and then back-transformed.
- **Geometric-mean / log-scale metrics:** taking logs of a skewed metric, computing the CI there, and exponentiating uses the delta method (or, equivalently, Jensen-aware back-transform corrections).

The recurring principle: don't compute a variance on the raw scale and then transform the endpoints naively — propagate the variance *through* the transform with its derivative, which is exactly what the delta method automates.

#### Q70. [Coding] Use the delta method to put a confidence interval on a relative lift.

```python
import numpy as np
from scipy import stats

# control and treatment conversion data
xc, nc = 480, 12_000      # control conversions / users
xt, nt = 540, 12_000      # treatment conversions / users

pc, pt = xc/nc, xt/nt
var_pc = pc*(1-pc)/nc
var_pt = pt*(1-pt)/nt

# relative lift g(pc, pt) = (pt - pc)/pc = pt/pc - 1
lift = pt/pc - 1
# gradient of g wrt (pc, pt): d/dpc = -pt/pc^2 ,  d/dpt = 1/pc
g_pc = -pt/pc**2
g_pt =  1/pc
var_lift = g_pc**2 * var_pc + g_pt**2 * var_pt   # arms independent ⇒ no covariance
se_lift = np.sqrt(var_lift)

z = stats.norm.ppf(0.975)
print(f"control={pc:.4f}  treatment={pt:.4f}")
print(f"relative lift = {lift:+.2%}   95% CI = [{lift - z*se_lift:+.2%}, {lift + z*se_lift:+.2%}]")
# Reports the lift stakeholders care about, WITH propagated uncertainty.
```

This gives a CI on the **percentage** lift — the number leadership actually asks for — with uncertainty correctly propagated from both arms, rather than the common mistake of reporting a point lift with no interval or transforming the absolute-difference CI incorrectly.

#### Q71. [Practical] How does Welch's t-test approximate its degrees of freedom, and why does that matter for unequal variances/sizes?

The pooled two-sample t-test assumes equal variances and uses df = n₁+n₂−2. When variances differ (the common case across A/B arms — treatment often changes the *spread*, not just the mean), that assumption inflates Type I error. **Welch's t-test** uses a separate-variance statistic and approximates its df with the **Welch–Satterthwaite** formula:

```text
              ( s₁²/n₁ + s₂²/n₂ )²
df_welch  ≈  ─────────────────────────────────────
             (s₁²/n₁)²/(n₁−1) + (s₂²/n₂)²/(n₂−1)
```

This is a non-integer, data-dependent df that interpolates between the two groups' individual df. Why it matters: (1) it correctly **widens the tails** (lowers df) when one group is small or much noisier, restoring the nominal error rate; (2) it degrades gracefully — with equal variances and sizes it reduces to the pooled df, so you lose almost nothing by always using Welch; (3) it's why Welch is the recommended **default** (e.g. `equal_var=False` in scipy) — you rarely *know* variances are equal, and the cost of being wrong (false positives) outweighs the tiny power cost when they happen to be equal. The practical rule: default to Welch unless you have a strong reason to pool.

### 🔴 — extended

#### Q72. [Theory] Derive why the running-minimum p-value crosses α with probability far above α, and connect it to alpha-spending.

Model a fixed-horizon test monitored at K interim looks. Under H₀, by the law of the iterated logarithm the standardized cumulative statistic Sₜ/√t behaves like a random walk that, with **probability 1**, eventually exceeds *any* fixed boundary. Concretely, the event "p ever drops below α in K looks" is the union of K correlated crossing events:

```text
P( min over K looks of pₖ < α | H₀ )  =  P( ∪ₖ {pₖ < α} )  ≫  α   as K grows
For independent looks it would be 1−(1−α)^K; correlation between looks softens
this but it still climbs far above α (≈ 30–50% for daily peeking over ~2–3 weeks).
```

The fix — **alpha spending** (Lan–DeMets) — treats α as a *budget* allocated across looks via a spending function f(t) with f(0)=0, f(1)=α. At each look you test against a boundary that has "spent" only f(t_k)−f(t_{k−1}) of the budget, so the **cumulative** Type I probability across *all* looks totals exactly α.

```text
O'Brien–Fleming spending: spend almost nothing early (very strict early boundaries),
   most of α near the end  ⇒ preserves power at the planned horizon, hard to stop super early.
Pocock spending: constant boundary across looks ⇒ easier early stopping, lower final power.
```

The deeper unification: **confidence sequences / always-valid p-values** (built from mixture-SPRTs or supermartingale e-processes) make the boundary valid at *every* possible stopping time simultaneously, so the optional-stopping problem disappears entirely — you can peek infinitely. That's the theory powering "stop anytime" experimentation dashboards.

#### Q73. [Theory] Explain the mSPRT / e-value foundation of always-valid inference. Why is it valid under optional stopping?

Classic p-values break under optional stopping because P(p_t ≤ α for *some* t | H₀) ≫ α. Always-valid inference fixes this by building a test statistic that is a **non-negative supermartingale** under H₀ — its expected value never grows over time:

```text
e-process Eₜ ≥ 0,  E[Eₜ | H₀] ≤ 1 for all t.
Ville's inequality:  P( supₜ Eₜ ≥ 1/α | H₀ ) ≤ α.
```

Because the bound holds over the **supremum across all t**, you may stop at *any* data-dependent time τ and still have P(E_τ ≥ 1/α) ≤ α — optional stopping is fully licensed. The **mixture SPRT (mSPRT)** instantiates this by mixing the simple-vs-simple likelihood ratio over a prior on the effect size, producing a likelihood ratio that's a martingale under the null; its reciprocal is an **always-valid p-value** p_t = min(1, 1/Λ_t), and inverting it gives a **confidence sequence** — a sequence of intervals that *simultaneously* covers the truth at all times with probability ≥ 1−α.

```text
e-value E = 1/p  →  "evidence" that accumulates; you can keep betting as data arrives.
Confidence sequence: wider than a fixed-n CI (the price of anytime-validity),
   but it shrinks as data grows and is honest no matter when you look.
```

The conceptual shift is from "fix n, then test" to "test continuously, with a statistic engineered so that looking can never inflate error." The cost is a modestly wider interval at the planned horizon — you pay a little power for the freedom to stop whenever you want.

#### Q74. [Theory] What is Fisher information, the Cramér–Rao lower bound, and how do they set the floor on experiment precision?

**Fisher information** I(θ) quantifies how much a single observation tells you about θ — formally the variance of the score (the derivative of the log-likelihood) or, equivalently, the expected curvature of the log-likelihood:

```text
I(θ) = E[ (∂/∂θ log f(X;θ))² ] = −E[ ∂²/∂θ² log f(X;θ) ]
```

A sharply curved log-likelihood (large I) means the data strongly constrain θ. The **Cramér–Rao lower bound** then states the hard floor on the variance of *any* unbiased estimator:

```text
Var(θ̂)  ≥  1 / (n · I(θ))
```

Implications for experimentation: (1) it's a **fundamental precision limit** — no clever estimator beats it without adding bias, so once you're at the bound, the only way to tighter CIs is more data n or a lower-variance metric; (2) for a Bernoulli/conversion metric, I(p) = 1/(p(1−p)), which is *minimized* (information *maximized*) near p=0.5 and tiny near 0 or 1 — that's the formal reason rare-event metrics (low base-rate conversions) need enormous samples; (3) **variance reduction (CUPED)** effectively raises the information per user by removing predictable noise, moving you toward the bound for a *transformed*, lower-variance metric; (4) MLE achieving the bound asymptotically is *why* model-based experiment analyses report `1/√(nI)` style standard errors. Fisher information is the bridge between "how informative is my metric" and "how much traffic will this test cost."

#### Q75. [Theory] How does multiple-testing correction generalize to dependent tests, and what do FWER vs FDR control guarantee asymptotically?

Bonferroni and Benjamini–Hochberg are the textbook tools, but at scale the *dependence structure* and the *error metric* both matter:

- **FWER (family-wise error rate)** = P(≥1 false positive). Bonferroni controls it under *any* dependence (it's a union bound, so it never fails — just gets conservative). **Holm's** step-down is uniformly more powerful than Bonferroni and still controls FWER under arbitrary dependence. **Šidák** is slightly tighter under independence.
- **FDR (false discovery rate)** = E[ false positives / total discoveries ]. **Benjamini–Hochberg** controls FDR at level q under independence and under **positive regression dependence (PRDS)** — a condition that holds for many real metric panels. Under *arbitrary* dependence you need **Benjamini–Yekutieli**, which divides by a harmonic factor Σ1/i (more conservative, the price of unknown dependence).

```text
Choose by what you're protecting:
  one critical decision, any false positive is costly  →  FWER (Holm/Bonferroni)
  many metrics, tolerate a controlled FRACTION wrong  →  FDR (BH, or BY if dependent)
```

Asymptotic/scale insight: as the number of tests m→∞, FWER control forces per-test thresholds toward 0 (power collapses), whereas FDR thresholds **adapt** to the number of true discoveries — so FDR is the only sensible choice for large metric/segment sweeps. Even more modern: **e-value-based** multiple testing (e-BH) controls FDR under *arbitrary* dependence without the Yekutieli penalty, and composes naturally with the always-valid sequential machinery — letting you do peeking-safe *and* multiplicity-safe inference simultaneously, which is where state-of-the-art experimentation platforms (circa 2026) are heading.

#### Q76. [Theory] Reconcile the frequentist confidence interval and the Bayesian credible interval at a foundational level. When do they coincide and when do they diverge sharply?

They are answers to different questions that *numerically* often agree:

```text
Frequentist CI:  random interval, fixed parameter.
   Guarantee: over repeated experiments, 95% of such intervals cover the true θ.
   It does NOT say P(θ ∈ this interval) = 0.95.
Bayesian credible interval:  fixed interval, parameter treated as random (posterior).
   Statement: P(θ ∈ interval | data, prior) = 0.95 — a direct probability about θ.
```

**Coincidence:** with a flat/uninformative prior and a symmetric likelihood (e.g. a normal mean with known variance), the 95% credible interval and the 95% CI are *identical numerically* — the posterior is just the likelihood renormalized. More generally, the **Bernstein–von Mises theorem** says that as n→∞, the posterior converges to a normal centered at the MLE with the inverse-Fisher-information variance — so credible and confidence intervals **asymptotically coincide regardless of the (fixed) prior**. This is why, with lots of data, the philosophical gulf rarely changes the decision.

**Sharp divergence:** (1) small samples with an informative prior — the prior shifts and shrinks the credible interval; a strong/biased prior can make it badly miscalibrated as a frequentist procedure; (2) **boundary/constrained parameters** — for a near-zero rate, the frequentist Wald CI can extend below 0 (nonsensical) while a Beta-posterior credible interval respects [0,1]; (3) **optional stopping** — a Bayesian credible interval is "valid given the prior" at any stopping time (the posterior doesn't care *why* you stopped), whereas a naive frequentist CI is corrupted by peeking — which is exactly why Bayesian A/B platforms advertise continuous monitoring. The expert stance: the two are *tools with different guarantees*; pick by what you must guarantee (long-run error control vs. a direct probability statement) and report which one you're making — most production miscommunication comes from quoting a frequentist interval with a Bayesian sentence.

#### Q77. [Coding] Demonstrate Bernstein–von Mises: a Bayesian posterior and a frequentist CI converge as n grows.

```python
import numpy as np
from scipy import stats

rng = np.random.default_rng(21)
true_p = 0.3

def compare(n, prior=(1, 1)):   # Beta(1,1) = uniform prior
    x = rng.binomial(n, true_p)            # observed successes
    p_hat = x / n
    # Frequentist Wald 95% CI
    se = np.sqrt(p_hat*(1-p_hat)/n)
    f_lo, f_hi = p_hat - 1.96*se, p_hat + 1.96*se
    # Bayesian 95% credible interval (Beta-Binomial conjugate posterior)
    a, b = prior[0] + x, prior[1] + (n - x)
    b_lo, b_hi = stats.beta.ppf([0.025, 0.975], a, b)
    return (f_lo, f_hi), (b_lo, b_hi)

for n in [20, 200, 5000]:
    (fl, fh), (bl, bh) = compare(n)
    print(f"n={n:5d}  freq CI=[{fl:.3f},{fh:.3f}]   credible=[{bl:.3f},{bh:.3f}]   "
          f"gap={abs(fl-bl)+abs(fh-bh):.4f}")
# Gap between the two intervals shrinks toward 0 as n grows (Bernstein–von Mises).
```

At small n the prior and the boundary-respecting Beta posterior pull the credible interval away from the Wald CI; by n = 5000 the two intervals are nearly indistinguishable — the data overwhelm the prior and both procedures report essentially the same range, just with different sentences attached.

#### Q78. [Theory] What are confidence sequences and why are they the "correct" object for a peeking-prone, always-on experimentation platform?

A **confidence sequence** (CS) is a sequence of intervals (CIₜ) such that they *simultaneously* cover the true parameter at every time step with probability ≥ 1−α:

```text
Fixed-n CI:   P( θ ∈ CIₙ ) ≥ 1−α     for the ONE pre-chosen n.
Conf. sequence: P( θ ∈ CIₜ  for ALL t simultaneously ) ≥ 1−α.
```

The quantifier moved *inside*: coverage holds **uniformly over time**, so you can look at every CIₜ, stop at whichever t you like (a data-dependent τ), and the covered-with-prob-(1−α) guarantee survives. Contrast with a sequence of independent fixed-n CIs, where the chance that *some* of them misses the truth grows toward 1 — the same peeking pathology, now in interval form.

Why platforms want this: (1) PMs and dashboards inherently peek — a CS makes peeking *safe by construction* rather than forbidden; (2) it unifies estimation and stopping — you read the effect size and decide to stop from the *same* object, no separate alpha-spending bookkeeping; (3) it's built on the same supermartingale/e-process machinery as always-valid p-values (a CS is the set of θ₀ not rejected by an always-valid test), so it composes with e-BH multiplicity control. The cost is honest and explicit: a CS is **wider** than the fixed-n CI at any given n (it pays for anytime-validity), but it shrinks at the same √n rate and never lies about coverage no matter when or how often you look. For an always-on, peeking-prone program, that uniform-over-time guarantee — not the fixed-n CI — is the statistically correct object to report.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

Where Set 1 opened the mathematical black box, this set is the **on-call / war-room** companion: the experiment that *looks* fine but isn't, the dashboard that disagrees with the rerun, the metric that flipped after a logging change, the launch that regressed. Each question is a concrete situation an experimentation engineer or data scientist actually faces, with diagnostic checklists and runnable Python for the troubleshooting workflow. The lens is "the result is in front of you and something is wrong — what do you check, in what order, and how do you prove it?"

### 🟢 — extended

#### Q79. [Practical] Your control and treatment buckets came back 50.0% / 50.0% but the *metric* in control changed between two reruns of the same query. What's going on?

If the **assignment** is stable but the **metric value** for the same bucket shifts between reruns, the randomization is fine and the bug is downstream in the data layer. Walk the pipeline in order:

1. **Non-deterministic query.** A `LIMIT` without `ORDER BY`, a sampled table (`TABLESAMPLE`), a window function over a non-deterministic partition, or a `now()`/`current_date` reference that moves the analysis window between runs. Pin the window to explicit timestamps.
2. **Late-arriving / backfilled events.** Logs land with delay; the same query run an hour later sees more events for the same period. Confirm by checking whether row counts grew. Fix by waiting for a data-completeness watermark before reading.
3. **Mutable upstream tables.** A dimension table (user attributes, country) was updated, so a join now classifies users differently. Snapshot dimensions as-of assignment time.
4. **Deduplication differences.** If dedup depends on ingestion order, two runs can keep different duplicates. Make dedup deterministic (keep `MIN(event_id)`).

The tell is that the *split* is stable while the *measurement* drifts — that isolates the bug to metric computation, not assignment. Stable, reproducible analysis requires a frozen window, a completeness gate, and snapshotted dimensions.

#### Q80. [Coding] Write a reusable function that runs a two-proportion z-test and returns the lift, CI, and p-value in one call.

A single helper that every analysis can call avoids hand-rolled, error-prone math per experiment.

```python
import numpy as np
from scipy import stats
from dataclasses import dataclass

@dataclass
class ABResult:
    lift_abs: float        # treatment_rate - control_rate
    lift_rel: float        # relative lift
    ci_abs: tuple          # 95% CI on the absolute difference
    p_value: float
    significant: bool

def two_prop_test(c_conv, c_n, t_conv, t_n, alpha=0.05):
    pc, pt = c_conv / c_n, t_conv / t_n
    diff = pt - pc
    # SE of the difference of two independent proportions (unpooled, for the CI)
    se_ci = np.sqrt(pc*(1-pc)/c_n + pt*(1-pt)/t_n)
    # pooled SE for the test statistic (under H0 the rates are equal)
    p_pool = (c_conv + t_conv) / (c_n + t_n)
    se_test = np.sqrt(p_pool*(1-p_pool)*(1/c_n + 1/t_n))
    z = diff / se_test
    p = 2 * (1 - stats.norm.cdf(abs(z)))
    crit = stats.norm.ppf(1 - alpha/2)
    return ABResult(diff, diff/pc, (diff-crit*se_ci, diff+crit*se_ci), p, p < alpha)

print(two_prop_test(c_conv=800, c_n=20_000, t_conv=880, t_n=20_000))
# lift_abs≈0.004 (0.4pp), lift_rel≈0.10, CI excludes 0, p≈0.006, significant=True
```

Note the deliberate detail: the **CI uses unpooled** SE (estimating each rate separately) while the **test statistic uses pooled** SE (under H₀ the rates are equal). Mixing these up is a common subtle bug.

#### Q81. [Practical] A stakeholder says "the test has been running 3 days and it's already significant — let's ship." How do you respond?

Three days is almost always too short regardless of the p-value, for reasons that have nothing to do with the math being "wrong":

1. **Day-of-week incompleteness.** Three days can't contain a full weekly cycle; weekday and weekend users behave differently, so the estimate is biased toward whatever days you captured.
2. **Peeking.** If significance "already" appeared, you've likely been watching it cross the line — under a fixed-horizon test that inflates the false-positive rate well above 5%. The honest read of a fixed-horizon p-value requires looking **once**, at the pre-committed end.
3. **Novelty.** Early days overweight the novelty bump; the effect often decays.
4. **Sample size.** "Significant" doesn't mean "reached the planned n" — an early significant blip on a fraction of the sample can regress.

The constructive response: agree on the **pre-registered stop date and sample size**, show the effect-over-time plot (is it stable or drifting?), and if they genuinely need to stop early, switch to a **sequential / always-valid** method that is *designed* for early stopping. Frame waiting as buying down the risk of shipping noise and then rolling back.

#### Q82. [Coding] Given raw event logs, compute a per-user conversion table and run the test at the correct (user) granularity.

A frequent bug is testing at the event level when randomization was per user. Aggregate to the user first.

```python
import pandas as pd
from scipy import stats
import numpy as np

# events: one row per event; a user converts if they have >=1 'purchase'
events = pd.DataFrame({
    "user_id": [1,1,2,3,3,3,4,5,5],
    "variant": ["A","A","A","B","B","B","A","B","B"],
    "event":   ["view","purchase","view","view","view","purchase","view","view","purchase"],
})

# 1. collapse to ONE row per user (the randomization unit)
user = (events.assign(conv=events.event.eq("purchase"))
              .groupby(["user_id","variant"], as_index=False)["conv"].max())

# 2. test at the user level
a = user.loc[user.variant=="A","conv"]
b = user.loc[user.variant=="B","conv"]
ca, na, cb, nb = a.sum(), len(a), b.sum(), len(b)
p_pool = (ca+cb)/(na+nb)
se = np.sqrt(p_pool*(1-p_pool)*(1/na + 1/nb))
z = (b.mean() - a.mean())/se
print(f"A: {ca}/{na}  B: {cb}/{nb}  z={z:.2f}  p={2*(1-stats.norm.cdf(abs(z))):.3f}")
```

The collapse step (`groupby(user_id).max()`) is what keeps the analysis unit equal to the randomization unit. Skipping it treats each event as independent, inflates n, shrinks the SE artificially, and manufactures false positives.

#### Q83. [Practical] You launched a feature flag for an A/B test but the treatment group is showing exactly the control behavior. How do you debug?

The symptom — treatment metrics indistinguishable from control, often a *suspiciously* tiny effect — usually means the treatment isn't actually being delivered. Check, cheapest first:

1. **Is the flag wired to the code path?** Confirm the treatment branch is reached (add a counter/log on the treatment branch and verify non-zero volume).
2. **Exposure vs. assignment mismatch.** Users were *assigned* to treatment but never *exposed* (the code that reads the flag runs only on a page they didn't reach). Analyze on **triggered/exposed** users, not all-assigned — but verify the trigger fires.
3. **Caching / stale config.** CDN, client-side, or config cache is serving the old experience; the flag flips server-side but the user sees cached control. Check cache TTLs and client SDK refresh.
4. **Default-on bug.** The flag defaults to the control value when the SDK can't resolve it (network error, missing user_id), silently dumping treatment users into control behavior. Check SDK error/fallback rates.
5. **SRM the exposure logs.** If assignment is 50/50 but *exposure* is lopsided, that pins it to a delivery/trigger bug.

The fastest decisive test: instrument the treatment branch directly and confirm it executes for treatment users at the expected rate.

#### Q84. [Coding] Write a quick sanity-check report that flags an experiment for SRM, low power, and missing guardrails before anyone reads the result.

Automating the pre-read health checks stops bad experiments from being interpreted at all.

```python
from scipy import stats
import numpy as np

def health_check(n_control, n_treatment, expected_ratio=0.5,
                 baseline_rate=None, mde_rel=None, alpha=0.05, power=0.80,
                 guardrails=None):
    issues = []

    # 1. Sample-ratio mismatch (strict threshold)
    total = n_control + n_treatment
    exp = [total*(1-expected_ratio), total*expected_ratio]
    _, p_srm = stats.chisquare([n_control, n_treatment], exp)
    if p_srm < 0.001:
        issues.append(f"SRM: split {n_control}/{n_treatment}, p={p_srm:.1e} — DO NOT TRUST")

    # 2. Power / sample-size adequacy
    if baseline_rate and mde_rel:
        p1, p2 = baseline_rate, baseline_rate*(1+mde_rel)
        za, zb = stats.norm.ppf(1-alpha/2), stats.norm.ppf(power)
        pbar = (p1+p2)/2
        need = ((za*np.sqrt(2*pbar*(1-pbar)) + zb*np.sqrt(p1*(1-p1)+p2*(1-p2)))**2
                / (p2-p1)**2)
        if min(n_control, n_treatment) < need:
            issues.append(f"UNDERPOWERED: have {min(n_control,n_treatment):,}/group, "
                          f"need ~{int(np.ceil(need)):,}")

    # 3. Guardrails declared?
    if not guardrails:
        issues.append("NO GUARDRAILS declared — add latency/error/revenue counters")

    return issues or ["OK — passed health checks"]

print(health_check(50_120, 49_300, baseline_rate=0.10, mde_rel=0.05,
                   guardrails=["latency_p95"]))
```

Running this as a gate means analysts never even see results from a structurally broken experiment.

#### Q85. [Practical] How do you sanity-check that your randomization is actually random before trusting any experiment?

Randomization bugs are silent and catastrophic, so verify it independently of the metric:

1. **SRM check** (chi-square) that the overall split matches the intended ratio at a strict threshold.
2. **Pre-experiment balance / A/A on covariates:** compare the two arms on **pre-period** attributes (tenure, prior spend, country, device) — they should be statistically indistinguishable. A pre-period difference means the buckets weren't exchangeable.
3. **Hash uniformity:** plot the distribution of `hash(user_id, salt) mod N` — it should be flat across buckets. A spike or gap reveals a bad hash or modulo bias.
4. **Independence across experiments:** the same user's bucket in experiment X should be uncorrelated with their bucket in experiment Y (unless they share a layer). Correlation means salts collide.
5. **Continuous A/A:** keep a permanent A/A experiment running; its false-positive rate over time should hover near α. Persistent over-rejection means the pipeline (variance estimation, logging, assignment) is broken.

If pre-period covariates are imbalanced, *stop* — no amount of clever analysis fixes a broken randomization.

### 🟡 — extended

#### Q86. [Practical] Your primary metric is flat but a secondary metric is "significant" at p = 0.03. The PM wants to ship on the secondary. What do you say?

This is the classic multiple-comparisons / metric-shopping trap. Reason through it:

1. **The primary was pre-registered as the decision metric** precisely so you don't go fishing. A flat primary is the headline result.
2. **How many secondaries did you test?** If you scanned 15 secondary metrics, the chance that *at least one* hits p < 0.05 by pure chance is `1 − 0.95¹⁵ ≈ 54%`. Apply **Benjamini–Hochberg** across the secondary panel; p = 0.03 may not survive.
3. **Was this secondary a pre-stated hypothesis or discovered after the fact?** Post-hoc "significant" secondaries are hypotheses, not conclusions.
4. **Plausibility / mechanism:** is there a causal story for why the change would move *this* metric and not the primary? Absent one, suspicion rises.

Constructive path: treat the secondary finding as a **hypothesis for a new, pre-registered confirmatory experiment** with that metric as primary and proper power. Shipping on an uncorrected, possibly-cherry-picked secondary is how teams ship noise.

#### Q87. [Coding] Two experiment reports disagree on the same metric. Write code to reconcile them by checking population, window, and dedup.

A reconciliation harness makes "the numbers don't match" debuggable instead of a guessing game.

```python
import pandas as pd

def reconcile(df_a, df_b, key="user_id"):
    """df_a, df_b: each a per-user frame with [user_id, metric]. Diagnose the gap."""
    sa, sb = set(df_a[key]), set(df_b[key])
    report = {
        "n_a": len(df_a), "n_b": len(df_b),
        "dupes_a": int(df_a[key].duplicated().sum()),   # dedup bug?
        "dupes_b": int(df_b[key].duplicated().sum()),
        "only_in_a": len(sa - sb),                       # population/window diff?
        "only_in_b": len(sb - sa),
        "in_both": len(sa & sb),
    }
    # for users in BOTH, do the metric values agree? (computation diff?)
    m = df_a.drop_duplicates(key).merge(
        df_b.drop_duplicates(key), on=key, suffixes=("_a", "_b"))
    report["value_mismatches"] = int((m["metric_a"] != m["metric_b"]).sum())
    return report

# Typical findings:
#  only_in_a/only_in_b > 0      -> different time window or population filter
#  dupes_* > 0                  -> a report failed to dedup to user grain
#  value_mismatches > 0 but
#    same membership            -> different metric definition / aggregation
print(reconcile(
    pd.DataFrame({"user_id":[1,2,3],   "metric":[1,0,1]}),
    pd.DataFrame({"user_id":[1,2,3,4], "metric":[1,0,1,1]})))
# only_in_b=1 (user 4) -> the reports cover different populations/windows
```

The discipline: decompose the discrepancy into **membership** differences (population/window/filter), **grain** differences (dedup), and **value** differences (metric definition) — almost every "numbers don't match" reduces to one of those three.

#### Q88. [Practical] A guardrail metric (latency) regressed but the primary metric won. Walk through the ship/no-ship decision.

A guardrail breach is exactly the scenario guardrails exist for; do not auto-ship on a primary win.

1. **Is the regression real or noise?** Check the latency CI — does it exclude the no-harm tolerance band, or is it within noise? A non-significant blip on a guardrail isn't a breach.
2. **Magnitude vs. tolerance.** Guardrails are usually **non-inferiority** checks: "no worse than X." Did it cross the pre-agreed tolerance (e.g. p95 latency +5ms is fine, +50ms is not)?
3. **Mechanism.** Is the latency hit *caused by* the treatment (new network call, heavier render)? If so it's structural and won't disappear.
4. **Net trade-off.** Quantify both sides in comparable terms (e.g. revenue lift vs. the known latency→abandonment elasticity). Sometimes a small latency cost is worth a large primary win; often it isn't.
5. **Mitigation path.** Can you ship the primary win *and* fix the latency (optimize the new call, lazy-load)? Re-test the mitigated version.

Default posture: a confirmed guardrail breach beyond tolerance **blocks the ship** until mitigated, even with a winning primary — because the guardrail encodes a harm the org pre-committed to refusing.

#### Q89. [Coding] Implement a sequential / always-valid p-value (mSPRT-style) so a dashboard can be safely peeked.

A mixture sequential probability ratio test gives an always-valid p-value: you can evaluate it at every step and the Type I rate stays ≤ α.

```python
import numpy as np

def msprt_pvalue(x_control, x_treatment, tau2=1.0):
    """Always-valid p-value for a difference in means (known-variance approx).
    tau2 is the mixing-prior variance over the effect size."""
    nc, nt = len(x_control), len(x_treatment)
    diff = x_treatment.mean() - x_control.mean()
    # pooled variance estimate of the difference
    s2 = x_control.var(ddof=1)/nc + x_treatment.var(ddof=1)/nt
    s2 = max(s2, 1e-12)
    n_eff = 1.0 / s2                       # information ~ 1/Var(diff)
    # mSPRT mixture likelihood ratio under N(0, tau2) prior on the effect
    lr = np.sqrt(s2 / (s2 + tau2)) * np.exp(
        (diff**2) / (2 * s2 * (1 + s2/tau2)))
    p_anytime = min(1.0, 1.0 / lr)         # always-valid p-value
    return p_anytime, diff

rng = np.random.default_rng(0)
c = rng.normal(0, 1, 5000)
t = rng.normal(0.05, 1, 5000)             # tiny true effect
p, d = msprt_pvalue(c, t)
print(f"diff={d:.3f}  always-valid p={p:.3f}  (safe to peek at any n)")
```

Because the likelihood ratio is a non-negative martingale under H₀, `1/LR` is an always-valid p-value — Ville's inequality guarantees `P(ever ≤ α) ≤ α`, so stopping the first time it drops below α controls Type I error despite unlimited peeking. The price is that it's more conservative than a fixed-horizon test at the planned n.

#### Q90. [Practical] You ran an A/A test (treatment is identical to control) and got p = 0.01. Is the platform broken?

Not necessarily — and overreacting either way is the mistake.

1. **One A/A hitting p < 0.05 is expected.** Under a correct pipeline, A/A p-values are **Uniform(0,1)**, so ~5% land below 0.05 and ~1% below 0.01. A single low p-value is consistent with a healthy engine.
2. **The right test is the distribution, not one draw.** Run **many** A/A tests (or many metrics/segments) and check that the p-values are uniform and the false-positive rate ≈ α. *Persistent* over-rejection (say 15% below 0.05) is the red flag.
3. **If A/A genuinely over-rejects,** the usual culprits are **understated variance** — per-event variance on a clustered/ratio metric (needs delta-method/clustered SEs), or treating correlated repeated observations as independent. Less commonly: a logging bug that correlates with bucket, or a non-50/50 split.

So: don't declare the platform broken on one A/A; *do* investigate if the **rate** of false positives across many A/A runs exceeds α, and look first at variance estimation.

#### Q91. [Coding] Detect a novelty effect by plotting the cumulative treatment effect over time and testing for a trend.

A decaying day-by-day effect is the signature of novelty; quantify it instead of eyeballing.

```python
import numpy as np
import pandas as pd
from scipy import stats

# daily per-arm means over a 14-day experiment
rng = np.random.default_rng(1)
days = np.arange(1, 15)
# simulate a novelty effect: starts at +0.06, decays toward +0.01
true_daily = 0.01 + 0.05*np.exp(-days/4)
daily_effect = true_daily + rng.normal(0, 0.008, len(days))

df = pd.DataFrame({"day": days, "effect": daily_effect})

# 1. cumulative (what a naive final read reports) vs daily (the truth)
df["cumulative"] = df["effect"].expanding().mean()

# 2. test for a downward trend in the DAILY effect (novelty => negative slope)
slope, intercept, r, p_trend, se = stats.linregress(df["day"], df["effect"])
print(df.round(4).to_string(index=False))
print(f"\ntrend slope={slope:.4f}/day  p={p_trend:.4f}  "
      f"{'NOVELTY: effect is decaying' if slope<0 and p_trend<0.05 else 'stable'}")
```

A significantly negative slope on the *daily* effect — even while the *cumulative* number still looks positive — flags novelty. The fix is to read the **stabilized late-period** effect (or a long-term holdback), not the cumulative average that's inflated by the early bump.

#### Q92. [Practical] Mid-experiment, you discover a logging bug that under-counted conversions for the first 4 days. What now?

A mid-flight data-integrity break forces a containment-then-recovery decision; do not just "patch and continue silently."

1. **Characterize the bug.** Was the undercount **differential** (hit treatment and control unequally) or **symmetric**? Differential corruption biases the effect and is far more serious than symmetric noise.
2. **Is it fixable retroactively?** If the raw events still exist and only the aggregation was wrong, **backfill** and recompute — the experiment may be salvageable.
3. **If unrecoverable, discard the contaminated window.** Restart the clock from the fix, re-derive the required duration, and treat the pre-fix data as untrustworthy. Do **not** stitch corrupted and clean periods together.
4. **Re-validate randomization** post-fix (SRM, pre-period balance) since logging bugs often co-occur with assignment bugs.
5. **Document and pre-register the new analysis window** so the restart isn't itself a form of peeking ("we kept going until it looked good").

The principle: integrity beats schedule. A clean shorter experiment is worth more than a longer contaminated one, and the most dangerous case is the *differential* bug that quietly biases the estimate rather than just adding noise.

#### Q93. [Coding] Estimate experiment runtime from required sample size and daily eligible traffic, rounding up to whole weeks.

Translating sample size into a calendar date is where planning becomes actionable.

```python
import numpy as np
from scipy import stats

def runtime_days(p_baseline, mde_rel, daily_eligible, n_arms=2,
                 alpha=0.05, power=0.80):
    p1, p2 = p_baseline, p_baseline*(1+mde_rel)
    za, zb = stats.norm.ppf(1-alpha/2), stats.norm.ppf(power)
    pbar = (p1+p2)/2
    n_per_arm = ((za*np.sqrt(2*pbar*(1-pbar)) +
                  zb*np.sqrt(p1*(1-p1)+p2*(1-p2)))**2 / (p2-p1)**2)
    total_needed = int(np.ceil(n_per_arm)) * n_arms
    raw_days = total_needed / daily_eligible
    # never shorter than a full week; round UP to whole weeks for day-of-week balance
    weeks = max(1, int(np.ceil(raw_days / 7)))
    return {
        "n_per_arm": int(np.ceil(n_per_arm)),
        "total_needed": total_needed,
        "raw_days": round(raw_days, 1),
        "recommended_weeks": weeks,
        "recommended_days": weeks * 7,
    }

print(runtime_days(p_baseline=0.10, mde_rel=0.05, daily_eligible=8000))
# raw ~7.8 days -> rounds up to 2 full weeks for clean weekly cycles
```

Rounding *up to whole weeks* (not just to the raw day count) is the practical detail that keeps every weekday equally represented and avoids day-of-week bias in the final read.

### 🟠 — extended

#### Q94. [Practical] A new experiment shows a huge, implausible lift (+40% on a mature metric). What's your debugging order?

A too-good-to-be-true result is almost always a bug, not a breakthrough. Be most suspicious of the biggest wins. Check in order of likelihood:

1. **Analysis-unit / variance bug.** Counting events as independent users, or per-event variance on a ratio metric, can manufacture both a huge point estimate and a tiny p-value. Re-aggregate to the randomization unit.
2. **Dilution / trigger asymmetry.** If the control denominator includes unexposed users but the treatment denominator doesn't (or vice versa), the rate comparison is apples-to-oranges. Align the **triggered/exposed** population on both arms.
3. **SRM / leakage.** Check the split; a broken hash can route a non-random (e.g. high-intent) slice into treatment.
4. **Metric definition asymmetry.** Does the metric accidentally reference the treatment (e.g. counting a treatment-only event as a conversion)? This is "endogenous metric" — the treatment *defines* the win.
5. **Outliers / bots.** A handful of extreme users or unfiltered bots concentrated in one arm. Winsorize/cap and re-run; check the effect with and without the top 0.1%.
6. **Time window misalignment** between arms.

The mindset: extraordinary effects demand extraordinary verification — replicate, decompose by segment, and find the *mechanism* before believing it.

#### Q95. [Coding] Diagnose whether a significant result is driven by a few outliers using a capping/winsorization sensitivity check.

If a "win" evaporates after capping extreme values, it was outlier-driven, not a real shift in the typical user.

```python
import numpy as np
from scipy import stats

rng = np.random.default_rng(3)
control   = rng.exponential(10, 5000)
treatment = rng.exponential(10, 5000)
# inject a few whales into treatment that fake a lift
treatment[:5] += 5000

def welch(c, t):
    diff = t.mean() - c.mean()
    se = np.sqrt(c.var(ddof=1)/len(c) + t.var(ddof=1)/len(t))
    return diff, 2*(1-stats.norm.cdf(abs(diff/se)))

def cap(x, q=0.99):
    hi = np.quantile(np.r_[control, treatment], q)
    return np.minimum(x, hi)

d_raw, p_raw = welch(control, treatment)
d_cap, p_cap = welch(cap(control), cap(treatment))
print(f"raw    : diff={d_raw:8.3f}  p={p_raw:.4f}")
print(f"capped : diff={d_cap:8.3f}  p={p_cap:.4f}")
# diff collapses and p jumps after capping -> the 'win' was 5 whales, not a real effect
```

Always report the result **with and without** outlier treatment. If the conclusion flips, the headline effect lives in the tail — decide deliberately whether those whales are signal (real high-value behavior) or noise (bots, fat-finger orders) rather than letting them silently drive the decision.

#### Q96. [Practical] How do you analyze an experiment where users can enter at different times (staggered rollout) and have unequal exposure lengths?

Unequal exposure breaks the naive "compare totals" approach because late joiners have had less time to convert, and if entry timing differs between arms it confounds the comparison.

1. **Align on exposure time, not calendar time.** Measure each user's metric over a fixed window *since their first exposure* (e.g. "7-day conversion") rather than total-to-date, so everyone is compared over an equal-length window.
2. **Watch for survivorship/maturation.** Users who joined yesterday can't have a 7-day outcome yet — exclude immature users or use a time-to-event (survival) model that handles **censoring** explicitly (Kaplan–Meier, Cox).
3. **Check entry-time balance.** If treatment users systematically entered earlier/later than control (e.g. a ramped rollout), entry time is a confounder — stratify or adjust for cohort.
4. **Cohort the analysis.** Bucket users by entry week and verify the effect is consistent across cohorts; a per-cohort effect that's stable is trustworthy, one that drifts signals a time confound.

The core fix is the **per-user clock**: standardize the measurement window relative to each user's exposure start, and use survival methods when outcomes are still maturing.

#### Q97. [Coding] Use the delta method to compute a correct CI for a ratio metric (CTR) randomized by user, and contrast with the naive per-impression CI.

The naive per-impression interval is too narrow because impressions within a user are correlated; the user-level delta method fixes it.

```python
import numpy as np
from scipy import stats

rng = np.random.default_rng(11)
n_users = 4000
imps  = rng.poisson(20, n_users) + 1          # impressions per user
clicks = rng.binomial(imps, 0.10)             # ~10% CTR, correlated within user

X, Y = clicks, imps                            # numerator, denominator per user
mx, my = X.mean(), Y.mean()
ratio = X.sum() / Y.sum()                       # overall CTR

# delta-method variance of the user-level ratio mean
n = len(X)
var_delta = (1/my**2) * (X.var(ddof=1)
            - 2*(mx/my)*np.cov(X, Y, ddof=1)[0,1]
            + (mx/my)**2 * Y.var(ddof=1)) / n
se_delta = np.sqrt(var_delta)

# naive per-impression SE (WRONG: ignores within-user correlation)
N = Y.sum()
se_naive = np.sqrt(ratio*(1-ratio)/N)

z = stats.norm.ppf(0.975)
print(f"CTR={ratio:.4f}")
print(f"delta CI : [{ratio-z*se_delta:.4f}, {ratio+z*se_delta:.4f}]  se={se_delta:.5f}")
print(f"naive CI : [{ratio-z*se_naive:.4f}, {ratio+z*se_naive:.4f}]  se={se_naive:.5f}")
# the naive interval is much narrower -> false confidence, more false positives
```

The naive SE treats `N` correlated impressions as `N` independent Bernoulli trials and badly understates uncertainty. The delta-method SE, computed at the **user** level, accounts for the clustering and is the correct interval to report.

#### Q98. [Practical] Two experiments running simultaneously seem to interact — one's result changed when the other launched. How do you handle interaction between concurrent experiments?

Concurrency interaction is real but usually manageable with the right design; diagnose whether it's structural overlap or true effect interaction.

1. **Are they in the same layer / mutually exclusive?** Well-run platforms put potentially-interacting experiments in a **mutually exclusive layer** so no user is in both. If they overlapped, that's the first fix.
2. **Orthogonal randomization.** If overlap is allowed, assignments should be independent (different salts), so each experiment's traffic is balanced across the other's arms — the other experiment becomes *noise that cancels*, not bias. Verify the cross-tab is balanced.
3. **Test for interaction explicitly.** Fit `metric ~ A + B + A:B` and inspect the **interaction term**. A significant `A:B` means the effects aren't additive (e.g. two UI changes competing for the same screen real estate).
4. **If interaction is real and matters,** run a combined factorial experiment or sequence them. For most independent changes, interaction is negligible and orthogonal assignment suffices.

The key insight: with orthogonal assignment, concurrent experiments don't *bias* each other's average effects — they only add variance. Apparent interaction is often actually overlap leakage or just noise; confirm with the explicit interaction term before redesigning.

#### Q99. [Coding] Run CUPED with a pre-period covariate and report the realized variance reduction and effective sample-size gain.

Quantifying the variance reduction tells you how much runtime CUPED actually bought.

```python
import numpy as np

rng = np.random.default_rng(5)
n = 10_000
x_pre = rng.normal(100, 20, 2*n)               # pre-period covariate
y = 0.7*x_pre + rng.normal(0, 15, 2*n)         # in-experiment metric (rho~0.68)
group = np.r_[np.zeros(n), np.ones(n)]
y[group==1] += 2.0                              # +2 true effect

theta = np.cov(y, x_pre, ddof=1)[0,1] / x_pre.var(ddof=1)
y_cuped = y - theta*(x_pre - x_pre.mean())

def se_diff(m):
    t, c = m[group==1], m[group==0]
    return np.sqrt(t.var(ddof=1)/len(t) + c.var(ddof=1)/len(c))

se_raw, se_cuped = se_diff(y), se_diff(y_cuped)
var_reduction = 1 - (se_cuped/se_raw)**2
# variance ~ 1/n, so a (1-r) variance factor is like (1/(1-r))x the sample
eff_n_gain = 1/(1-var_reduction)
rho = np.corrcoef(y, x_pre)[0,1]
print(f"rho(Y,X)={rho:.3f}  theory reduction=1-rho^2={1-rho**2:.3f}... "
      f"wait, reduction={var_reduction:.3f}")
print(f"SE: raw={se_raw:.4f} -> cuped={se_cuped:.4f}")
print(f"variance reduction={var_reduction:.1%}  ~ like {eff_n_gain:.2f}x the users")
```

The realized variance reduction is approximately `ρ²` (the squared pre/post correlation), and because variance scales as `1/n`, a 40% reduction is equivalent to running with ~1.7× the traffic — pure power for free, since the pre-period covariate is unaffected by treatment and the estimate stays unbiased.

#### Q100. [Practical] Post-launch, the metric that won the experiment is flat in production. List the candidate explanations and how you'd test each.

A win that doesn't replicate at launch is a known, recurring failure mode; enumerate causes and a discriminating test for each:

| Candidate cause | How to test it |
|---|---|
| **Novelty effect** (early bump decayed) | Effect-over-time plot in the experiment; long-term holdback now |
| **Primacy/learning** masked it, or it's still ramping | Watch the production trend for a delayed rise |
| **Understated variance** made noise look real | Re-run analysis with delta-method/clustered SEs |
| **Peeking** — shipped on an early lucky look | Check whether the stop was at the pre-registered n |
| **Dilution at launch** (100% rollout exposes different mix) | Compare experiment vs. launch population composition |
| **Seasonality** — experiment ran in an unrepresentative window | Compare the experiment window to the launch window |
| **Implementation drift** — shipped code ≠ experiment code | Diff the experiment branch vs. the launched build |
| **Interaction** with another change shipped concurrently | Look for other launches in the same window/surface |

The structured move is to **keep a long-term holdback** so you can directly measure persistence, and to maintain an experiment registry so "shipped but flat" cases feed back into platform fixes (mandatory holdbacks, correct variance, no peeking) rather than being relitigated each time.

### 🔴 — extended

#### Q101. [Practical] You're asked to estimate the long-term (1-year) impact of a feature you can only experiment on for 3 weeks. How do you approach it?

You can't directly observe a year in three weeks, so you bridge the gap with surrogates and holdbacks, and you're explicit about the assumptions.

1. **Long-term holdback.** Keep a small slice of users in control after launch and keep measuring for months. This is the most direct evidence and catches decay/novelty — accept that the *full* answer arrives later.
2. **Validated surrogate metrics.** Use historical experiments to find a short-term proxy that **provably predicts** the long-term north star (e.g. week-1 engagement → 1-year retention). Ship on the surrogate only once the surrogate→outcome link is established and stable, and re-validate it periodically (surrogates drift).
3. **Effect-trajectory modeling.** Fit the day-by-day effect curve and extrapolate cautiously — a stable plateau extrapolates more safely than a still-moving curve. Treat extrapolation as a hypothesis with wide error bars, not a point estimate.
4. **Guardrails on long-term-adjacent metrics** (retention, churn signals) so a short-term win that cannibalizes the long term is caught early.

The expert framing: optimize for **long-term user value** using short-term metrics as *instruments*, continuously check the instruments still point the same way, and be honest that the 1-year number is a model-based estimate carrying its own uncertainty — pair it with an actual holdback to ground-truth it.

#### Q102. [Coding] Build a small simulation harness to measure your platform's actual false-positive rate under peeking, with and without a sequential correction.

The decisive way to prove a peeking problem (and a fix) is to simulate under a true null and count rejections.

```python
import numpy as np
from scipy import stats

def fpr_under_peeking(n_sims=2000, n_max=5000, peek_every=250,
                      sequential=False, alpha=0.05, tau2=1.0, seed=0):
    rng = np.random.default_rng(seed)
    rejections = 0
    for _ in range(n_sims):
        c = rng.normal(0, 1, n_max)
        t = rng.normal(0, 1, n_max)            # TRUE NULL: no effect
        rejected = False
        for k in range(peek_every, n_max+1, peek_every):
            cc, tt = c[:k], t[:k]
            diff = tt.mean() - cc.mean()
            s2 = cc.var(ddof=1)/k + tt.var(ddof=1)/k
            if sequential:                      # mSPRT always-valid p-value
                lr = np.sqrt(s2/(s2+tau2))*np.exp(diff**2/(2*s2*(1+s2/tau2)))
                p = min(1.0, 1.0/lr)
            else:                               # naive fixed-horizon p, peeked
                p = 2*(1-stats.norm.cdf(abs(diff/np.sqrt(s2))))
            if p < alpha:
                rejected = True
                break                           # stop at first 'win'
        rejections += rejected
    return rejections / n_sims

print(f"naive peeking FPR      : {fpr_under_peeking(sequential=False):.1%}")
print(f"sequential (mSPRT) FPR : {fpr_under_peeking(sequential=True):.1%}")
# naive: well above 5% (peeking inflates it); sequential: stays near/below 5%
```

This harness is also how you *audit* a platform: run it on your real inference code under a simulated null. If the naive peeked FPR is ~15–25% while the sequential one holds near 5%, you have a quantified, reproducible case for mandating always-valid inference on monitored dashboards.

#### Q103. [Practical] Leadership wants a single "experiment scorecard" number to decide ship/no-ship. What are the dangers and how do you design it responsibly?

Collapsing a multi-dimensional result into one number is convenient but easy to weaponize; design it with the failure modes in mind.

1. **A single number hides guardrail breaches.** A composite that nets a primary win against a latency regression can greenlight a harmful change. **Keep guardrails as separate hard gates**, not summands — the scorecard ships only if *all* guardrails pass *and* the primary clears its bar.
2. **Effect size + uncertainty, not just significance.** The scorecard must encode the **CI / expected loss**, not a binary p < 0.05 — a barely-significant trivial lift shouldn't score the same as a large robust one.
3. **Pre-registered weights.** If it combines metrics, fix the weights *before* seeing results, or it becomes a metric-shopping engine.
4. **Decision-theoretic framing.** A Bayesian **expected loss / probability-of-being-best** is a more honest single number than a p-value because it directly answers "what do we risk by shipping?"
5. **Health flags travel with it.** SRM, power, and peeking status must be surfaced alongside the number, so a "win" from a broken experiment can't be read at face value.

The responsible design is a **gated** scorecard: hard guardrail/health gates first, then an effect-size-and-uncertainty summary (ideally expected loss) with pre-committed weights — never a single scalar that can silently trade harm against benefit.

#### Q104. [Coding] Implement a heterogeneous-treatment-effect check that flags a segment where the treatment hurts, with multiplicity control.

Surfacing "wins overall but hurts segment X" responsibly requires testing segments *and* correcting for the many comparisons.

```python
import numpy as np
import pandas as pd
from scipy import stats
from statsmodels.stats.multitest import multipletests

rng = np.random.default_rng(9)
n = 12_000
seg = rng.choice(["new","returning","power","mobile","desktop"], n)
group = rng.integers(0, 2, n)
base = {"new":0.20,"returning":0.25,"power":0.40,"mobile":0.22,"desktop":0.30}
# treatment helps everyone EXCEPT 'returning', where it hurts
lift = {"new":0.03,"returning":-0.04,"power":0.02,"mobile":0.02,"desktop":0.03}
p_true = np.array([base[s] + (lift[s] if g else 0) for s,g in zip(seg, group)])
y = rng.binomial(1, np.clip(p_true,0,1))
df = pd.DataFrame({"seg":seg,"group":group,"y":y})

rows = []
for s, sub in df.groupby("seg"):
    a, b = sub[sub.group==0].y, sub[sub.group==1].y
    diff = b.mean() - a.mean()
    se = np.sqrt(a.var(ddof=1)/len(a) + b.var(ddof=1)/len(b))
    z = diff/se
    rows.append((s, diff, 2*(1-stats.norm.cdf(abs(z)))))
res = pd.DataFrame(rows, columns=["seg","effect","p_raw"])
res["p_bh"] = multipletests(res.p_raw, method="fdr_bh")[1]   # control FDR
res["FLAG_HARM"] = (res.effect < 0) & (res.p_bh < 0.05)
print(res.round(4).to_string(index=False))
# 'returning' should surface with a negative effect surviving FDR correction
```

The two non-negotiables: apply **FDR (or FWER) correction** across the segment panel so you don't flag noise, and **validate** any flagged harmful segment on held-out data or a confirmatory experiment before acting — subgroup effects are the single biggest source of false discoveries in experimentation.

#### Q105. [Practical] An experiment is "stuck" — it can't reach the required sample size in a reasonable time because traffic is limited. What are all your levers?

When traffic is the binding constraint, you trade off the four power levers and the experiment design itself:

1. **Reduce variance (free power).** Apply **CUPED / CUPAC** with pre-period covariates — often a 30–50% variance cut, equivalent to that much more traffic at zero extra cost. Use a less noisy metric or a better-defined OEC.
2. **Raise the MDE.** Be honest about the smallest effect worth shipping; detecting a 1% lift instead of 0.5% cuts the required n by ~4×. Often the tiny effect wasn't decision-relevant anyway.
3. **Trade off α / power.** For a low-risk reversible change, accept 90% power instead of 95%, or a looser α — explicitly, with eyes open about the added error risk.
4. **Increase allocation / eligible traffic.** Ramp treatment to a larger share, broaden eligibility, or remove unnecessary targeting filters (if it doesn't change the population of interest).
5. **Switch design.** A **paired / within-subject** or **switchback** design can be far more efficient by removing between-user variance; a **bandit** reduces regret if you mainly need to pick a winner, not estimate a clean effect.
6. **Accept a different inference.** A **sequential** test can stop early on a large effect; a **Bayesian** read with an informative (defensible) prior can reach a decision sooner.

The framing for leadership: each lever trades something (sensitivity, error rate, generality, or a clean unbiased estimate) for speed — make that trade **explicitly** rather than quietly running an underpowered fixed test and over-reading whatever comes out.

#### Q106. [Behavioral] Tell me about a time you had to debug a trusted experimentation result that turned out to be a data-pipeline artifact, not a real effect.

Frame it as rigorous diagnosis turned into a systemic fix (STAR):

- **Situation:** a headline experiment reported a large, celebrated lift that a skeptical reviewer (me) found implausibly large for a mature metric.
- **Task:** determine whether the effect was real before the org committed engineering to a full rollout.
- **Action:** I worked the pipeline in order — re-aggregated to the **user** grain (the original analysis had counted events independently), checked **SRM** (clean), and aligned the **triggered/exposed** populations across arms. The smoking gun was an **exposure asymmetry**: the control denominator included users who never reached the surface, while treatment's didn't, inflating the rate comparison. Recomputing on a symmetric exposed population shrank the lift to within noise, and a **delta-method** SE on the ratio metric confirmed the original CI had been far too narrow.
- **Result:** we did not ship the phantom win, avoiding a likely rollback. I turned the lesson into platform changes: enforced exposed-population alignment, delta-method variance for all ratio metrics, and an automated "implausibly large effect" flag that triggers mandatory re-validation. The durable principle I push: **a surprising result is a hypothesis about a bug until proven otherwise**, and the fix belongs in the platform so the next analyst can't repeat it.

The interviewer is checking for a disciplined, ordered debugging method, correct statistical diagnosis (grain, exposure, variance), intellectual honesty in killing a popular result, and the instinct to convert a one-off catch into a systemic guardrail.

#### Q107. [Coding] Build a triage script that ingests an experiment's summary stats and emits a ranked list of likely problems with severity.

A single triage entry point turns ad-hoc debugging into a repeatable, ordered checklist.

```python
from scipy import stats
import numpy as np

def triage(exp):
    """exp: dict of experiment summary stats. Returns issues sorted by severity."""
    issues = []  # (severity 0-3, message)

    # --- BLOCKERS (severity 3): result is untrustworthy outright ---
    total = exp["n_c"] + exp["n_t"]
    _, p_srm = stats.chisquare([exp["n_c"], exp["n_t"]],
                               [total*0.5, total*0.5])
    if p_srm < 0.001:
        issues.append((3, f"SRM (p={p_srm:.1e}): split {exp['n_c']}/{exp['n_t']} — quarantine"))
    if exp.get("exposed_c_frac", 1) and exp.get("exposed_t_frac", 1):
        if abs(exp["exposed_c_frac"] - exp["exposed_t_frac"]) > 0.05:
            issues.append((3, "Exposure asymmetry between arms — align triggered population"))

    # --- HIGH (severity 2): biases or invalidates the read ---
    if exp.get("peeked") and not exp.get("sequential"):
        issues.append((2, "Peeked with a fixed-horizon test — FPR inflated, use sequential"))
    if exp.get("ratio_metric") and not exp.get("delta_method"):
        issues.append((2, "Ratio metric without delta-method SE — variance understated"))
    if exp.get("days", 99) < 7:
        issues.append((2, f"Ran only {exp.get('days')}d (<1 week) — day-of-week bias"))

    # --- MEDIUM (severity 1): weakens confidence ---
    if exp.get("n_secondary_metrics", 0) > 1 and not exp.get("fdr_corrected"):
        issues.append((1, f"{exp['n_secondary_metrics']} secondary metrics, no FDR correction"))
    if exp.get("min_n") and exp.get("required_n") and exp["min_n"] < exp["required_n"]:
        issues.append((1, f"Underpowered: {exp['min_n']:,} < required {exp['required_n']:,}"))

    issues.sort(key=lambda x: -x[0])
    sev = {3: "BLOCKER", 2: "HIGH", 1: "MEDIUM"}
    return [f"[{sev[s]}] {m}" for s, m in issues] or ["[OK] no issues detected"]

example = {"n_c": 50_120, "n_t": 49_300, "ratio_metric": True, "delta_method": False,
           "peeked": True, "sequential": False, "days": 4,
           "n_secondary_metrics": 12, "fdr_corrected": False}
print("\n".join(triage(example)))
```

The value is the **ordering by severity**: a BLOCKER (SRM, exposure asymmetry) means *don't read the result at all*, while MEDIUM issues (uncorrected secondaries, slight underpowering) only qualify interpretation. Encoding this triage as code makes the on-call response consistent across analysts instead of dependent on who happens to look.

#### Q108. [Practical] How do you run a credible experimentation program when you genuinely cannot randomize (regulatory, ethical, or all-or-nothing rollouts)?

When an RCT is off the table you fall back to **quasi-experimental** causal inference, choosing the weakest-assumption design the situation supports and stress-testing it:

1. **Difference-in-differences (DiD):** compare the pre/post change in a treated unit against the change in an untreated comparison, differencing out fixed differences. Hinges on the **parallel-trends** assumption — verify the two groups moved together *before* the intervention, and run placebo tests on pre-period "fake" interventions.
2. **Regression discontinuity (RDD):** when treatment is assigned by a threshold on a running variable (a credit cutoff, a rank), units just above vs. just below the cutoff are quasi-random. Strong local validity; only estimates the effect *at the threshold*.
3. **Interrupted time series / synthetic control:** model the counterfactual for a single treated unit (a country, a market) as a weighted combination of untreated donor units, then read the gap post-intervention. Good for one-off all-or-nothing rollouts.
4. **Instrumental variables:** exploit a source of variation that affects treatment but not the outcome directly. Powerful but the exclusion restriction is **untestable** — defend it on domain grounds.

The expert discipline: every one of these rests on an **identifying assumption that randomization would have given you for free** (parallel trends, valid threshold, valid instrument), and those assumptions are largely untestable — so you triangulate across methods, run sensitivity/placebo analyses, report how strong an unmeasured confounder would have to be to overturn the result (an E-value-style bound), and communicate the conclusion as **weaker and more assumption-laden** than an RCT would be. Honesty about the assumption ledger is what makes an observational program credible rather than a rationalization engine.

## ✅ Key Takeaways

- **Inference is about uncertainty:** the CLT makes sample means normal, the **standard error (σ/√n)** quantifies their wobble, and CIs/p-values turn that into decisions — always report **effect size + CI**, never a p-value alone.
- **A p-value is P(data | H₀)**, not the probability the null is true; **statistical significance ≠ practical significance**, especially at large n.
- **A/B tests earn causation through randomization**, which balances even unmeasured confounders — the thing observational adjustment can never fully do.
- **Plan before you peek:** pick one primary metric, set α/power, compute sample size from the **MDE**, choose the right **randomization unit**, set **guardrails**, and run a full cycle.
- **Defend against the classic traps:** peeking (use sequential/always-valid tests), multiple comparisons (Bonferroni/FDR), SRM (chi-square gate), novelty/primacy (run longer, segment), ratio-metric variance (delta method), and Simpson's paradox (check confounders).
- **CUPED and variance reduction** buy power for free by exploiting pre-experiment data.

## ⚠️ Common Pitfalls

- Confusing **standard deviation** (spread of data) with **standard error** (spread of an estimate).
- Interpreting a 95% CI as "95% probability the truth is in this interval" — that's the **Bayesian credible-interval** reading, not the frequentist one.
- **Peeking** at a fixed-horizon test and stopping at first significance, inflating false positives to 30–50%.
- Chasing tiny, **practically meaningless effects** that are "significant" only because n is huge.
- **Mismatching randomization and analysis units** (randomize by user, compute variance per event) → understated variance, false positives. Use clustered/delta-method SEs.
- Ignoring **multiple comparisons** across metrics, variants, and segments.
- Trusting an experiment with **sample-ratio mismatch** instead of debugging the assignment/logging bug it signals.
- Reading the **early** treatment effect as the long-run effect (novelty/primacy), and aggregating across a confounder into **Simpson's paradox**.
- Treating Poisson/normal as defaults without checking — overdispersed counts and skewed/heavy-tailed metrics break the assumptions.

## 📚 Further Reading

- Kohavi, Tang & Xu — *Trustworthy Online Controlled Experiments* (the definitive practitioner's book on A/B testing).
- Deng, Xu, Kohavi & Walker — *Improving the Sensitivity of Online Controlled Experiments by Utilizing Pre-Experiment Data* (the CUPED paper, 2013).
- Johari, Pekelis, Walsh & Koomen — *Always Valid Inference: Continuous Monitoring of A/B Tests* (sequential/peeking-safe testing).
- Pearl & Mackenzie — *The Book of Why* (causation, confounders, and Simpson's paradox).
- Wasserman — *All of Statistics* (a concise graduate-level reference for the underlying theory).
- Benjamini & Hochberg (1995) — *Controlling the False Discovery Rate* (the FDR / BH procedure).
- Athey & Imbens — surveys on machine-learning methods for causal inference and heterogeneous treatment effects.
