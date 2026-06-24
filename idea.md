
Agent-native CLI for Mountebank
6/23/2026, 8:35:18 PM

i want to whip up a quick and dirty cli wrapping mountebank which makes it dead simple for agents to create re-usable mocked versions of external systems - github, gitlab, amazon apis, whatever. whats the highest-leverage set of features for such an agent-native cli tool?

I’ll frame this as a “thin but opinionated wrapper” around mountebank, with the agent workflow as the design center: discover, record, parameterize, replay, and share mocks.

The highest-leverage version is not “a nicer mountebank config generator.” It is an agent workflow tool that turns messy real API interactions into stable, named, reusable mock fixtures.

Mountebank already gives you the core primitives: imposters listen on ports/protocols, stubs define behaviors, predicates match requests, responses return data, and proxy mode can record/replay real downstream calls. Mountebank also exposes REST APIs for managing imposters, saved proxy responses, and recorded requests. [mbtest.dev]
The core product idea

Build a CLI that lets an agent say:
sh

mocksys init github
mocksys record github --target https://api.github.com --scenario create-issue
mocksys freeze github create-issue
mocksys run github create-issue

And get:
text

GitHub mock running at http://localhost:48231
Set GITHUB_API_URL=http://localhost:48231
Scenario: create-issue
Fixtures: 7 request/response pairs
Secrets: redacted
Matchers: method + path + selected query/body fields

That is the win.

The agent should not need to understand mountebank’s JSON structure unless it wants to.
Highest-leverage feature set
1. Service templates

Give agents a one-command starting point for common external systems:
sh

mocksys init github
mocksys init gitlab
mocksys init aws-s3
mocksys init aws-sts
mocksys init stripe
mocksys init slack
mocksys init generic-http

Each template should define:
yaml

service: github
protocol: http
default_port: auto
base_url_env:
  - GITHUB_API_URL
  - GH_HOST
auth:
  redact_headers:
    - authorization
    - x-github-token
stable_matchers:
  - method
  - path
  - selected_query
  - selected_json_body
volatile_fields:
  - headers.date
  - headers.x-request-id
  - body.id
  - body.created_at
  - body.updated_at

The “template” does not need to know the entire GitHub API. It just needs to know how to safely record, scrub, and replay traffic.

This is probably the single biggest productivity multiplier.
2. Record → sanitize → freeze workflow

Mountebank proxy mode can record/replay downstream responses; proxyOnce, proxyAlways, and proxyTransparent have different replay semantics. In particular, proxyOnce records one response per predicate-defined request and automatically replays matching calls later, while proxyAlways keeps proxying and can record richer interaction sets. [mbtest.dev]

Wrap that in a dead-simple lifecycle:
sh

mocksys record github --target https://api.github.com --scenario create-pr
# run the agent/app against the mock endpoint
mocksys inspect github create-pr
mocksys freeze github create-pr

The important opinion: recording should not immediately produce committed fixtures.

Instead, recording creates a draft scenario:
text

.mocks/
  github/
    scenarios/
      create-pr.draft/
        imposter.json
        traffic.ndjson
        review.md

Then freeze performs:

    Secret redaction.
    Header normalization.
    Timestamp/ID scrubbing.
    Matcher simplification.
    Fixture promotion.

Final output:
text

.mocks/
  github/
    scenarios/
      create-pr/
        mock.yaml
        imposter.json
        examples/
        README.md

This makes mocks reusable instead of becoming one-off blobs of captured traffic.
3. “Explain what I captured”

Agents need semantic summaries. After recording, generate a review file:
sh

mocksys inspect github create-pr

Output:
md

# Scenario: github/create-pr

## Calls observed

1. GET /repos/chazu/procyon
2. GET /repos/chazu/procyon/branches/main
3. POST /repos/chazu/procyon/pulls
4. GET /repos/chazu/procyon/pulls/42

## Detected secrets

- Authorization header: redacted
- X-GitHub-Api-Version: kept

## Volatile fields

- body.id
- body.node_id
- body.created_at
- body.updated_at
- headers.date
- headers.x-github-request-id

## Suggested matchers

- GET repo: method + path
- POST pull request: method + path + body.title + body.head + body.base

