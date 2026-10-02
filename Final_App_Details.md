For the **completely finished version of Sam**, I would treat the product as more than a voice-controlled automation agent. The final system should be a **teachable, persistent, semantically retrievable automation agent**: the user can teach Sam a task once, Sam records what happened, converts the successful interaction into a reusable workflow/memory, stores it, and later retrieves the most relevant learned experience when the user gives a similar command—even if the wording, quantities, or some UI details have changed.

The key distinction is that **Gemini remains the intelligence layer, while Sam's own storage becomes the long-term memory layer**. Do not confuse this with Gemini Context Caching: Gemini's context caching is primarily an optimization for repeatedly sending the same context and has cache lifetime/usage semantics; it is not your application's permanent semantic memory. ([Google AI for Developers][1])

# 1. The final Sam experience

The finished product should support two modes:

```text
NORMAL MODE
User:
"Hey start listening"

        ↓

Sam listens

        ↓

"Open Amazon and find 5 packets of milk"

        ↓

Semantic Memory Retrieval

        ↓

Found previous Amazon/milk workflow

        ↓

Gemini adapts it to current request

        ↓

Agent executes

        ↓

Successful result

        ↓

Memory updated
```

And:

```text
TEACH MODE

User:
"Hey start listening"

        ↓

"Teach me how to order milk from Amazon"

        ↓

Sam enters TEACHING MODE

        ↓

User demonstrates / Sam performs actions

        ↓

Every UI transition is recorded

        ↓

Successful completion

        ↓

Gemini summarizes the workflow

        ↓

Workflow is embedded + stored

        ↓

Future requests can retrieve it semantically
```

That is the feature that makes Sam genuinely **teachable** rather than just another Gemini-powered UI agent.

---

# 2. The complete architecture

I would make the final architecture approximately:

```text
                         ┌─────────────────────┐
                         │       USER          │
                         └──────────┬──────────┘
                                    │
                          "Hey start listening"
                                    │
                                    ▼
                    ┌─────────────────────────────┐
                    │ Android VoiceInteraction    │
                    │ Service                     │
                    └──────────────┬──────────────┘
                                   │
                                   ▼
                    ┌─────────────────────────────┐
                    │ VoiceInteractionSession     │
                    │                             │
                    │ Audio capture + STT         │
                    └──────────────┬──────────────┘
                                   │
                                   ▼
                           USER COMMAND
                                   │
                                   ▼
                    ┌─────────────────────────────┐
                    │     Request Router          │
                    │                             │
                    │ app + intent + parameters  │
                    └──────────────┬──────────────┘
                                   │
                                   ▼
                    ┌─────────────────────────────┐
                    │     Memory Retriever        │
                    │                             │
                    │ semantic search             │
                    │ workflow retrieval          │
                    └──────────────┬──────────────┘
                                   │
                     relevant memories/workflows
                                   │
                                   ▼
                    ┌─────────────────────────────┐
                    │          Gemini             │
                    │                             │
                    │ adapt memory to current     │
                    │ request + current UI        │
                    └──────────────┬──────────────┘
                                   │
                                   ▼
                    ┌─────────────────────────────┐
                    │      AgentController        │
                    │                             │
                    │ observe → decide → act      │
                    └──────────────┬──────────────┘
                                   │
                                   ▼
                    ┌─────────────────────────────┐
                    │ Accessibility Bridge        │
                    └──────────────┬──────────────┘
                                   │
                                   ▼
                       Amazon / Zomato / etc.
                                   │
                                   ▼
                           RESULT / FAILURE
                                   │
                                   ▼
                    ┌─────────────────────────────┐
                    │     Memory Learner          │
                    │                             │
                    │ successful trajectory       │
                    │ new UI observations         │
                    │ parameters                  │
                    │ failures / corrections      │
                    └──────────────┬──────────────┘
                                   │
                                   ▼
                         Persistent Memory
```

Android's `VoiceInteractionService` is appropriate for the system-level assistant layer; Android documents that the selected service is kept running for capabilities such as background hotwording, while heavier interaction/UI belongs in the associated `VoiceInteractionSessionService`. ([Android Developers][2])

