package procomp.core.project;

import procomp.core.common.DomainError;
import procomp.core.common.Result;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pure resolver that validates and transforms RawProjectConfig + Catalog items + env files into a Project.
 */
public final class ProjectResolver {

    public record EnvFileTarget(Path path, boolean optional, String context) {}

    public record ServiceDraft(
        String name,
        RawProjectConfig.RawServiceConfig mergedConfig,
        Optional<RepositoryRef> repository,
        Path workingDir,
        Map<String, VarValue> effectiveVars,
        List<EnvFileTarget> serviceEnvFiles
    ) {}

    public record ResolutionPlan(
        Path configPath,
        Path configDir,
        Optional<Path> repositoriesRoot,
        RawProjectConfig rawConfig,
        List<EnvFileTarget> topEnvFiles,
        List<ServiceDraft> serviceDrafts
    ) {
        public List<EnvFileTarget> allEnvFileTargets() {
            List<EnvFileTarget> all = new ArrayList<>(topEnvFiles);
            for (ServiceDraft draft : serviceDrafts) {
                all.addAll(draft.serviceEnvFiles());
            }
            return all;
        }
    }

    private ProjectResolver() {}

    public static Result<Optional<Path>, DomainError> resolveCatalogPath(Path configDir, RawProjectConfig rawConfig) {
        if (rawConfig.catalog().isEmpty()) {
            return Result.ok(Optional.empty());
        }
        Optional<Path> reposRoot = resolveRepositoriesRoot(configDir, rawConfig);
        Map<String, VarValue> pathVars = new LinkedHashMap<>();
        pathVars.put("configDir", new VarValue.Scalar(configDir.toString()));
        reposRoot.ifPresent(p -> pathVars.put("repositoriesRoot", new VarValue.Scalar(p.toString())));

        Result<String, DomainError> expanded = TemplateExpander.expandScalar(
            rawConfig.catalog().get().path(),
            pathVars,
            Set.of(),
            "catalog.path"
        );
        if (expanded.isErr()) {
            return castErr(expanded);
        }
        Path resolved = configDir.resolve(okVal(expanded)).normalize();
        return Result.ok(Optional.of(resolved));
    }

    public static Result<Project, DomainError> resolve(
        Path configPath,
        RawProjectConfig rawConfig,
        Map<String, RawProjectConfig.RawServiceConfig> catalogServices,
        Map<Path, String> loadedEnvFiles,
        Map<String, String> hostEnv
    ) {
        Result<ResolutionPlan, DomainError> planRes = planResolution(configPath, rawConfig, catalogServices);
        if (planRes.isErr()) {
            return castErr(planRes);
        }
        ResolutionPlan plan = okVal(planRes);
        for (EnvFileTarget target : plan.allEnvFileTargets()) {
            if (!target.optional() && !loadedEnvFiles.containsKey(target.path())) {
                return Result.err(DomainError.of(
                    "CONFIG_MISSING_ENV_FILE",
                    "Required envFile '" + target.path() + "' not found (" + target.context() + ")",
                    Map.of("path", target.path().toString(), "context", target.context())
                ));
            }
        }
        return finalizeProject(plan, loadedEnvFiles, hostEnv);
    }