This is incredibly useful for agents because they can reason over the scenario without spelunking mountebank JSON.
4. Stable matcher generation

Raw record/replay often overfits. The wrapper should help agents create robust predicates.

Mountebank predicates are the request-matching conditions that determine which stub response gets used; it supports matching request fields such as path, method, query, body, headers, JSONPath, and XPath. [mbtest.readthedocs.io]

Your CLI should default to sane matchers:
yaml

match:
  method: POST
  path: /repos/:owner/:repo/issues
  json:
    title: present
    body: optional

Then compile to mountebank predicates.

Provide commands like:
sh

mocksys match github create-issue --on method,path,json.title
mocksys ignore github create-issue --header authorization
mocksys ignore github create-issue --json '$.created_at'
mocksys parameterize github create-issue --path '/repos/{owner}/{repo}/issues'

The killer feature is anti-overfit mode:
sh

mocksys doctor github create-issue

It warns:
text

Over-specific matcher detected:
- Matching full Authorization header
- Matching User-Agent generated by test runner
- Matching Date header
- Matching entire JSON body with volatile timestamp

Suggested matcher:
- method
- path
- body.title

That turns “captured once” into “usable by future agents.”
5. Scenario catalog

Agents need reusable named capabilities, not anonymous mocks.
sh

mocksys list

text

github
  create-issue
  create-pr
  repo-not-found
  rate-limited
  auth-failure
  branch-protection-failure

aws-s3
  put-object-success
  bucket-not-found
  access-denied
  eventual-consistency-delay

Each scenario should have:
yaml

name: github/create-issue
description: Creates an issue successfully
provides:
  - POST /repos/{owner}/{repo}/issues
  - GET /repos/{owner}/{repo}/issues/{number}
env:
  GITHUB_API_URL: http://localhost:{port}
tags:
  - github
  - issues
  - happy-path

Then:
sh

mocksys run github/create-issue
mocksys run github/create-issue github/rate-limited

This supports both happy-path and failure-mode testing.
6. Failure and edge-case authoring

Agents often need to test unhappy paths more than golden paths.

Make these trivial:
sh

mocksys add github rate-limited \
  --request 'GET /rate_limit' \
  --status 403 \
  --body examples/github-rate-limit.json

mocksys fault github create-issue --status 500
mocksys fault github create-issue --latency 2000
mocksys fault github create-issue --timeout
mocksys fault github create-issue --drop-connection

Support a small vocabulary:
yaml

faults:
  - status: 500
  - latency_ms: 2000
  - timeout: true
  - malformed_json: true
  - empty_body: true
  - rate_limit:
      reset_after_seconds: 60
  - pagination_truncation: true
  - auth_expired: true

For agent usefulness, this matters more than perfect coverage.
7. Contract-shaped fixture files

Do not force agents to edit mountebank JSON directly. Use a simpler source format and compile down.

Example:
yaml

service: github
scenario: create-issue

endpoints:
  - name: create issue
    request:
      method: POST
      path: /repos/{owner}/{repo}/issues
      match:
        json:
          title: present
    response:
      status: 201
      headers:
        content-type: application/json
      body:
        id: "{{ fake.github.issue_id }}"
        number: 42
        title: "{{ request.body.title }}"
        state: open

Then:
sh

mocksys compile github/create-issue

This gives agents a stable, human-editable representation while still using mountebank underneath.
8. Request verification

Mountebank can record requests for verification, and it exposes APIs for clearing recorded requests while leaving the imposter intact. [mbtest.dev]

Expose that as:
sh

mocksys assert github/create-issue \
  --saw 'POST /repos/chazu/procyon/issues' \
  --json 'title=Fix flaky test'

mocksys requests github/create-issue
mocksys clear-requests github/create-issue

This lets agents write tests like:
sh

mocksys run github/create-issue --background
go test ./...
mocksys assert github/create-issue --saw 'POST /repos/*/*/issues'

This is very high leverage because mocks become test oracles, not just fake servers.
9. Environment handoff

Every run should print shell-consumable output:
sh

eval "$(mocksys env github/create-issue)"

Output:
sh

export GITHUB_API_URL=http://127.0.0.1:48231
export GITHUB_TOKEN=mock-token
export MOCKSYS_SCENARIO=github/create-issue

Also support:
sh