Your AccessibilityService should remain responsible for UI observation, actions, and accessibility overlays. Android explicitly supports background accessibility services and accessibility overlays. ([Android Developers][3])

---

# 3. Sam needs a real memory system

This is the most important addition.

Don't simply save:

```text
"User ordered milk from Amazon."
```

That isn't enough for teachability.

You need to store several different kinds of memory.

I would divide memory into:

```text
1. Workflow Memory
2. UI Pattern Memory
3. Parameter Memory
4. Semantic Command Memory
5. Failure Memory
6. Preference Memory
7. Execution History
```

---

# 4. Workflow Memory

A workflow represents a task Sam successfully learned.

For example:

```json
{
  "workflow_id": "wf_amazon_milk_001",
  "app": "Amazon",
  "goal": "Find milk",
  "description": "Search Amazon for milk and select the requested quantity",
  "steps": [
    {
      "action": "CLICK",
      "target_role": "text_field",
      "target_label": "Search"
    },
    {
      "action": "INPUT",
      "value_template": "{{item}}"
    },
    {
      "action": "CLICK",
      "target_role": "button",
      "target_label": "Search"
    }
  ],
  "parameters": {
    "item": "milk",
    "quantity": 5
  }
}
```

But don't store the exact Accessibility node IDs as the primary workflow.

For example, don't depend on:

```text
node_id = aw04d4
```

because that can change between sessions.

Instead store semantic information:

```text
role = text_field
label = Search
content_description = Search
action = INPUT
```

This allows Sam to find the corresponding node again.

---

# 5. Store the actual successful trajectory

Your current agent already produces something extremely valuable:

```text
BEFORE UI
    ↓
ACTION
    ↓
AFTER UI
```

You should persist that.

For example:

```json
{
  "step": 4,
  "state_before": "sha256...",
  "action": {
    "type": "CLICK",
    "element_role": "button",
    "element_label": "Search"
  },
  "state_after": "sha256...",
  "result": "SUCCESS"
}
```

And the complete workflow:

```text
State A
  ↓ CLICK Search
State B
  ↓ INPUT "milk"
State C
  ↓ CLICK Search
State D
  ↓ SCROLL
State E
  ↓ CLICK product
State F
```

This is essentially Sam's **experience memory**.

---

# 6. But don't store the entire UI tree blindly

This is important for storage size and retrieval quality.

Suppose Amazon has 400 Accessibility nodes.

You don't want to permanently store all 400 nodes for every step.

Instead create a **semantic snapshot**.

For example:

```json
{
  "app": "Amazon",
  "screen_type": "search_results",
  "elements": [
    {
      "role": "text_field",
      "label": "Search",
      "actions": ["INPUT"]
    },
    {
      "role": "button",
      "label": "Cart",
      "actions": ["CLICK"]
    },
    {
      "role": "card",
      "label": "Amul Milk 1L",
      "actions": ["CLICK"]
    }
  ]
}
```

Then store:

```text
semantic_snapshot
fingerprint
important_elements
screen_type
successful_action
```

rather than every raw Android object.

---

# 7. Semantic retrieval is what makes the system "teachable"

This is the critical part of your idea.

Suppose Sam previously learned:

> "Order milk from Amazon."

Later the user says:

> "Get me five packets of milk from Amazon."

The text is different.

A keyword search might not recognize that these are related.

Instead:

```text
Previous workflow:
"Order milk from Amazon"

             ↓
          embedding

Current request:
"Get me five packets of milk from Amazon"

             ↓
          embedding

       vector similarity

             ↓

HIGH SIMILARITY

             ↓

Retrieve workflow
```

Gemini provides embedding models specifically intended for semantic search, classification, and clustering. Google's current Gemini API documentation describes `gemini-embedding-2` as a multimodal embedding model, while `gemini-embedding-001` remains available for text-only use cases. ([Google AI for Developers][4])

---

# 8. Your memory database should therefore have embeddings

A memory record could look like:

```text
Memory
────────────────────────────
memory_id
workflow_id
app
intent
goal
description
parameters
workflow_json
embedding
created_at
last_used_at
success_count
failure_count
version
```

For example:

```json
{
  "memory_id": "mem_0081",
  "app": "Amazon",
  "goal": "Find milk",
  "description": "Search Amazon for milk",
  "embedding": [0.0123, -0.0912, ...],
  "success_count": 7,
  "failure_count": 1
}
```

---

# 9. Retrieval should happen BEFORE Gemini decides everything from scratch

This is where your finished product becomes substantially better.

Currently:

```text
User command
      ↓
Gemini
      ↓
figure everything out
```

The finished Sam should do:

```text
User command
      ↓
Normalize request
      ↓
Generate embedding
      ↓
Semantic memory search
      ↓
Top relevant workflows
      ↓
Current UI
      ↓
Gemini
      ↓
adapt known workflow
      ↓
execute
```

For example:

```text
User:
"Get me 5 packets of milk from Amazon."

          ↓

Request:
APP = Amazon
ITEM = milk
QUANTITY = 5

          ↓

Semantic Search

          ↓

Memory #1
"Order milk from Amazon"
Similarity: 0.91

Memory #2
"Search Amazon for groceries"
Similarity: 0.78

Memory #3
"Buy household products"
Similarity: 0.69

          ↓

Retrieve #1 + useful portions of #2

          ↓

Gemini
```

You don't need to send every historical memory to Gemini.

Only send the **top relevant memories**.

---

# 10. This is where "cached" should mean memory, not Gemini cache

You said:

> already done things are sortof cached and used by retrieving semantically next time

That is exactly right, but I would call it:

**Persistent Semantic Memory**

rather than simply "cache."

Why?

A cache normally means:

```text
temporary performance optimization
```

Your memory means:

```text
knowledge acquired from previous successful execution
```

Gemini's Context Caching is useful for repeatedly reused large prompts/context and can reduce repeated input processing costs, but it is not the right mechanism for your permanent workflow library. ([Google AI for Developers][1])

---

# 11. Teachability needs an explicit teaching mode

Your final app should have:

```text
NORMAL
TEACH
REPLAY
```

### Normal

```text
"Order milk from Amazon."
```

Sam retrieves known workflows.

### Teach

```text
"Teach me how to order milk from Amazon."
```

Sam says:

```text
"Teaching mode started."
```

Then it records the interaction.

### Replay

The next time:

```text
"Order milk from Amazon."
```

Sam retrieves the learned workflow.

---

# 12. Teaching mode should record more than clicks

Suppose the user teaches:

```text
Open Amazon
↓
Tap search
↓
Type milk
↓
Search
↓
Open first result
↓
Select quantity
```

Sam should record:

```text
Intent
App
Goal
Actions
UI semantics
Parameters
Successful outcome
```

Not just:

```text
click x=384,y=612
```

Coordinates are fragile.

Instead:

```json
{
  "action": "CLICK",
  "target": {
    "role": "button",
    "label": "Search",
    "content_description": "Search"
  }
}
```

---

# 13. Teachability also means parameter generalization

This is one of your most important final features.

Suppose Sam learns:

> "Buy 2 packets of milk."

Later the user says:

> "Buy 5 packets of milk."

Sam shouldn't create an entirely new workflow.

Instead:

```text
Learned:

quantity = 2

         ↓

Generalize:

quantity = {{quantity}}
```

Then:

```text
New request:

quantity = 5

         ↓

Reuse workflow
         ↓
substitute quantity = 5
```

Your workflow should therefore have:

```json
{
  "parameters": {
    "item": "{{item}}",
    "quantity": "{{quantity}}"
  }
}
```

This is what makes the workflow reusable.

---

# 14. You also need semantic UI matching

Suppose the original workflow saw:

```text
"Search Amazon"
```

but Amazon later changes it to:

```text
"Search"
```

The workflow should not immediately fail.

Instead, the agent can match using:

```text
role
+
label
+
content description
+
nearby text
+
available action
+
screen context
```