    public static Result<ResolutionPlan, DomainError> planResolution(
        Path configPath,
        RawProjectConfig rawConfig,
        Map<String, RawProjectConfig.RawServiceConfig> catalogServices
    ) {
        Path normalizedConfigPath = configPath.normalize();
        Path configDir = normalizedConfigPath.getParent();
        if (configDir == null) {
            return Result.err(DomainError.of("CONFIG_INVALID_PATH", "Config path must have a parent directory"));
        }
        Optional<Path> reposRoot = resolveRepositoriesRoot(configDir, rawConfig);

        Map<String, VarValue> baseVars = new LinkedHashMap<>(rawConfig.defaults().vars());
        baseVars.put("configDir", new VarValue.Scalar(configDir.toString()));
        reposRoot.ifPresent(p -> baseVars.put("repositoriesRoot", new VarValue.Scalar(p.toString())));

        List<EnvFileTarget> topEnvTargets = new ArrayList<>();
        for (int i = 0; i < rawConfig.envFiles().size(); i++) {
            EnvFileSpec spec = rawConfig.envFiles().get(i);
            Result<String, DomainError> pRes = TemplateExpander.expandScalar(spec.path(), baseVars, Set.of(), "envFiles[" + i + "]");
            if (pRes.isErr()) return castErr(pRes);
            Path resolved = configDir.resolve(okVal(pRes)).normalize();
            topEnvTargets.add(new EnvFileTarget(resolved, spec.optional(), "envFiles[" + i + "]"));
        }

        Map<String, RawProjectConfig.RawServiceConfig> mergedServices = mergeServices(catalogServices, rawConfig.services());
        List<ServiceDraft> drafts = new ArrayList<>();

        for (Map.Entry<String, RawProjectConfig.RawServiceConfig> entry : mergedServices.entrySet()) {
            String svcName = entry.getKey();
            RawProjectConfig.RawServiceConfig svc = entry.getValue();

            if (svc.runtime().isEmpty()) {
                return Result.err(DomainError.of(
                    "CONFIG_MISSING_FIELD",
                    "Service '" + svcName + "' is missing required 'runtime'",
                    Map.of("service", svcName, "field", "services." + svcName + ".runtime")
                ));
            }
            String runtimeName = svc.runtime().get();
            RawProjectConfig.RawRuntimeConfig runtimeCfg = rawConfig.runtimes().get(runtimeName);
            if (runtimeCfg == null) {
                return Result.err(DomainError.of(
                    "CONFIG_UNKNOWN_RUNTIME",
                    "Service '" + svcName + "' references unknown runtime '" + runtimeName + "'",
                    Map.of("service", svcName, "runtime", runtimeName)
                ));
            }

            Result<Optional<RepositoryRef>, DomainError> repoRes = deriveRepository(svcName, svc, configDir, reposRoot, baseVars);
            if (repoRes.isErr()) return castErr(repoRes);
            Optional<RepositoryRef> repoRef = okVal(repoRes);

            Map<String, VarValue> effectiveVars = new LinkedHashMap<>(rawConfig.defaults().vars());
            effectiveVars.putAll(svc.vars());
            effectiveVars.put("configDir", new VarValue.Scalar(configDir.toString()));
            reposRoot.ifPresent(p -> effectiveVars.put("repositoriesRoot", new VarValue.Scalar(p.toString())));
            effectiveVars.put("service", new VarValue.Scalar(svcName));
            svc.port().ifPresent(p -> effectiveVars.put("port", new VarValue.Scalar(Integer.toString(p))));
            repoRef.ifPresent(r -> effectiveVars.put("repository", new VarValue.Scalar(r.root().toString())));

            Result<Path, DomainError> wdRes = resolveServiceWorkingDir(
                svcName, svc, runtimeCfg, rawConfig.defaults(), repoRef, configDir, effectiveVars
            );
            if (wdRes.isErr()) return castErr(wdRes);
            Path workingDir = okVal(wdRes);

            List<EnvFileTarget> svcEnvTargets = new ArrayList<>();
            for (int i = 0; i < svc.envFiles().size(); i++) {
                EnvFileSpec spec = svc.envFiles().get(i);
                String ctx = "services." + svcName + ".envFiles[" + i + "]";
                Result<String, DomainError> pRes = TemplateExpander.expandScalar(spec.path(), effectiveVars, Set.of(), ctx);
                if (pRes.isErr()) return castErr(pRes);
                Path resolved = workingDir.resolve(okVal(pRes)).normalize();
                svcEnvTargets.add(new EnvFileTarget(resolved, spec.optional(), ctx));
            }

            drafts.add(new ServiceDraft(svcName, svc, repoRef, workingDir, effectiveVars, svcEnvTargets));
        }

        return Result.ok(new ResolutionPlan(
            normalizedConfigPath, configDir, reposRoot, rawConfig, topEnvTargets, drafts
        ));
    }