mocksys run github/create-issue --env-file .env.test
mocksys run github/create-issue --json
mocksys run github/create-issue --docker-compose

Agents are usually wiring this into tests, CI, or an app invocation. The CLI should optimize for that.
10. One-file bundle export/import

Agents need to pass mocks between repos and sessions.
sh

mocksys pack github/create-issue > github-create-issue.mock.tgz
mocksys unpack github-create-issue.mock.tgz

Or:
sh

mocksys export github/create-issue --format markdown

Which emits:
md

# github/create-issue

## How to run

`mocksys run github/create-issue`

## Environment

`GITHUB_API_URL=...`

## Endpoints

...

For agent-native workflows, a markdown export is surprisingly useful. Agents can read it, explain it, modify it, and commit it.
The CLI shape I’d use
sh

mocksys init SERVICE
mocksys record SERVICE --target URL --scenario NAME
mocksys inspect SERVICE/SCENARIO
mocksys freeze SERVICE/SCENARIO
mocksys run SERVICE/SCENARIO
mocksys stop SERVICE/SCENARIO
mocksys list
mocksys requests SERVICE/SCENARIO
mocksys assert SERVICE/SCENARIO --saw 'METHOD PATH'
mocksys add SERVICE SCENARIO --request 'METHOD PATH' --status N --body FILE
mocksys fault SERVICE/SCENARIO --status 500
mocksys doctor SERVICE/SCENARIO
mocksys compile SERVICE/SCENARIO
mocksys pack SERVICE/SCENARIO

The smallest useful v0 could be:
sh

mocksys init
mocksys record
mocksys freeze
mocksys run
mocksys inspect
mocksys doctor

That is enough to be valuable.
Repository layout
text

.mocks/
  mocksys.yaml

  services/
    github.yaml
    gitlab.yaml
    aws-s3.yaml

  github/
    scenarios/
      create-issue/
        mock.yaml
        imposter.json
        README.md
        examples/
          create-issue.request.json
          create-issue.response.json

      rate-limited/
        mock.yaml
        imposter.json
        README.md

  generated/
    docker-compose.mock.yml

Top-level config:
yaml

mountebank:
  binary: mb
  admin_port: 2525
  allow_injection: false

defaults:
  host: 127.0.0.1
  port_strategy: auto
  redact:
    headers:
      - authorization
      - cookie
      - x-api-key
    query:
      - token
      - access_token

I would default allow_injection to false. Mountebank supports JavaScript injection for custom predicates and responses, but it only works when run with --allowInjection, and that is a security-sensitive footgun for an agent-facing tool. [GitHub]
The big design principle

The tool should treat mountebank as a mock runtime, not as the user interface.

Mountebank vocabulary:
text

imposter
stub
predicate
response
proxy
savedProxyResponses
savedRequests

Agent-facing vocabulary:
text

service
scenario
endpoint
matcher
fixture
recording
failure
assertion

That vocabulary shift is most of the product.
My recommended v0 build

For the quickest dirty-but-useful version, build these first:
v0.1
sh

mocksys init generic-http
mocksys record generic-http --target URL --scenario NAME
mocksys freeze generic-http/NAME
mocksys run generic-http/NAME
mocksys inspect generic-http/NAME

Internals:

    Spawn mb.
    Create a proxy imposter.
    Record traffic.
    Fetch imposter JSON from mountebank.
    Redact secrets.
    Save scenario.
    Re-run saved imposter later.

v0.2

Add templates:
sh

mocksys init github
mocksys init gitlab
mocksys init aws-s3

Add:
sh

mocksys doctor
mocksys env
mocksys requests
mocksys assert

v0.3

Add authoring:
sh

mocksys add
mocksys fault
mocksys parameterize
mocksys pack

What not to build yet

I would avoid these in the first cut:

    Full OpenAPI import.
    Full AWS API modeling.
    UI dashboard.
    Complex stateful mocks.
    Synthetic data generation beyond simple placeholders.
    Deep request-body schema inference.
    Custom JavaScript injection as a default path.
    Kubernetes operator / sidecar story.
    Multi-agent coordination features.

Those can come later. The agent-native core is: capture reality, remove secrets and volatility, name it, replay it, assert against it.

That is the wedge.