For example:

```text
Expected:
TextField
"Search"

Current:
TextField
"Search Amazon"
```

Gemini/your deterministic matcher can recognize that these are probably the same functional element.

This is the **UI-change robustness** feature.

---

# 15. Use deterministic matching before Gemini

Don't send every element to Gemini unnecessarily.

Your execution system can try:

```text
1. Exact semantic match
2. Normalized label match
3. Role + label match
4. Contextual match
5. Learned selector match
6. Gemini reasoning
7. Ask user
```

For example:

```text
Expected:
BUTTON "Search"

Current UI:
BUTTON "Search"

→ deterministic match
```

No Gemini call necessary.

If:

```text
Expected:
BUTTON "Search"

Current:
BUTTON "Find products"
```

then:

```text
semantic similarity
```

can help.

Only when uncertain:

```text
Gemini
```

This reduces latency and API usage.

---

# 16. Every successful execution should improve the memory

After every successful task:

```text
Execution
   ↓
Success?
   ↓ YES
Update memory
```

For example:

```text
success_count += 1
last_used_at = now
confidence +=
```

If the UI changed but the agent successfully adapted:

```text
workflow version updated
```

You can store:

```text
workflow_version = 3
```

and preserve the previous version if needed.

---

# 17. Failed executions should also become memory

This is just as important.

Suppose Sam learned:

```text
Amazon → Search → Milk → First result
```

but the first result was unavailable.

The agent tries another path.

Record:

```json
{
  "failed_action": "CLICK first product",
  "reason": "product unavailable",
  "alternative": "select second product",
  "context": "search_results"
}
```

Next time Sam sees the same situation, it can avoid repeating the failed transition.

This combines very well with the loop detection you already have.

---

# 18. You need a confidence score

Every retrieved workflow should have something like:

```text
retrieval_score
workflow_success_rate
UI_match_score
parameter_match_score
```

For example:

```text
Workflow A

Semantic similarity:     0.93
App match:               1.00
Parameter compatibility: 1.00
Past success:            0.96
UI match:                0.88

Overall confidence:      HIGH
```

You don't necessarily need Gemini to calculate this.

Your controller can calculate deterministic scores and give Gemini the relevant candidates.

---

# 19. Don't automatically replay a low-confidence workflow

The finished system needs three paths:

```text
HIGH CONFIDENCE
      ↓
reuse workflow

MEDIUM CONFIDENCE
      ↓
reuse workflow + Gemini verification

LOW CONFIDENCE
      ↓
normal exploration / ask user
```

That prevents an old memory from blindly causing the wrong action.

---

# 20. The memory hierarchy

I would implement retrieval in this order:

```text
Exact recent task
       ↓
Exact app + similar goal
       ↓
Semantic workflow match
       ↓
Similar UI pattern
       ↓
General app knowledge
       ↓
Fresh exploration
```

So if the user says:

> "Open Amazon and get milk."

Sam first checks:

```text
Did I recently perform exactly this?
```

Then:

```text
Do I have an Amazon + milk workflow?
```

Then:

```text
Do I have an Amazon grocery workflow?
```

Then:

```text
Do I know how to search Amazon generally?
```

Then Gemini can explore from scratch.

---

# 21. Your database architecture

For an Android product, I would use a local persistent database for the user's learned memories.

Conceptually:

```text
Room / SQLite
        │
        ├── workflows
        ├── workflow_steps
        ├── ui_patterns
        ├── executions
        ├── failures
        ├── parameters
        └── embeddings
```

You can store the embedding vector alongside the memory and perform similarity retrieval locally if the memory volume remains manageable.

For larger-scale/cloud synchronization, use a backend/vector database.

The important thing is:

```text
Gemini = intelligence
Database = memory
```

Do not make Gemini itself your only storage mechanism.

---

# 22. You can also use Gemini embeddings

For every reusable workflow, generate an embedding for something like:

```text
Amazon
Search for groceries
Find milk
Buy milk
Select quantity
```

Then:

```text
User:
"Get me five packets of milk from Amazon"

       ↓

embedding

       ↓

vector search

       ↓

workflow:
"Order milk from Amazon"
```

Gemini's embedding API is specifically designed for semantic search use cases. ([Google AI for Developers][4])

---

# 23. What about Gemini File Search?

Gemini's File Search is another RAG mechanism, and Google describes it as importing, chunking, indexing, and semantically retrieving relevant content. ([Google AI for Developers][5])

However, **I would not use File Search as the primary storage for Sam's workflow memory**.

Your workflow data is:

```text
dynamic
structured
frequently updated
execution-specific
parameterized
versioned
```

A proper application database is a better fit.

File Search is much more naturally suited to:

```text
documents
manuals
knowledge bases
reference material
```

Also, the current Gemini File Search documentation says the Live API does not support File Search, and uploaded files themselves have a 48-hour lifetime even though the indexed embeddings persist. ([Google AI for Developers][6])

So your architecture should be:

```text
Sam Workflow Memory
        ↓
Your DB + embeddings
```

not:

```text
Sam Workflow Memory
        ↓
File Search
```

---

# 24. You can use Gemini Context Cache separately

There **is** a place for Gemini Context Caching.

Suppose every agent request contains the same large system instructions:

```text
You are Sam.
You can use:
CLICK
INPUT
SCROLL
BACK
WAIT
DONE
ASK

You must obey these safety rules...
You must...
```

Instead of repeatedly sending a huge static context, Gemini's caching facilities can reduce repeated input processing. Google's current documentation describes implicit caching on supported newer Gemini models and explicit caching through supported APIs. ([Google AI for Developers][1])

But conceptually:

```text
Persistent Memory
        ≠
Gemini Context Cache
```

Persistent memory:

```text
"What did Sam learn?"
```

Context cache:

```text
"Don't make Sam repeatedly process this same large context."
```

---

# 25. The final teachability pipeline

This is the part I would consider the heart of Sam.

### First time

User:

> Teach me how to order milk from Amazon.

Sam:

```text
TEACHING MODE
```

Then:

```text
Observe
↓
Action
↓
Observe
↓
Action
↓
...
↓
Success
```

Sam stores the trajectory.

Then Gemini receives the completed trajectory:

```text
Create a generalized reusable workflow from this successful execution.

Extract:
- app
- intent
- goal
- parameters
- variable values
- semantic UI targets
- action sequence
- alternative paths
- failure recovery
```

Gemini returns:

```json
{
  "workflow": "...",
  "parameters": [...],
  "steps": [...],
  "recovery": [...]
}
```

Then Sam generates an embedding and stores it.

---

# 26. Second time

User:

> Get me five packets of milk from Amazon.

Sam:

```text
Request
   ↓
embedding
   ↓
memory search
   ↓
Amazon milk workflow
   ↓
parameter extraction
quantity = 5
   ↓
current UI
   ↓
Gemini adapts
   ↓
execute
```

The system doesn't have to rediscover the entire task.

---

# 27. Third time with different wording

User:

> I need five milk packets. Get them from Amazon.

Semantic retrieval finds the same workflow.

That's the important distinction between:

```text
COMMAND MATCHING
```

and:

```text
SEMANTIC TASK MATCHING
```

Sam should use the second.

---

# 28. If the app UI changes

Suppose Amazon changes:

```text
Search
```

to:

```text
Search Amazon
```

Sam retrieves the old workflow but checks the current UI.

```text
Old workflow
      ↓
Current UI
      ↓
Semantic element matching
      ↓
compatible
      ↓
continue
```

If the workflow breaks:

```text
Old workflow
      ↓
current UI incompatible
      ↓
Gemini explores
      ↓
new successful path
      ↓
update workflow
```

That is how the system becomes progressively more robust.

---

# 29. You should keep workflow versions

Never overwrite a successful workflow blindly.

Use:

```text
workflow_id
version
```

For example:

```text
Amazon Milk Workflow

v1
created: September 2026

v2
Amazon UI changed

v3
new product-selection behavior
```

Then the system can choose the most successful compatible version.