    public static Result<Project, DomainError> finalizeProject(
        ResolutionPlan plan,
        Map<Path, String> loadedEnvFiles,
        Map<String, String> hostEnv
    ) {
        RawProjectConfig rawConfig = plan.rawConfig();

        Result<Map<Path, Map<String, String>>, DomainError> parsedEnvFilesRes = parseAllLoadedEnvFiles(plan, loadedEnvFiles);
        if (parsedEnvFilesRes.isErr()) return castErr(parsedEnvFilesRes);
        Map<Path, Map<String, String>> parsedEnvFiles = okVal(parsedEnvFilesRes);

        Map<String, String> topEnvFileOverlay = new LinkedHashMap<>();
        for (EnvFileTarget t : plan.topEnvFiles()) {
            Map<String, String> entries = parsedEnvFiles.get(t.path());
            if (entries != null) {
                topEnvFileOverlay.putAll(entries);
            }
        }

        // Fail fast on unset ${env:VAR} even if a default or runtime has no referencing services
        Result<procomp.core.common.Unit, DomainError> defaultEnvCheck = validateHostEnvRefs(
            rawConfig.defaults().env(), hostEnv, "defaults.env"
        );
        if (defaultEnvCheck.isErr()) return castErr(defaultEnvCheck);

        Map<String, RuntimeDefinition> resolvedRuntimes = new LinkedHashMap<>();
        for (Map.Entry<String, RawProjectConfig.RawRuntimeConfig> entry : rawConfig.runtimes().entrySet()) {
            String rtName = entry.getKey();
            RawProjectConfig.RawRuntimeConfig rt = entry.getValue();
            Result<Map<String, String>, DomainError> rtEnvRes = expandHostEnvMap(rt.env(), hostEnv, "runtimes." + rtName + ".env");
            if (rtEnvRes.isErr()) return castErr(rtEnvRes);
            resolvedRuntimes.put(rtName, new RuntimeDefinition(
                rtName, rt.command(), rt.healthPath(), rt.artifactGlob(),
                rt.artifactExclude(), rt.workingDir(), SecretEnv.of(okVal(rtEnvRes))
            ));
        }

        Result<Optional<GatewayDefinition>, DomainError> gwRes = resolveGateway(plan, topEnvFileOverlay, hostEnv);
        if (gwRes.isErr()) return castErr(gwRes);
        Optional<GatewayDefinition> gateway = okVal(gwRes);

        Map<String, ServiceDefinition> services = new LinkedHashMap<>();
        Map<Path, RepositoryRef> repoByRoot = new LinkedHashMap<>();

        for (ServiceDraft draft : plan.serviceDrafts()) {
            Result<ServiceDefinition, DomainError> svcRes = resolveService(
                draft, rawConfig, parsedEnvFiles, topEnvFileOverlay, hostEnv
            );
            if (svcRes.isErr()) return castErr(svcRes);
            ServiceDefinition svc = okVal(svcRes);
            services.put(svc.name(), svc);

            if (svc.repository().isPresent()) {
                RepositoryRef ref = svc.repository().get();
                RepositoryRef existing = repoByRoot.get(ref.root());
                if (existing == null) {
                    repoByRoot.put(ref.root(), ref);
                } else if (existing.remoteUrl().isEmpty() && ref.remoteUrl().isPresent()) {
                    repoByRoot.put(ref.root(), ref);
                } else if (existing.remoteUrl().isPresent()
                    && ref.remoteUrl().isPresent()
                    && !existing.remoteUrl().get().equals(ref.remoteUrl().get())) {
                    return Result.err(DomainError.of(
                        "CONFIG_CONFLICTING_REPOSITORY",
                        "Conflicting repositoryUrl values map to the same local root '" + ref.root() + "'",
                        Map.of("root", ref.root().toString(), "service", svc.name())
                    ));
                }
            }
        }

        for (ProfileDefinition profile : rawConfig.profiles().values()) {
            for (String svcName : profile.localServices()) {
                if (!services.containsKey(svcName)) {
                    return Result.err(DomainError.of(
                        "CONFIG_UNKNOWN_SERVICE",
                        "Profile '" + profile.name() + "' references unknown service '" + svcName + "'",
                        Map.of("profile", profile.name(), "service", svcName)
                    ));
                }
            }
        }

        return Result.ok(new Project(
            rawConfig.project(),
            plan.configPath(),
            plan.configDir(),
            plan.repositoriesRoot(),
            resolvedRuntimes,
            gateway,
            services,
            rawConfig.profiles(),
            List.copyOf(repoByRoot.values())
        ));
    }

