# EduTrack — Getting Started

From an empty folder to four developers writing code. Follow in order.

---

## Prerequisites (every developer)

| Tool | Version | Check |
|---|---|---|
| Java (Temurin) | 21 LTS | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Node.js | 20 LTS+ | `node -v` |
| Docker Desktop | current | `docker ps` |
| Git | 2.40+ | `git --version` |
| Claude Code | current | `claude --version` |
| GitHub account | — | with repo write access |

### Installing JDK 21 — read this before you build

**Spring Boot 3.3 does not run on Java 24+.** If `java -version` shows anything above 21, the build fails in ways that do not obviously point at the JDK. Install Temurin 21 and make it the default.

**With Homebrew:**

```bash
brew install --cask temurin@21
```

**Without Homebrew** (no admin rights needed — macOS also looks in your home directory):

```bash
mkdir -p ~/Library/Java/JavaVirtualMachines
curl -L -o /tmp/jdk21.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/21/ga/mac/aarch64/jdk/hotspot/normal/eclipse"
tar xzf /tmp/jdk21.tar.gz -C /tmp
mv /tmp/jdk-21* ~/Library/Java/JavaVirtualMachines/temurin-21.jdk
```

*(Intel Mac: swap `aarch64` for `x64`.)*

**Then make it the default** — append to `~/.zshrc`:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
```

Open a new terminal and confirm:

```bash
java -version        # must report 21.x
/usr/libexec/java_home -V   # lists every JDK macOS can see
```

Other JDKs can stay installed — `java_home -v <version>` switches between them per shell.

**Maven is not required.** The wrapper is committed: use `./mvnw` from `backend/`.

### Installing Docker

**With admin rights:** Docker Desktop from docker.com, or `brew install --cask docker`.

**Without admin rights** — Colima runs a Linux VM on Apple's Virtualization framework entirely in user space. This is a verified working setup (macOS 26.5, Apple silicon):

```bash
mkdir -p ~/.local/bin ~/.docker/cli-plugins

# Colima
curl -sL -o ~/.local/bin/colima \
  https://github.com/abiosoft/colima/releases/latest/download/colima-Darwin-arm64
chmod +x ~/.local/bin/colima

# Lima (Colima needs the `lima` wrapper on PATH, not just `limactl`)
curl -sL https://github.com/lima-vm/lima/releases/download/v2.2.0/lima-2.2.0-Darwin-arm64.tar.gz \
  | tar xz -C ~/.local/lima --one-top-level