---

# 30. Your final AgentController becomes a hybrid

Your current controller is approximately:

```text
Observe
↓
Gemini
↓
Action
↓
Observe
```

The finished one should become:

```text
User Request
      ↓
Semantic Memory Retrieval
      ↓
Relevant Workflow?
   /             \
 YES              NO
 │                 │
 │             Explore
 │                 │
 ▼                 ▼
Adapt          Gemini
Workflow       reasoning
 │                 │
 └───────┬─────────┘
         ▼
    Action Validator
         ↓
     Accessibility
         ↓
      UI Changed?
       /       \
     YES        NO
      │          │
      ▼          ▼
 Continue     Recovery
      │          │
      └────┬─────┘
           ▼
       Completion
           ↓
     Learn/update memory
```

That is the finished agent.

---

# 31. Teachability should also support corrections

This is a major feature I would add.

Suppose Sam chooses the wrong product.

User says:

> No, not that one. Choose the Amul one.

Sam should record:

```text
Incorrect choice
      ↓
User correction
      ↓
Correct target
      ↓
Update workflow
```

So teaching isn't limited to:

```text
Teach me...
```

It can happen organically during normal execution.

---

# 32. Sam should learn from successful adaptations

Suppose the original workflow was:

```text
Search → Milk → First result
```

but a later execution required:

```text
Search → Milk → Scroll → Third result
```

and Sam successfully adapted.

That successful adaptation is useful.

You can store:

```text
workflow variant
```

rather than replacing the original.

Over time:

```text
Amazon Milk Workflow
       │
       ├── Variant A: first result
       ├── Variant B: scroll results
       └── Variant C: filter brand
```

Semantic retrieval can then select the relevant variant.

---

# 33. What should never be stored

For a finished product, don't blindly persist sensitive information.

Avoid storing:

```text
passwords
PINs
OTP codes
payment credentials
credit-card numbers
authentication tokens
```

Your existing agent already has the correct principle of pausing for sensitive actions.

Memory should remember:

```text
"User needs to confirm payment"
```

not:

```text
"User's card number is..."
```

Likewise, don't permanently save raw microphone recordings unless there is a specific, disclosed reason to do so.

For normal voice commands:

```text
Audio
 ↓
STT
 ↓
text
 ↓
discard audio
```

is preferable.

---

# 34. Memory should be user-controllable

The final Sam should have a Memory section:

```text
Memory
────────────────────

Learned workflows

Amazon
  • Search for milk
  • Find groceries

Zomato
  • Search for biryani

Chrome
  • Search for...
```

Each memory should allow:

```text
View
Rename
Disable
Delete
Forget
```

And ideally:

```text
Clear all learned workflows
```

The user should know what Sam has learned.

---

# 35. You should also show why a memory was used

For debugging and trust:

```text
Using learned workflow:
"Search Amazon for milk"

Similarity:
High

Adapted:
Quantity = 5
```

You don't have to expose technical embeddings, but you can show:

```text
Using a learned workflow from a previous Amazon milk task.
```

That makes the behavior understandable.

---

# 36. The final project modules

I would eventually have your project organized roughly like:

```text
com.example.sam/

├── agent/
│   ├── AgentController.kt
│   ├── AgentState.kt
│   ├── AgentForegroundService.kt
│   ├── ActionValidator.kt
│   ├── LoopDetector.kt
│   └── RecoveryManager.kt
│
├── accessibility/
│   ├── MirrorAccessibilityService.kt
│   ├── AccessibilityBridge.kt
│   ├── UiTreeExtractor.kt
│   ├── UiNormalizer.kt
│   └── SemanticMatcher.kt
│
├── voice/
│   ├── TAVVoiceInteractionService.kt
│   ├── TAVVoiceInteractionSessionService.kt
│   ├── TAVVoiceInteractionSession.kt
│   ├── AudioCaptureManager.kt
│   ├── GeminiLiveSttClient.kt
│   └── VoiceStateRepository.kt
│
├── routing/
│   ├── AgentRequest.kt
│   └── AgentRequestRouter.kt
│
├── memory/
│   ├── MemoryRepository.kt
│   ├── WorkflowRepository.kt
│   ├── WorkflowLearner.kt
│   ├── SemanticRetriever.kt
│   ├── EmbeddingService.kt
│   ├── MemoryRanker.kt
│   ├── WorkflowGeneralizer.kt
│   └── MemoryModels.kt
│
├── storage/
│   ├── AppDatabase.kt
│   ├── WorkflowDao.kt
│   ├── ExecutionDao.kt
│   ├── MemoryDao.kt
│   └── Converters.kt
│
├── gemini/
│   ├── GeminiApiClient.kt
│   ├── GeminiLiveSttClient.kt
│   └── GeminiEmbeddingClient.kt
│
└── ui/
    ├── MainActivity.kt
    ├── VoiceListeningOverlayManager.kt
    ├── AgentOverlayManager.kt
    └── MemoryActivity.kt
```

You don't have to literally use these filenames, but **the responsibilities should be this separated**.

---

# 37. The final data model

At minimum, I would have:

### `Workflow`

```text
id
appPackage
appName
intent
goal
description
parametersSchema
workflowDefinition
embedding
version
confidence
createdAt
updatedAt
successCount
failureCount
```

### `WorkflowStep`

```text
id
workflowId
stepIndex
actionType
targetRole
targetLabel
targetDescription
targetContext
inputTemplate
expectedState
alternativeActions
```

### `Execution`

```text
id
workflowId
request
startTime
endTime
success
stepsExecuted
failureReason
adaptations
```

### `UIPattern`

```text
id
appPackage
screenType
semanticDescription
elements
embedding
```

### `Memory`

```text
id
type
text
embedding
source
confidence
createdAt
lastUsedAt
```

---

# 38. The final API architecture

You can continue using Gemini for all AI-heavy functions:

```text
Gemini
│
├── Speech transcription
│
├── Request parsing
│
├── Workflow generalization
│
├── UI reasoning
│
├── Recovery reasoning
│
└── Embeddings
```

But the application owns:

```text
Sam
│
├── persistent storage
├── workflow database
├── semantic retrieval
├── execution history
├── state machine
├── action validation
├── loop detection
├── safety checks
└── Accessibility execution
```

That is important because **you don't want your application's core memory to disappear or become dependent on a particular model conversation.**

---

# 39. One optimization that will make a big difference

Don't call Gemini for everything.

Use this hierarchy:

```text
             User Request
                   │
                   ▼
          Semantic Memory Search
                   │
                   ▼
          Known workflow?
             /          \
           YES           NO
            │             │
            ▼             ▼
       Deterministic    Gemini
         matching       reasoning
            │             │
            └──────┬──────┘
                   ▼
              Current UI
                   │
                   ▼
         Deterministic selector
                   │
              uncertain?
              /       \
            NO         YES
            │           │
            ▼           ▼
          Action      Gemini
```

This makes Sam faster and reduces Gemini calls.

---

# 40. What "finished" should mean

I would consider **Sam v1 complete** only when all of these work:

### Voice

* Android invokes Sam through `VoiceInteractionService`.
* `"Hey start listening"` invokes the assistant.
* TAV Activity does not need to be open.
* Voice session appears.
* Audio is captured after invocation.
* Gemini STT produces the command.
* Voice session handles silence/end-of-command.

### Routing

* Natural language becomes `AgentRequest`.
* Target app is identified.
* Parameters are extracted.
* Unknown/ambiguous requests can ask the user.

### Automation

* Existing Accessibility bridge works.
* Semantic UI tree is generated.
* Gemini can choose actions.
* Actions are validated.
* UI changes are observed.
* No-op actions are detected.
* Loops are detected.
* Backtracking works.
* Dead ends are handled.
* Sensitive actions pause for confirmation.

### Teachability

* User can explicitly teach a workflow.
* Every action/observation is recorded.
* Successful execution becomes a generalized workflow.
* Variables become parameters.
* Workflow is versioned.
* Corrections can update the workflow.
* Successful adaptations are learned.