    private static Result<ServiceDefinition, DomainError> resolveService(
        ServiceDraft draft,
        RawProjectConfig rawConfig,
        Map<Path, Map<String, String>> parsedEnvFiles,
        Map<String, String> topEnvFileOverlay,
        Map<String, String> hostEnv
    ) {
        String name = draft.name();
        RawProjectConfig.RawServiceConfig svc = draft.mergedConfig();
        String rtName = svc.runtime().orElseThrow();
        RawProjectConfig.RawRuntimeConfig rt = rawConfig.runtimes().get(rtName);
        Map<String, VarValue> vars = draft.effectiveVars();

        Result<Optional<String>, DomainError> artifactGlobRes = expandOptionalScalar(
            rt.artifactGlob(), vars, Set.of(), "runtimes." + rtName + ".artifactGlob"
        );
        if (artifactGlobRes.isErr()) return castErr(artifactGlobRes);
        Optional<String> artifactGlob = okVal(artifactGlobRes);

        Set<String> deferredCmdVars = artifactGlob.isPresent() ? Set.of("artifact") : Set.of();
        Result<List<String>, DomainError> cmdRes = TemplateExpander.expandArgv(
            rt.command(), vars, deferredCmdVars, "services." + name + ".command"
        );
        if (cmdRes.isErr()) return castErr(cmdRes);

        Optional<String> rawHealth = svc.healthPath().isPresent() ? svc.healthPath() : rt.healthPath();
        Result<Optional<String>, DomainError> healthRes = expandOptionalScalar(
            rawHealth, vars, Set.of(), "services." + name + ".healthPath"
        );
        if (healthRes.isErr()) return castErr(healthRes);

        String rawProxy = svc.proxyPath()
            .or(() -> rawConfig.defaults().proxyPathTemplate())
            .orElse("/{{service}}");
        Result<String, DomainError> proxyRes = TemplateExpander.expandScalar(
            rawProxy, vars, Set.of(), "services." + name + ".proxyPath"
        );
        if (proxyRes.isErr()) return castErr(proxyRes);
        String proxyPath = okVal(proxyRes);
        if (proxyPath.isBlank()) {
            return Result.err(DomainError.of(
                "CONFIG_INVALID_VALUE",
                "Resolved proxyPath for service '" + name + "' must not be blank",
                Map.of("service", name, "field", "proxyPath")
            ));
        }
        if (!proxyPath.startsWith("/")) {
            proxyPath = "/" + proxyPath;
        }

        Optional<String> rawRemoteUrl = svc.remoteUrl().isPresent()
            ? svc.remoteUrl()
            : rawConfig.defaults().remoteUrlTemplate();
        Result<Optional<String>, DomainError> remoteUrlRes = expandOptionalScalar(
            rawRemoteUrl, vars, Set.of(), "services." + name + ".remoteUrl"
        );
        if (remoteUrlRes.isErr()) return castErr(remoteUrlRes);

        Map<String, String> envFromFiles = new LinkedHashMap<>(topEnvFileOverlay);
        for (EnvFileTarget target : draft.serviceEnvFiles()) {
            Map<String, String> entries = parsedEnvFiles.get(target.path());
            if (entries != null) {
                envFromFiles.putAll(entries);
            }
        }

        Map<String, String> configEnvLayer = new LinkedHashMap<>(rawConfig.defaults().env());
        configEnvLayer.putAll(rt.env());
        configEnvLayer.putAll(svc.env());

        Map<String, String> finalEnv = new LinkedHashMap<>(envFromFiles);
        for (Map.Entry<String, String> entry : configEnvLayer.entrySet()) {
            String ctx = "services." + name + ".env." + entry.getKey();
            Result<String, DomainError> hostExpanded = EnvFileParser.expandHostEnvRefs(entry.getValue(), hostEnv, ctx);
            if (hostExpanded.isErr()) return castErr(hostExpanded);
            Result<String, DomainError> tplExpanded = TemplateExpander.expandScalar(okVal(hostExpanded), vars, Set.of(), ctx);
            if (tplExpanded.isErr()) return castErr(tplExpanded);
            finalEnv.put(entry.getKey(), okVal(tplExpanded));
        }

        return Result.ok(new ServiceDefinition(
            name,
            svc.port(),
            rtName,
            draft.repository(),
            draft.workingDir(),
            okVal(healthRes),
            proxyPath,
            okVal(remoteUrlRes),
            artifactGlob,
            rt.artifactExclude(),
            okVal(cmdRes),
            SecretEnv.of(finalEnv),
            svc.envFiles()
        ));
    }