ln -sf ~/.local/lima/bin/* ~/.local/bin/

# Docker CLI + Compose plugin
curl -sL https://download.docker.com/mac/static/stable/aarch64/docker-29.7.1.tgz \
  | tar xz -C /tmp && cp /tmp/docker/docker ~/.local/bin/
curl -sL -o ~/.docker/cli-plugins/docker-compose \
  https://github.com/docker/compose/releases/latest/download/docker-compose-darwin-aarch64
chmod +x ~/.docker/cli-plugins/docker-compose ~/.local/bin/docker

echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.zshrc && exec zsh

colima start --vm-type=vz --cpu 4 --memory 6 --disk 40 --runtime docker
```

*(Intel Mac: swap `arm64`/`aarch64` for `amd64`/`x86_64`.)*

**After a reboot the VM is stopped** — run `colima start` again. `make up` alone will fail with "cannot connect to the Docker daemon" until it is running.

---

## Step 1 — Lead decisions · 30 minutes · **blocking**

Nothing else can start until these are answered.

**1a. Assign the four streams to the four developers.**

| Stream | Give it to | Why |
|---|---|---|
| **A — Platform & Security** | Strongest backend/infrastructure person | Gates the other three for six weeks; owns the highest-risk component in the system |
| **C — Tickets & Ribbon** | Strongest all-rounder | ~40% of the product surface and the hardest UI |
| **B — Masters & Clients** | Fastest at CRUD and forms | Broad but shallow; the import wizard is the one hard piece |
| **D — Engines & Realtime** | Comfortable with async, queues, scheduling | Most independent; least damaged by a late start |

**1b. Answer the directory question.** Is there an existing employee directory or SSO (Azure AD, Google Workspace) that the Resource Master should sync from, or is EduTrack the system of record for users? If a directory exists, `users` needs an external ID **now** — retrofitting one after M3 means touching every master screen.

**1c. Confirm the four governance defaults** (PLAN.md §5). All four follow the blueprint's own recommendation; confirming now costs a minute, discovering a disagreement during M2 costs a week.

- Effort mandatory at handoff → **blocking**
- Rework resets Planned Close Date → **no, original stands**
- Developer may close → **no, Resolved only**
- Escalated level reverts after close → **no, keep `original_level`**

---

## Step 2 — Repository bootstrap · ✅ done

Already in place at the project root:

| File | Purpose |
|---|---|
| `.gitignore` | Java, Node, IDE, OS, secrets |
| `.gitattributes` | LF normalisation · generated client `merge=ours` · binary markers |
| `CONTRIBUTING.md` | The daily loop, seven rules, and the PR description template |
|  `.github/workflows/ci.yml` | Backend build · frontend build · **OpenAPI staleness check** · **migration guard** |
| `CLAUDE.md` | Auto-loaded rules for every session |
| `docs/` | Blueprint, PLAN, TEAM-PLAN, this file, stream backlogs, decks |

Committed on `main`, with `develop` branched from it.

---

## Step 3 — Connect to GitHub · lead

Repository: **https://github.com/debashisedunext/EduTrack.git**

**3a. Add the remote and push.**

```bash
git remote add origin https://github.com/debashisedunext/EduTrack.git
git push -u origin main
git push -u origin develop
```

GitHub does not accept an account password over HTTPS. Either install the GitHub CLI (`brew install gh && gh auth login`), which configures a credential helper for you, or use SSH:

```bash
git remote set-url origin git@github.com:debashisedunext/EduTrack.git
```

> **If the repo was created with a README or .gitignore**, the remote has a commit yours doesn't:
> ```bash
> git pull --rebase origin main --allow-unrelated-histories
> ```
> Resolve any conflict (keep your `.gitignore`), then push.

**3b. Set `develop` as the default branch.** Settings → General → Default branch → `develop`. New pull requests then target `develop` automatically, which is what you want — `main` should only ever receive a promotion.

**3c. Fill in CODEOWNERS.** `.github/CODEOWNERS` ships with `@stream-a` … `@stream-d` placeholders. Replace them with real GitHub usernames once streams are assigned — until then GitHub silently requests nobody.

**3d. Apply branch protection** to **both** `main` and `develop` — Settings → Branches → Add rule. See TEAM-PLAN.md §8.5 for the full table. Two that matter:

- **Require status checks to pass** — add all four job names, or a red CI run can still be merged.
- **Require review from Code Owners** — this is what makes the ownership map enforceable rather than advisory.

**3e. Add the four developers** — Settings → Collaborators and teams. **Write** access is enough; branch protection stops them pushing to `main` or `develop` directly.

**3f. Actions** are enabled by default on new repositories. Confirm at Settings → Actions → General that "Allow all actions" is selected.

---

## Step 4 — Kickoff · whole team, 90 minutes

Not optional. Four people building one system need a shared mental model of two things, or they will each build a different product:

1. **Walk blueprint §4A — the Workflow Ribbon.** Specifically that `iteration_no` and `cycle_no` are *two independent counters*: iteration increments when a ticket goes backwards within a cycle; cycle increments when it is reopened after closure. This is the single most misunderstood concept in the spec.
2. **Walk blueprint §4 — the reopen and history model.** Why history is append-only, why every layer enforces it, and why "just add an update method" is never the answer.

Then confirm: stream assignments · the four gating tasks below · that the OpenAPI contract review happens end of week 1 · the git rules in `CLAUDE.md`.

---

## Step 5 — The common baseline · every developer, identically

Sprint 0 is **shared in time but split in work.** Only these five things are done by all four developers; everything else in weeks 1–2 is stream-specific.

### 5.1 Install the toolchain · day 0, before the repo exists

The prerequisites table above. Nobody is blocked on anybody for this — do it while Steps 1–3 are still in progress.

### 5.2 Clone and orient

```bash
git clone <repo-url> && cd edutrack
git checkout develop
claude
```

Then, in Claude Code:

```
/stream-platform     # or /stream-masters, /stream-tickets, /stream-engines
```

The skill loads your scope, owned paths, boundaries and backlog, and names your next task. `CLAUDE.md` loads automatically either way.

### 5.3 Required reading · ~2 hours, everyone, before writing code

Four blueprint sections are the **shared mental model**. A developer who hasn't read these will build something that looks right and is wrong in a way that only surfaces in month three.

| Read | Why every stream needs it |
|---|---|
| **§2** Role model & permission matrix | Six roles and two orthogonal scopes decide visibility on *every* query you write, in every stream |
| **§3** Core workflow | Stage and status are **separate layers** — a ticket can be *In Progress* in the *QA* stage. Confusing them corrupts the data model |
| **§4** Reopen & history model | Why history is append-only at four layers, and why "just add an update method" is never the answer |
| **§4A** The Workflow Ribbon | `iteration_no` and `cycle_no` are **two independent counters**. The single most misunderstood concept in the spec |

Plus, for your own work: `CLAUDE.md` (rules, all streams) and your `docs/streams/STREAM-*.md` backlog.

Skim the rest of the blueprint. Read §12 (design tokens) if you touch UI — which, being full-stack, you do.

### 5.4 Verify the stack on your machine · once A-002 lands, ~day 2

Every developer runs this and confirms it works **on their own machine**, not just on A's:

```bash
docker compose up -d
docker compose ps          # mysql, redis, minio, mailpit all healthy
mvn -q verify              # green
```

A stack that only runs on one laptop is a stack that will block three people the first time it breaks.

### 5.5 Review and sign off the OpenAPI contract · end of week 1

**The one genuinely shared deliverable of Sprint 0.** Stream D authors it; all four review and sign off.

This is where four developers stop guessing at each other's payload shapes. An hour spent here saves days of rework in weeks 4–8, because after this point the contract — not a conversation — is what each stream builds against.

Come with your stream's endpoints checked: are the fields you need present, are the types right, does pagination and the error envelope work for your screens?

---

## What is *not* common

Everything else in Sprint 0. A does the schema; B does seed data and fixtures; C does the design system; D does the contract and mocks. Four different backlogs, four different branches, no overlap — that is the point of the stream split.

**Do not wait for each other.** C and D depend on nothing and start day 1. B starts day 3, once A's schema lands.

---

## Step 6 — Sprint 0 · weeks 1–2

**Four tasks gate the entire team.** If these slip, the parallelism collapses back into a queue and three developers idle.

| Task | Owner | Due | Blocks |
|---|---|---|---|
| **A-012** `dev-noauth` profile | A | **Day 10** | B, C and D — all authenticated endpoint work |
| **B-007** Ticket fixture corpus | B | Day 10 | D's SLA testing, C's ribbon testing |
| **B-024** Working-hours service | B | Week 3 | Every SLA calculation D writes |
| **D-004** MSW mock server | D | **Day 5** | C's entire frontend |

### Day-by-day

| Days | A | B | C | D |
|---|---|---|---|---|
| **1–2** | A-001, A-002 — Maven skeleton, docker-compose | *(waiting on schema)* | C-001, C-002 — scaffold, design tokens | D-001 — OpenAPI contract |
| **3–7** | A-003…A-009 — the full baseline schema | B-001…B-004 — seed data | C-003…C-004 — component library, Storybook | D-002…D-005 — codegen, **mocks by day 5** |
| **8–10** | A-010…A-013 — grants, CI, **`dev-noauth`**, trigger tests | B-005…B-008 — entities, **fixtures** | C-005, C-006 — app shell, command palette | Contract review with all four |
| **11–14** | Buffer, begin M1 | Buffer, begin M3 | Buffer, begin M4 | D-010…D-012 — outbox, ShedLock, STOMP |

B starts day 3 deliberately — entities need A's schema. C and D depend on nothing and start day 1.

### Sprint 0 exit gate

**Do not start M1 until all five pass:**

- [ ] `docker compose up` yields a migrated database with full seed data
- [ ] `mvn verify` green, **including the negative tests proving the triggers reject `UPDATE` and `DELETE`**
- [ ] `npm run storybook` renders the component library in the correct tokens
- [ ] `npm run dev` serves the React shell against MSW mocks with **no backend running**
- [ ] All four developers have reviewed and signed off the OpenAPI contract

---

## Step 7 — The daily rhythm

### Developer loop

```bash
# start of day
git checkout develop && git pull
git checkout -b feat/tickets/quick-update-panel

claude
> /stream-tickets
> start C-036
```

Work. Then:

```bash
git add -A
git commit -m "feat(tickets): add quick update slide-over with optimistic UI"
git pull --rebase origin develop        # daily, without exception
git push -u origin feat/tickets/quick-update-panel
```

Then tell Claude the branch is ready.

**Never** `git checkout main` · **never** merge your own branch · **never** merge `develop` into your branch (rebase instead) · **never** let a branch go a week without rebasing.

### Integration loop (Claude, end of each day)

Per TEAM-PLAN.md §9: rebase each ready branch on `develop`, resolve conflicts, run the full build, merge `--no-ff`, push, delete the branch. Anything unmergeable is reported back the next morning with the specific conflict.

Promotion to `main` happens at milestone boundaries, tagged.

---

## Step 8 — Milestone cadence

| Milestone | Weeks | Ends when |
|---|---|---|
| **M0** Foundation | 1–2 | The five exit criteria above |
| **M1** Auth + scope guard | 3–7 | All six roles log in; permission matrix green; Developer gets 404 on another's ticket |
| **M2** Immutability core | 8–9 | Chain verifier passes; every mutation rejected at the DB; no fork under concurrent load |
| **M3** Masters | 3–9 (B) | An Admin can stand up a complete tenant without touching the database |
| **M4** Tickets + ribbon | 3–14 (C) | Blueprint §14 walkthrough A runs end to end and reconciles to 38.0 h |
| **M5** SLA + mail | 6–11 (D) | Walkthrough A step 11 escalates, mails, and turns the banner red live |
| **M6** Dashboard + reports | 10–16 | Dashboard first paint under 1.5 s at 50,000 tickets |
| **M7** Chat + hardening | 12–18 | Security review passes; UAT signed off |

At each boundary: promote `develop` → `main`, tag, demo, then re-read the next milestone's tasks together.

---

## What to do right now

1. Assign the four streams (Step 1a)
2. Answer the directory question (Step 1b)
3. Bootstrap the repo (Step 2) — ask Claude to do it
4. Create the remote and add the team (Step 3)
5. Kickoff (Step 4)
6. Everyone runs their stream skill and starts Sprint 0 (Steps 5–6)
