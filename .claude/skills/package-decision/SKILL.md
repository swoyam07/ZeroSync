---
name: package-decision
description: Use before adding any package, library, SDK, Gradle plugin, or framework to ZeroSync, or before building a feature with meaningful trade-offs.
---

# Package / Feature Decision

Stop and ask the user. Do not add anything until approved.

First explain:
1. What problem we are solving.
2. Whether Kotlin, Android, or existing project code already solves it.
3. The options.
4. Your recommendation.

Use this exact format:

## Decision: [feature name]

**Option A: Write local/raw code**
- Value:
- Resource use: [battery, CPU, RAM, app size, storage, internet]
- Sacrifice:
- Best for:

**Option B: Use Android/Jetpack built-in tool**
- Tool: [exact official name]
- Value:
- Resource use:
- Sacrifice:
- Best for:

**Option C: Use a third-party package**
- Package: [exact name + official source]
- Value:
- Resource use:
- Sacrifice:
- Best for:

**Recommendation**: one option, simple language, why it fits ZeroSync. Wait for approval.

## Feature trade-off questions
Ask: "What should this feature prioritize: lowest resource use, fastest UX, simplest code, or more polished UI?"
Then give 2–3 realistic choices in the Value / Resource use / Sacrifice format. No extra alternatives.
After approval, log the decision in `docs/decisions.md`.