    private static Result<Optional<GatewayDefinition>, DomainError> resolveGateway(
        ResolutionPlan plan,
        Map<String, String> topEnvFileOverlay,
        Map<String, String> hostEnv
    ) {
        if (plan.rawConfig().gateway().isEmpty()) {
            return Result.ok(Optional.empty());
        }
        RawProjectConfig.RawGatewayConfig gw = plan.rawConfig().gateway().get();
        Map<String, VarValue> gwVars = new LinkedHashMap<>(plan.rawConfig().defaults().vars());
        gwVars.put("configDir", new VarValue.Scalar(plan.configDir().toString()));
        plan.repositoriesRoot().ifPresent(p -> gwVars.put("repositoriesRoot", new VarValue.Scalar(p.toString())));
        gwVars.put("port", new VarValue.Scalar(Integer.toString(gw.port())));

        Result<List<String>, DomainError> cmdRes = TemplateExpander.expandArgv(
            gw.command(), gwVars, Set.of(), "gateway.command"
        );
        if (cmdRes.isErr()) return castErr(cmdRes);

        Optional<Path> workingDir = Optional.empty();
        if (gw.workingDir().isPresent()) {
            Result<String, DomainError> wdRes = TemplateExpander.expandScalar(
                gw.workingDir().get(), gwVars, Set.of(), "gateway.workingDir"
            );
            if (wdRes.isErr()) return castErr(wdRes);
            workingDir = Optional.of(plan.configDir().resolve(okVal(wdRes)).normalize());
        }

        Result<Optional<String>, DomainError> healthRes = expandOptionalScalar(
            gw.healthPath(), gwVars, Set.of(), "gateway.healthPath"
        );
        if (healthRes.isErr()) return castErr(healthRes);

        Map<String, String> gwEnv = new LinkedHashMap<>(topEnvFileOverlay);
        for (Map.Entry<String, String> entry : gw.env().entrySet()) {
            String ctx = "gateway.env." + entry.getKey();
            Result<String, DomainError> hostExpanded = EnvFileParser.expandHostEnvRefs(entry.getValue(), hostEnv, ctx);
            if (hostExpanded.isErr()) return castErr(hostExpanded);
            Result<String, DomainError> tplExpanded = TemplateExpander.expandScalar(okVal(hostExpanded), gwVars, Set.of(), ctx);
            if (tplExpanded.isErr()) return castErr(tplExpanded);
            gwEnv.put(entry.getKey(), okVal(tplExpanded));
        }

        return Result.ok(Optional.of(new GatewayDefinition(
            gw.type(),
            gw.port(),
            okVal(healthRes),
            okVal(cmdRes),
            workingDir,
            SecretEnv.of(gwEnv)
        )));
    }

