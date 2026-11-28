# procomp

Local process orchestration and hybrid local/remote routing for working on a slice of a microservice estate.

## Language

### Core Entities

**Project**:
A configured microservice estate containing its Services, Profiles, Gateway definition, and scoped local state.
_Avoid_: Workspace, stack, environment

**Service**:
A logical, routable workload in a Project, defined by its identity, source repository, build/runtime templates, local port, gateway path, and remote URL.
_Avoid_: App, container, server

**Gateway**:
The local reverse-proxy router that fronts the hybrid service network and enforces the Routing Table for Services, independent of how Service Processes are owned or managed.
_Avoid_: Proxy service, WireMock service

**Process**:
An OS process instance (PID, command line, parent chain, port binding, log stream, and health) that backs a locally running Service, a local Gateway, or a Build Run.
_Avoid_: Instance, worker, daemon

**Build Run**:
A tracked build execution for a Service, recording its Process, status (`RUNNING`, `SUCCEEDED`, `FAILED`, `UNKNOWN`), timestamps, exit code, and build log.
_Avoid_: Build state, compile job

**Artifact**:
A runnable file on disk produced for a Service by a Build Run and launched by a Process.
_Avoid_: Binary, JAR, bundle

**Repository**:
A local Git repository checkout on disk (optionally linked to a remote Git URL) that may be shared by one or more Services; optional for Services that run without a Git checkout.
_Avoid_: Repo, checkout, source folder

**Git Hook**:
A mandatory Git hook script declared by a Project and verified or installed across its cloned Repositories.
_Avoid_: Hook script, pre-commit rule

**Builder**:
A reusable build template defining how a class of Services is compiled or packaged into an Artifact (command `argv`, optional working directory, and default environment).
_Avoid_: Build tool, build preset, compiler

**Runtime**:
A reusable execution template defining how a class of Services is launched locally (command `argv`, health check path, optional artifact pattern, and default environment).
_Avoid_: Type, preset, runner

**Catalog**:
An external file listing Service definitions imported into a Project via explicit field mapping.
_Avoid_: AppsFile, service index

### Hybrid Network & Routing

**Route**:
A Service's declarative traffic destination — either `LOCAL` (forwarding to the Service's local port) or `REMOTE` (proxying to its shared remote URL) — with no silent fallback when a target is unavailable.
_Avoid_: Mode, enabled, routing mode

**Routing Table**:
The persisted set of desired Routes for all Services in a Project, serving as the source of truth for the Gateway.
_Avoid_: Gateway state, route cache

**Profile**:
A named, reusable preset of Routes that switches a Project's Routing Table to a chosen local slice in one step.
_Avoid_: Preset, slice config

**Reconciliation**:
The act of converging the Gateway's live mappings to match the Project's Routing Table.
_Avoid_: Syncing, refreshing

### Ownership & Lifecycle

**Managed**:
A local Process launched and owned by `procomp`, which `procomp` is permitted to stop or restart.
_Avoid_: Owned, internal

**Unmanaged**:
A local Process running outside `procomp` (such as in an IDE or a developer's own shell) that is recognized as the Service or Gateway and is never killed by `procomp`.
_Avoid_: External, IDE-UP, foreign

**Conflict**:
An unrelated Process occupying a Service's local port that is neither Managed nor recognized as Unmanaged.
_Avoid_: Port-conflict, collision

**Health State**:
The observed runtime condition of a Service's local Process: `STOPPED` (no Process), `STARTING` (Process alive, health check not yet passing), `HEALTHY` (Process alive and passing health check, or no health check defined), or `CRASHED` (a Managed Process exited unexpectedly).
_Avoid_: StatusText, service state

**Stale**:
A condition where a Managed Process was started before the Service's latest Artifact was built.
_Avoid_: Outdated, dirty

### Observation Actions

**Discovery**:
The read-only action of determining what is currently running for a Service or Gateway, who owns it (`Managed`, `Unmanaged`, `Conflict`, or `None`), and its current Health State.
_Avoid_: Probing, polling

**Inspection**:
A deeper diagnostic look at a Process — including its command line, arguments, parent process chain, resource usage, and detailed health output — used for debugging and conflict resolution.
_Avoid_: Probing, status check