### Memory

* Workflows persist across app restarts.
* Embeddings are generated.
* Semantic retrieval works.
* Similar wording retrieves old workflows.
* Top relevant memories are supplied to Gemini.
* Irrelevant memories are filtered.
* Successful executions increase confidence.
* Failures are recorded.
* Old workflows can be updated/versioned.

### Replay

```text
New command
   ↓
Retrieve learned workflow
   ↓
Substitute new parameters
   ↓
Match workflow to current UI
   ↓
Adapt if necessary
   ↓
Execute
   ↓
Learn from result
```

### Storage

* Room/SQLite or equivalent persistent storage.
* No raw AccessibilityNodeInfo objects stored.
* No unnecessary raw audio recordings.
* Sensitive credentials never become workflow memory.
* Embeddings stored with their associated memory.
* Workflow versions retained.
* User can inspect/delete learned memories.

### Gemini

* Gemini API used for reasoning.
* Gemini Live/appropriate transcription API used for STT.
* Gemini embeddings used for semantic retrieval.
* Context caching used only where it actually reduces repeated context cost—not as permanent memory. Google's current documentation explicitly distinguishes caching from retrieval/embedding workflows. ([Google AI for Developers][1])
* API credentials are not hardcoded into the APK.
* Production client-side Live API authentication uses the appropriate short-lived credential mechanism documented by Google.

---

## The core idea of the final product

The finished Sam should effectively become:

```text
                 SAM
                  │
        ┌─────────┴─────────┐
        │                   │
     VOICE              MEMORY
        │                   │
        │              "What have I
        │               learned?"
        │                   │
        └─────────┬─────────┘
                  │
             GEMINI
                  │
          "What should I do?"
                  │
                  ▼
             AGENT
                  │
          "How do I execute it?"
                  │
                  ▼
          ACCESSIBILITY
                  │
                  ▼
              TARGET APP
                  │
                  ▼
             EXPERIENCE
                  │
                  ▼
              MEMORY
             updated
```

The most important loop is therefore:

```text
                    ┌───────────────┐
                    │ User Request  │
                    └───────┬───────┘
                            ▼
                    Semantic Retrieval
                            ▼
                    Learned Workflow
                            ▼
                     Gemini adapts
                            ▼
                     Agent executes
                            ▼
                     Accessibility
                            ▼
                       App UI
                            ▼
                        Result
                       /     \
                    success  failure
                      │         │
                      ▼         ▼
                 Learn/update  Record failure
                      │         │
                      └────┬────┘
                           ▼
                     Persistent Memory
                           │
                           └──────────────►
                                next request
```

**That is the final evolution of the system you have been building:** not "Gemini controls Amazon," but **Sam builds an accumulating library of successful, parameterized, semantically searchable interaction skills and uses Gemini to adapt those skills to the current UI and user request.**

One architectural point is especially important: **do not store only the final Gemini answer. Store the successful execution trajectory + semantic UI targets + parameters + outcome.** That trajectory is what turns your existing agent into a teachable system.

[1]: https://ai.google.dev/gemini-api/docs/caching?authuser=002&hl=en&utm_source=chatgpt.com "Context caching  |  Gemini API  |  Google AI for Developers"
[2]: https://developer.android.com/reference/android/service/voice/VoiceInteractionService?utm_source=chatgpt.com "VoiceInteractionService  |  API reference  |  Android Developers"
[3]: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService?utm_source=chatgpt.com "AccessibilityService  |  API reference  |  Android Developers"
[4]: https://ai.google.dev/gemini-api/docs/embeddings?authuser=117&utm_source=chatgpt.com "Embeddings  |  Gemini API  |  Google AI for Developers"
[5]: https://ai.google.dev/gemini-api/docs/file-search?linkId=17612261&utm_source=chatgpt.com "File search  |  Gemini API  |  Google AI for Developers"
[6]: https://ai.google.dev/gemini-api/docs/file-search?utm_source=chatgpt.com "File search  |  Gemini API  |  Google AI for Developers"