    private static Result<Map<Path, Map<String, String>>, DomainError> parseAllLoadedEnvFiles(
        ResolutionPlan plan,
        Map<Path, String> loadedEnvFiles
    ) {
        Map<Path, Map<String, String>> parsed = new LinkedHashMap<>();
        for (EnvFileTarget target : plan.allEnvFileTargets()) {
            if (parsed.containsKey(target.path())) {
                continue;
            }
            String content = loadedEnvFiles.get(target.path());
            if (content == null) {
                continue;
            }
            Result<Map<String, String>, DomainError> res = EnvFileParser.parse(target.path().toString(), content);
            if (res.isErr()) return castErr(res);
            parsed.put(target.path(), okVal(res));
        }
        return Result.ok(parsed);
    }

    private static Optional<Path> resolveRepositoriesRoot(Path configDir, RawProjectConfig rawConfig) {
        return rawConfig.repositoriesRoot()
            .map(raw -> configDir.resolve(raw).normalize());
    }

    private static Map<String, RawProjectConfig.RawServiceConfig> mergeServices(
        Map<String, RawProjectConfig.RawServiceConfig> catalogServices,
        Map<String, RawProjectConfig.RawServiceConfig> inlineServices
    ) {
        Map<String, RawProjectConfig.RawServiceConfig> merged = new LinkedHashMap<>(catalogServices);
        for (Map.Entry<String, RawProjectConfig.RawServiceConfig> entry : inlineServices.entrySet()) {
            String name = entry.getKey();
            RawProjectConfig.RawServiceConfig override = entry.getValue();
            RawProjectConfig.RawServiceConfig base = merged.get(name);
            if (base == null) {
                merged.put(name, override);
            } else {
                Map<String, VarValue> mergedVars = new LinkedHashMap<>(base.vars());
                mergedVars.putAll(override.vars());
                Map<String, String> mergedEnv = new LinkedHashMap<>(base.env());
                mergedEnv.putAll(override.env());
                List<EnvFileSpec> mergedEnvFiles = new ArrayList<>(base.envFiles());
                mergedEnvFiles.addAll(override.envFiles());

                merged.put(name, new RawProjectConfig.RawServiceConfig(
                    override.port().isPresent() ? override.port() : base.port(),
                    override.runtime().isPresent() ? override.runtime() : base.runtime(),
                    override.repository().isPresent() ? override.repository() : base.repository(),
                    override.repositoryUrl().isPresent() ? override.repositoryUrl() : base.repositoryUrl(),
                    override.workingDir().isPresent() ? override.workingDir() : base.workingDir(),
                    override.healthPath().isPresent() ? override.healthPath() : base.healthPath(),
                    override.proxyPath().isPresent() ? override.proxyPath() : base.proxyPath(),
                    override.remoteUrl().isPresent() ? override.remoteUrl() : base.remoteUrl(),
                    mergedVars,
                    mergedEnv,
                    mergedEnvFiles
                ));
            }
        }
        return merged;
    }

    private static Result<Optional<RepositoryRef>, DomainError> deriveRepository(
        String svcName,
        RawProjectConfig.RawServiceConfig svc,
        Path configDir,
        Optional<Path> reposRoot,
        Map<String, VarValue> baseVars
    ) {
        if (svc.repository().isPresent()) {
            Result<String, DomainError> expanded = TemplateExpander.expandScalar(
                svc.repository().get(), baseVars, Set.of(), "services." + svcName + ".repository"
            );
            if (expanded.isErr()) return castErr(expanded);
            Path repoPath = configDir.resolve(okVal(expanded)).normalize();
            return Result.ok(Optional.of(new RepositoryRef(repoPath, svc.repositoryUrl())));
        }
        if (svc.repositoryUrl().isPresent()) {
            if (reposRoot.isEmpty()) {
                return Result.err(DomainError.of(
                    "CONFIG_MISSING_REPOSITORIES_ROOT",
                    "Service '" + svcName + "' sets repositoryUrl without repositoriesRoot or explicit repository",
                    Map.of("service", svcName)
                ));
            }
            Result<String, DomainError> basenameRes = extractRepoBasename(svc.repositoryUrl().get(), svcName);
            if (basenameRes.isErr()) return castErr(basenameRes);
            Path derived = reposRoot.get().resolve(okVal(basenameRes)).normalize();
            return Result.ok(Optional.of(new RepositoryRef(derived, svc.repositoryUrl())));
        }
        return Result.ok(Optional.empty());
    }

