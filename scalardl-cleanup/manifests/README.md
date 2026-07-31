# ScalarDL Cleanup — deployment guide

Deploy the `scalardl-cleanup` tool into the Kubernetes cluster that already runs your ScalarDL
Ledger and Auditor, and reclaim the space held by stale records (the Ledger's `coordinator.state`
table and the Auditor's `request_proof` table).

## Overview

The tool runs as one-shot Kubernetes `Job`s. There are three commands, split across the two
administrative domains (ADs):

| Command               | AD      | What it does                                                                            |
|-----------------------|---------|-----------------------------------------------------------------------------------------|
| `finalize-ledger`     | Ledger  | Drives every unfinished record to a terminal state; prints a **completion token**.      |
| `finalize-auditor`    | Auditor | Releases every unreleased asset lock, then deletes settled `request_proof` records; prints a **completion token**.                    |
| `cleanup-coordinator` | Ledger  | Deletes the `coordinator.state` records that are safe to remove. Needs **both** tokens. |

- **Two independent sides.** The Ledger commands and the Auditor command are deployed and run
  separately — each into its own AD's namespace, by that AD's operator, with that AD's own
  credentials. Nothing (namespace, Secret, or volume) is shared between the two ADs.
- **Cross-AD token handoff.** `cleanup-coordinator` needs the completion tokens from *both*
  `finalize-ledger` and `finalize-auditor`; the Auditor operator passes their token to the Ledger
  operator out-of-band (chat, ticket, …).
- **Resumable.** Each Job records progress on a checkpoint volume, so a failed run continues from
  where it stopped when you re-apply it.

## Manifest files

The manifests are split into one directory per administrative domain — each operator applies only
their own domain's directory:

**`ledger-domain/`** (Ledger AD)

| File                       | Kind                  | Purpose                                                             |
|----------------------------|-----------------------|---------------------------------------------------------------------|
| `pvc.yaml`                 | PersistentVolumeClaim | checkpoint volume (shared by both Ledger commands)                  |
| `configmap.yaml`           | ConfigMap             | config for the `finalize-ledger` and `cleanup-coordinator` commands |
| `finalize-ledger.yaml`     | Job                   | runs `finalize-ledger`                                              |
| `cleanup-coordinator.yaml` | Job                   | runs `cleanup-coordinator`                                          |

**`auditor-domain/`** (Auditor AD)

| File                    | Kind                  | Purpose                                   |
|-------------------------|-----------------------|-------------------------------------------|
| `pvc.yaml`              | PersistentVolumeClaim | checkpoint volume                         |
| `configmap.yaml`        | ConfigMap             | config for the `finalize-auditor` command |
| `finalize-auditor.yaml` | Job                   | runs `finalize-auditor`                   |

## Prerequisites

- Your ScalarDL Ledger and Auditor run on **Cosmos DB** — the only backend this tool currently supports.
- On the **Ledger** side, ScalarDB uses the Consensus Commit transaction manager
  (`scalar.db.transaction_manager=consensus-commit`, the default) — `finalize-ledger` and
  `cleanup-coordinator` reject any other at runtime.
- On the **Ledger** side, Coordinator group commit is disabled
  (`scalar.db.consensus_commit.coordinator.group_commit.enabled=false`, the default) — the tool does
  not support it and rejects it at runtime.
- The cluster's default `StorageClass` provides **`ReadWriteOnce`** volumes and honours `fsGroup`, so the non-root
  container can write the checkpoint.
- Each Job requests **2 vCPU and 4 GiB of memory** (`requests` equal `limits`), so the target
  namespace must have that much schedulable capacity — and quota headroom if a `ResourceQuota` applies.
- `kubectl`, plus `envsubst` (from `gettext`) and `jq` on your machine.

## What you can configure

Configuration falls into two groups: **deploy-time variables** you set in your shell, and
the **settings each command reads** from its `.properties` (supplied via the ConfigMap or the Secret).

### 1. Deploy-time variables

| Variable                         | Used in                                  | Meaning                                                              |
|----------------------------------|------------------------------------------|----------------------------------------------------------------------|
| `CLEANUP_VERSION`                | the Job manifests                        | image tag — `ghcr.io/scalar-labs/scalardl-cleanup:<CLEANUP_VERSION>` |
| `LEDGER_TOKEN` / `AUDITOR_TOKEN` | `ledger-domain/cleanup-coordinator.yaml` | the two completion tokens                                            |

### 2. Settings the commands accept

Each command reads these properties from its `.properties` file (the AD's ConfigMap —
`ledger-domain/configmap.yaml` or `auditor-domain/configmap.yaml`).
`scalar.db.storage` is preset to `cosmos`. Each row links to what the property means and its accepted
values in the ScalarDB / ScalarDL configuration reference.

| Property                                          | Accepted by        | Requirement                                                                                               |
|---------------------------------------------------|--------------------|-----------------------------------------------------------------------------------------------------------|
| `scalar.db.contact_points`                        | all commands       | Required. See <https://scalardb.scalar-labs.com/docs/latest/configurations/#contact_points-2>             |
| `scalar.db.password`                              | all commands       | Required. See <https://scalardb.scalar-labs.com/docs/latest/configurations/#password-2>                   |
| `scalar.dl.client.auditor.host`                   | `finalize-auditor` | Required. See <https://scalardl.scalar-labs.com/docs/latest/configurations/#auditorhost>                  |
| `scalar.dl.auditor.namespace`                     | `finalize-auditor` | Optional. See <https://scalardl.scalar-labs.com/docs/latest/configurations/#namespace-1>                  |
| `scalar.dl.client.auditor.tls.enabled`            | `finalize-auditor` | Optional. See <https://scalardl.scalar-labs.com/docs/latest/configurations/#auditortlsenabled>            |
| `scalar.dl.client.auditor.tls.ca_root_cert_path`  | `finalize-auditor` | Optional. See <https://scalardl.scalar-labs.com/docs/latest/configurations/#auditortlsca_root_cert_path>  |
| `scalar.dl.client.auditor.tls.override_authority` | `finalize-auditor` | Optional. See <https://scalardl.scalar-labs.com/docs/latest/configurations/#auditortlsoverride_authority> |
| `scalar.dl.client.auditor.authorization.credential` | `finalize-auditor` | Optional. See <https://scalardl.scalar-labs.com/docs/latest/configurations/#auditorauthorizationcredential> |

## Deploying

Run the steps in order, from the `manifests/` directory (the file paths below are relative to it).
**Steps 1 and 2 are independent** (either operator can go first, or in parallel); **step 3 needs
both tokens.** Each step exports the variables it needs at the top; `CLEANUP_VERSION` is the image
tag (`ghcr.io/scalar-labs/scalardl-cleanup:<CLEANUP_VERSION>`).

### Step 1 — Ledger AD: `finalize-ledger`

Set the variables this step uses:

```bash
export LEDGER_NS=<ledger-namespace> CLEANUP_VERSION=<image-tag>
```

**a. Credentials.** Create the Secret holding the Cosmos DB key:

```bash
kubectl -n "${LEDGER_NS:?}" create secret generic scalardl-cleanup-credentials \
  --from-literal=SCALAR_DB_PASSWORD='<cosmos-primary-key>'
```

**b. Checkpoint volume.**

```bash
kubectl -n "${LEDGER_NS:?}" apply -f ledger-domain/pvc.yaml
```

**c. Config.** Set `scalar.db.contact_points` in `ledger-domain/configmap.yaml`, then apply it:

```bash
kubectl -n "${LEDGER_NS:?}" apply -f ledger-domain/configmap.yaml
```

**d. Run.** Apply the Job:

```bash
: "${CLEANUP_VERSION:?}" && envsubst < ledger-domain/finalize-ledger.yaml | kubectl -n "${LEDGER_NS:?}" apply -f -
```

Wait for the Job to complete, then read the completion token from the log of its succeeded Pod:

```bash
pod=$(kubectl -n "${LEDGER_NS:?}" get pod -l job-name=scalardl-finalize-ledger \
  --field-selector=status.phase=Succeeded -o jsonpath='{.items[0].metadata.name}')
kubectl -n "${LEDGER_NS:?}" logs "$pod" | jq -rR 'fromjson? | .output.completion_token // empty'
```

Keep the printed token — it is the **Ledger token**.

### Step 2 — Auditor AD: `finalize-auditor`

Set the variables this step uses:

```bash
export AUDITOR_NS=<auditor-namespace> CLEANUP_VERSION=<image-tag>
```

**a. Credentials.** Create the Secret holding the Cosmos DB key:

```bash
kubectl -n "${AUDITOR_NS:?}" create secret generic scalardl-cleanup-credentials \
  --from-literal=SCALAR_DB_PASSWORD='<cosmos-primary-key>'
```

**b. Checkpoint volume.**

```bash
kubectl -n "${AUDITOR_NS:?}" apply -f auditor-domain/pvc.yaml
```

**c. Config.** Set `scalar.db.contact_points` and `scalar.dl.client.auditor.host` in `auditor-domain/configmap.yaml` (
for TLS,
also uncomment the TLS properties listed in the optional note below), then apply it:

```bash
kubectl -n "${AUDITOR_NS:?}" apply -f auditor-domain/configmap.yaml
```

**Optional — TLS to the Auditor.** If TLS is enabled on the Auditor, do this *before* step (d).

In `auditor-domain/configmap.yaml` (step c), uncomment:

- `scalar.dl.client.auditor.tls.enabled=true`
- `scalar.dl.client.auditor.tls.ca_root_cert_path=/cert/scalardl-cleanup/tls.crt`
- `scalar.dl.client.auditor.tls.override_authority=<auditor-cert-cn-or-san>` — set the value; only if it differs from the host you connect to

Then create the cert Secret holding the CA root cert that signed the Auditor's server cert.

```bash
kubectl -n "${AUDITOR_NS:?}" create secret generic scalardl-cleanup-cert --from-file=tls.crt=<ca.pem>
```

**Optional — Auditor authorization credential.** If the Auditor requires an authorization credential, do this *before* step (d).

In `auditor-domain/configmap.yaml` (step c), uncomment:

- `scalar.dl.client.auditor.authorization.credential=${env:SCALAR_DL_CLIENT_AUDITOR_AUTHORIZATION_CREDENTIAL}`

Then add the credential to the Secret created in step (a):

```bash
kubectl -n "${AUDITOR_NS:?}" patch secret scalardl-cleanup-credentials --type merge \
  -p '{"stringData":{"SCALAR_DL_CLIENT_AUDITOR_AUTHORIZATION_CREDENTIAL":"<credential>"}}'
```

**d. Run.** Apply the Job:

```bash
: "${CLEANUP_VERSION:?}" && envsubst < auditor-domain/finalize-auditor.yaml | kubectl -n "${AUDITOR_NS:?}" apply -f -
```

Wait for the Job to complete, then read the completion token from the log of its succeeded Pod:

```bash
pod=$(kubectl -n "${AUDITOR_NS:?}" get pod -l job-name=scalardl-finalize-auditor \
  --field-selector=status.phase=Succeeded -o jsonpath='{.items[0].metadata.name}')
kubectl -n "${AUDITOR_NS:?}" logs "$pod" | jq -rR 'fromjson? | .output.completion_token // empty'
```

Hand the printed token — the **Auditor token** — to the Ledger operator.

### Step 3 — Ledger AD: `cleanup-coordinator`

Run this once you have **both** tokens; it reuses the checkpoint volume, ConfigMap, and Secret from
Step 1. Set the variables, including both tokens:

```bash
export LEDGER_NS=<ledger-namespace> CLEANUP_VERSION=<image-tag>
export LEDGER_TOKEN=<ledger-token> AUDITOR_TOKEN=<auditor-token>
```

Apply the Job, then wait for it to complete:

```bash
: "${CLEANUP_VERSION:?}" "${LEDGER_TOKEN:?}" "${AUDITOR_TOKEN:?}" && envsubst < ledger-domain/cleanup-coordinator.yaml | kubectl -n "${LEDGER_NS:?}" apply -f -
```

## Re-running after a failure

Each Job records progress on its checkpoint volume, which is a separate object and is kept when the
Job is deleted. Deleting and re-applying a failed Job therefore **resumes where it stopped**.
Re-export the same variables the original step used, then delete and re-apply the failed command's
Job:

**`finalize-ledger` (Ledger AD)**

```bash
kubectl -n "${LEDGER_NS:?}" delete job/scalardl-finalize-ledger
: "${CLEANUP_VERSION:?}" && envsubst < ledger-domain/finalize-ledger.yaml | kubectl -n "${LEDGER_NS:?}" apply -f -
```

**`finalize-auditor` (Auditor AD)**

```bash
kubectl -n "${AUDITOR_NS:?}" delete job/scalardl-finalize-auditor
: "${CLEANUP_VERSION:?}" && envsubst < auditor-domain/finalize-auditor.yaml | kubectl -n "${AUDITOR_NS:?}" apply -f -
```

**`cleanup-coordinator` (Ledger AD)**

```bash
kubectl -n "${LEDGER_NS:?}" delete job/scalardl-cleanup-coordinator
: "${CLEANUP_VERSION:?}" "${LEDGER_TOKEN:?}" "${AUDITOR_TOKEN:?}" && envsubst < ledger-domain/cleanup-coordinator.yaml | kubectl -n "${LEDGER_NS:?}" apply -f -
```

On a resumed `cleanup-coordinator`, the token values are ignored — the deletion boundary saved in the
checkpoint is authoritative.

### Starting over from scratch

To discard the saved progress and start fresh, delete the Job **and** its checkpoint volume,
re-create the volume, then re-apply the Job (the matching block above). The Ledger checkpoint volume
is shared by both Ledger commands, so resetting it affects `finalize-ledger` and `cleanup-coordinator`
together.

**Ledger AD**

```bash
kubectl -n "${LEDGER_NS:?}" delete job/scalardl-finalize-ledger   # and/or job/scalardl-cleanup-coordinator
kubectl -n "${LEDGER_NS:?}" delete pvc/scalardl-cleanup-checkpoint
kubectl -n "${LEDGER_NS:?}" apply -f ledger-domain/pvc.yaml
```

**Auditor AD**

```bash
kubectl -n "${AUDITOR_NS:?}" delete job/scalardl-finalize-auditor
kubectl -n "${AUDITOR_NS:?}" delete pvc/scalardl-cleanup-checkpoint
kubectl -n "${AUDITOR_NS:?}" apply -f auditor-domain/pvc.yaml
```