    static Result<String, DomainError> extractRepoBasename(String repositoryUrl, String svcName) {
        String trimmed = repositoryUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int slash = trimmed.lastIndexOf('/');
        int colon = trimmed.lastIndexOf(':');
        int sep = Math.max(slash, colon);
        String name = sep >= 0 ? trimmed.substring(sep + 1) : trimmed;
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - ".git".length());
        }
        if (name.isBlank() || name.equals(".") || name.equals("..")) {
            return Result.err(DomainError.of(
                "CONFIG_INVALID_VALUE",
                "Cannot derive repository directory name from repositoryUrl '" + repositoryUrl + "' for service '" + svcName + "'",
                Map.of("service", svcName, "repositoryUrl", repositoryUrl)
            ));
        }
        return Result.ok(name);
    }

    private static Result<Path, DomainError> resolveServiceWorkingDir(
        String svcName,
        RawProjectConfig.RawServiceConfig svc,
        RawProjectConfig.RawRuntimeConfig runtime,
        RawProjectConfig.RawDefaultsConfig defaults,
        Optional<RepositoryRef> repoRef,
        Path configDir,
        Map<String, VarValue> effectiveVars
    ) {
        Optional<String> template = svc.workingDir()
            .or(runtime::workingDir)
            .or(defaults::workingDir);
        if (template.isEmpty()) {
            if (repoRef.isPresent()) {
                return Result.ok(repoRef.get().root());
            }
            return Result.err(DomainError.of(
                "CONFIG_MISSING_WORKING_DIR",
                "Service '" + svcName + "' has no Repository and must specify workingDir",
                Map.of("service", svcName)
            ));
        }
        Result<String, DomainError> expanded = TemplateExpander.expandScalar(
            template.get(), effectiveVars, Set.of(), "services." + svcName + ".workingDir"
        );
        if (expanded.isErr()) return castErr(expanded);
        return Result.ok(configDir.resolve(okVal(expanded)).normalize());
    }

    private static Result<procomp.core.common.Unit, DomainError> validateHostEnvRefs(
        Map<String, String> envMap,
        Map<String, String> hostEnv,
        String context
    ) {
        for (Map.Entry<String, String> entry : envMap.entrySet()) {
            Result<String, DomainError> res = EnvFileParser.expandHostEnvRefs(
                entry.getValue(), hostEnv, context + "." + entry.getKey()
            );
            if (res.isErr()) return castErr(res);
        }
        return Result.ok(procomp.core.common.Unit.VALUE);
    }

    private static Result<Map<String, String>, DomainError> expandHostEnvMap(
        Map<String, String> envMap,
        Map<String, String> hostEnv,
        String context
    ) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : envMap.entrySet()) {
            Result<String, DomainError> res = EnvFileParser.expandHostEnvRefs(
                entry.getValue(), hostEnv, context + "." + entry.getKey()
            );
            if (res.isErr()) return castErr(res);
            out.put(entry.getKey(), okVal(res));
        }
        return Result.ok(out);
    }

    private static Result<Optional<String>, DomainError> expandOptionalScalar(
        Optional<String> raw,
        Map<String, VarValue> vars,
        Set<String> deferred,
        String context
    ) {
        if (raw.isEmpty()) {
            return Result.ok(Optional.empty());
        }
        return TemplateExpander.expandScalar(raw.get(), vars, deferred, context).map(Optional::of);
    }

    private static <T, U> Result<U, DomainError> castErr(Result<T, DomainError> err) {
        return err.fold(
            ignored -> { throw new IllegalStateException("Expected Err result"); },
            Result::err
        );
    }

    private static <T> T okVal(Result<T, DomainError> res) {
        return ((Result.Ok<T, DomainError>) res).value();
    }
}
