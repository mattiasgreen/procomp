package procomp.core.project;

import procomp.core.common.DomainError;
import procomp.core.common.Result;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure resolver that parses procomp.json, loads optional Catalog and envFiles via a supplied lookup,
 * validates templates and references, and produces an immutable Project.
 */
public final class ProjectResolver {

    /**
     * Pure file-lookup callback supplied by the caller (e.g. ConfigSource in shell/ or an in-memory Map in tests).
     * Returns {@code Result.ok(Optional.empty())} when the path does not exist as a regular file.
     */
    @FunctionalInterface
    public interface FileLookup {
        Result<Optional<String>, DomainError> readIfPresent(Path path);
    }

    private record EnvFileTarget(Path path, boolean optional, String context) {}

    private record ServiceDraft(
        String name,
        RawProjectConfig.RawServiceConfig mergedConfig,
        Optional<RepositoryRef> repository,
        Path workingDir,
        Map<String, VarValue> effectiveVars,
        List<EnvFileTarget> serviceEnvFiles
    ) {}

    private record ResolutionPlan(
        Path configPath,
        Path configDir,
        Optional<Path> repositoriesRoot,
        Optional<String> currentUser,
        RawProjectConfig rawConfig,
        Map<String, VarValue> expandedDefaultsVars,
        List<EnvFileTarget> topEnvFiles,
        List<ServiceDraft> serviceDrafts
    ) {
        List<EnvFileTarget> allEnvFileTargets() {
            List<EnvFileTarget> all = new ArrayList<>(topEnvFiles);
            for (ServiceDraft draft : serviceDrafts) {
                all.addAll(draft.serviceEnvFiles());
            }
            return all;
        }
    }

    private ProjectResolver() {}

    public static Result<Project, DomainError> resolve(
        Path configPath,
        String rawConfigJson,
        FileLookup fileLookup,
        Map<String, String> hostEnv
    ) {
        return resolve(configPath, rawConfigJson, fileLookup, hostEnv, Optional.empty());
    }

    public static Result<Project, DomainError> resolve(
        Path configPath,
        String rawConfigJson,
        FileLookup fileLookup,
        Map<String, String> hostEnv,
        Optional<String> currentUser
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Result<RawProjectConfig, DomainError> rawCfgRes = ProjectConfigParser.parse(rawConfigJson);
        if (rawCfgRes.isErr()) {
            return rawCfgRes.castError();
        }
        RawProjectConfig rawConfig = okVal(rawCfgRes);

        Path normalizedConfigPath = configPath.normalize();
        Path configDir = normalizedConfigPath.getParent();
        if (configDir == null) {
            return Result.err(DomainError.of("CONFIG_INVALID_PATH", "Config path must have a parent directory"));
        }

        Result<Map<String, RawProjectConfig.RawServiceConfig>, DomainError> catalogRes =
            loadCatalogServices(configDir, rawConfig, fileLookup);
        if (catalogRes.isErr()) {
            return catalogRes.castError();
        }

        Result<ResolutionPlan, DomainError> planRes =
            planResolution(normalizedConfigPath, configDir, rawConfig, okVal(catalogRes), hostEnv, currentUser);
        if (planRes.isErr()) {
            return planRes.castError();
        }
        ResolutionPlan plan = okVal(planRes);

        Result<Map<Path, Map<String, String>>, DomainError> envFilesRes = loadAndParseEnvFiles(plan, fileLookup);
        if (envFilesRes.isErr()) {
            return envFilesRes.castError();
        }

        return finalizeProject(plan, okVal(envFilesRes), hostEnv);
    }

    private static Result<Map<String, RawProjectConfig.RawServiceConfig>, DomainError> loadCatalogServices(
        Path configDir,
        RawProjectConfig rawConfig,
        FileLookup fileLookup
    ) {
        if (rawConfig.catalog().isEmpty()) {
            return Result.ok(Map.of());
        }
        Result<Path, DomainError> catPathRes = resolveCatalogPath(configDir, rawConfig);
        if (catPathRes.isErr()) {
            return catPathRes.castError();
        }
        Path catalogPath = okVal(catPathRes);

        Result<Optional<String>, DomainError> readRes = fileLookup.readIfPresent(catalogPath);
        if (readRes.isErr()) {
            return readRes.castError();
        }
        Optional<String> catJsonOpt = okVal(readRes);
        if (catJsonOpt.isEmpty()) {
            return Result.err(DomainError.of(
                "CATALOG_NOT_FOUND",
                "Catalog file not found at '" + catalogPath + "'",
                Map.of("path", catalogPath.toString())
            ));
        }
        return CatalogParser.parse(rawConfig.catalog().get(), catJsonOpt.get());
    }

    private static Result<Path, DomainError> resolveCatalogPath(Path configDir, RawProjectConfig rawConfig) {
        Result<Optional<Path>, DomainError> reposRootRes = resolveRepositoriesRoot(configDir, rawConfig);
        if (reposRootRes.isErr()) {
            return reposRootRes.castError();
        }
        Optional<Path> reposRoot = okVal(reposRootRes);
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
            return expanded.castError();
        }
        return resolvePath(configDir, okVal(expanded), "catalog.path");
    }

    private static Result<ResolutionPlan, DomainError> planResolution(
        Path normalizedConfigPath,
        Path configDir,
        RawProjectConfig rawConfig,
        Map<String, RawProjectConfig.RawServiceConfig> catalogServices,
        Map<String, String> hostEnv,
        Optional<String> currentUser
    ) {
        Result<Optional<Path>, DomainError> reposRootRes = resolveRepositoriesRoot(configDir, rawConfig);
        if (reposRootRes.isErr()) return reposRootRes.castError();
        Optional<Path> reposRoot = okVal(reposRootRes);

        Result<Map<String, VarValue>, DomainError> defaultVarsRes =
            expandHostEnvInVars(rawConfig.defaults().vars(), hostEnv, "defaults.vars");
        if (defaultVarsRes.isErr()) return defaultVarsRes.castError();
        Map<String, VarValue> expandedDefaultsVars = okVal(defaultVarsRes);

        Map<String, VarValue> baseVars = new LinkedHashMap<>(expandedDefaultsVars);
        baseVars.put("configDir", new VarValue.Scalar(configDir.toString()));
        reposRoot.ifPresent(p -> baseVars.put("repositoriesRoot", new VarValue.Scalar(p.toString())));
        currentUser.filter(s -> !s.isBlank()).ifPresent(u -> baseVars.put("user", new VarValue.Scalar(u)));

        List<EnvFileTarget> topEnvTargets = new ArrayList<>();
        Result<procomp.core.common.Unit, DomainError> topEnvRes = appendEnvFileTargets(
            rawConfig.envFiles(), "envFiles", configDir, baseVars, topEnvTargets
        );
        if (topEnvRes.isErr()) return topEnvRes.castError();

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

            Result<Map<String, VarValue>, DomainError> svcVarsRes =
                expandHostEnvInVars(svc.vars(), hostEnv, "services." + svcName + ".vars");
            if (svcVarsRes.isErr()) return svcVarsRes.castError();

            Map<String, VarValue> effectiveVars = new LinkedHashMap<>(expandedDefaultsVars);
            effectiveVars.putAll(okVal(svcVarsRes));
            effectiveVars.put("configDir", new VarValue.Scalar(configDir.toString()));
            reposRoot.ifPresent(p -> effectiveVars.put("repositoriesRoot", new VarValue.Scalar(p.toString())));
            currentUser.filter(s -> !s.isBlank()).ifPresent(u -> effectiveVars.put("user", new VarValue.Scalar(u)));
            effectiveVars.put("service", new VarValue.Scalar(svcName));
            svc.port().ifPresent(p -> effectiveVars.put("port", new VarValue.Scalar(Integer.toString(p))));

            Result<Optional<RepositoryRef>, DomainError> repoRes = deriveRepository(svcName, svc, configDir, reposRoot, effectiveVars);
            if (repoRes.isErr()) return repoRes.castError();
            Optional<RepositoryRef> repoRef = okVal(repoRes);
            repoRef.ifPresent(r -> effectiveVars.put("repository", new VarValue.Scalar(r.root().toString())));

            Result<Path, DomainError> wdRes = resolveServiceWorkingDir(
                svcName, svc, runtimeCfg, rawConfig.defaults(), repoRef, configDir, effectiveVars, hostEnv
            );
            if (wdRes.isErr()) return wdRes.castError();
            Path workingDir = okVal(wdRes);

            List<EnvFileTarget> svcEnvTargets = new ArrayList<>();
            Result<procomp.core.common.Unit, DomainError> defEnvRes = appendEnvFileTargets(
                rawConfig.defaults().envFiles(), "defaults.envFiles", workingDir, effectiveVars, svcEnvTargets
            );
            if (defEnvRes.isErr()) return defEnvRes.castError();
            Result<procomp.core.common.Unit, DomainError> svcEnvRes = appendEnvFileTargets(
                svc.envFiles(), "services." + svcName + ".envFiles", workingDir, effectiveVars, svcEnvTargets
            );
            if (svcEnvRes.isErr()) return svcEnvRes.castError();

            drafts.add(new ServiceDraft(svcName, svc, repoRef, workingDir, effectiveVars, svcEnvTargets));
        }

        return Result.ok(new ResolutionPlan(
            normalizedConfigPath, configDir, reposRoot, currentUser, rawConfig, expandedDefaultsVars, topEnvTargets, drafts
        ));
    }

    private static Result<procomp.core.common.Unit, DomainError> appendEnvFileTargets(
        List<EnvFileSpec> specs,
        String ctxPrefix,
        Path baseDir,
        Map<String, VarValue> vars,
        List<EnvFileTarget> out
    ) {
        for (int i = 0; i < specs.size(); i++) {
            EnvFileSpec spec = specs.get(i);
            String ctx = ctxPrefix + "[" + i + "]";
            Result<String, DomainError> pRes = TemplateExpander.expandScalar(spec.path(), vars, Set.of(), ctx);
            if (pRes.isErr()) return pRes.castError();
            Result<Path, DomainError> resolvedRes = resolvePath(baseDir, okVal(pRes), ctx);
            if (resolvedRes.isErr()) return resolvedRes.castError();
            out.add(new EnvFileTarget(okVal(resolvedRes), spec.optional(), ctx));
        }
        return Result.ok(procomp.core.common.Unit.VALUE);
    }

    private static Result<Project, DomainError> finalizeProject(
        ResolutionPlan plan,
        Map<Path, Map<String, String>> parsedEnvFiles,
        Map<String, String> hostEnv
    ) {
        RawProjectConfig rawConfig = plan.rawConfig();

        Map<String, String> topEnvFileOverlay = new LinkedHashMap<>();
        for (EnvFileTarget t : plan.topEnvFiles()) {
            Map<String, String> entries = parsedEnvFiles.get(t.path());
            if (entries != null) {
                topEnvFileOverlay.putAll(entries);
            }
        }

        // Fail fast on unset ${env:VAR} and undefined {{var}} templates even if defaults or runtimes are overridden/unused
        Map<String, VarValue> validationVars = buildTemplateValidationVars(plan);
        Result<procomp.core.common.Unit, DomainError> defaultsCheck = validateDefaultsTemplates(
            rawConfig.defaults(), validationVars, hostEnv
        );
        if (defaultsCheck.isErr()) return defaultsCheck.castError();

        if (rawConfig.defaults().builder().isPresent()) {
            String defaultBuilder = rawConfig.defaults().builder().get();
            if (!rawConfig.builders().containsKey(defaultBuilder)) {
                return Result.err(DomainError.of(
                    "CONFIG_UNKNOWN_BUILDER",
                    "defaults.builder references unknown builder '" + defaultBuilder + "'",
                    Map.of("field", "defaults.builder", "builder", defaultBuilder)
                ));
            }
        }

        Map<String, BuilderDefinition> resolvedBuilders = new LinkedHashMap<>();
        for (Map.Entry<String, RawProjectConfig.RawBuilderConfig> entry : rawConfig.builders().entrySet()) {
            String builderName = entry.getKey();
            RawProjectConfig.RawBuilderConfig b = entry.getValue();
            Result<Map<String, String>, DomainError> bEnvRes = validateBuilderTemplates(
                builderName, b, validationVars, hostEnv
            );
            if (bEnvRes.isErr()) return bEnvRes.castError();
            resolvedBuilders.put(builderName, new BuilderDefinition(
                builderName, b.command(), b.workingDir(), SecretEnv.of(okVal(bEnvRes))
            ));
        }

        Map<String, RuntimeDefinition> resolvedRuntimes = new LinkedHashMap<>();
        for (Map.Entry<String, RawProjectConfig.RawRuntimeConfig> entry : rawConfig.runtimes().entrySet()) {
            String rtName = entry.getKey();
            RawProjectConfig.RawRuntimeConfig rt = entry.getValue();
            Result<Map<String, String>, DomainError> rtEnvRes = validateRuntimeTemplates(
                rtName, rt, validationVars, hostEnv
            );
            if (rtEnvRes.isErr()) return rtEnvRes.castError();
            resolvedRuntimes.put(rtName, new RuntimeDefinition(
                rtName, rt.command(), normalizeHealthPath(rt.healthPath()), rt.artifactGlob(),
                rt.artifactExclude(), rt.workingDir(), SecretEnv.of(okVal(rtEnvRes))
            ));
        }

        Result<Optional<GatewayDefinition>, DomainError> gwRes = resolveGateway(plan, topEnvFileOverlay, hostEnv);
        if (gwRes.isErr()) return gwRes.castError();
        Optional<GatewayDefinition> gateway = okVal(gwRes);

        Map<String, ServiceDefinition> services = new LinkedHashMap<>();
        Map<Path, RepositoryRef> repoByRoot = new LinkedHashMap<>();

        for (ServiceDraft draft : plan.serviceDrafts()) {
            Result<ServiceDefinition, DomainError> svcRes = resolveService(
                draft, plan.configDir(), rawConfig, parsedEnvFiles, topEnvFileOverlay, hostEnv
            );
            if (svcRes.isErr()) return svcRes.castError();
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
            resolvedBuilders,
            resolvedRuntimes,
            gateway,
            services,
            rawConfig.profiles(),
            List.copyOf(repoByRoot.values())
        ));
    }

    /**
     * Expands deferred launch-time template variables (such as {@code {{artifact}}}) in a Service's argv.
     */
    public static Result<List<String>, DomainError> resolveServiceCommand(
        ServiceDefinition service,
        Optional<Path> artifactPath
    ) {
        if (service == null) {
            throw new IllegalArgumentException("service must not be null");
        }
        boolean usesArtifact = service.command().stream().anyMatch(arg -> arg.contains("{{artifact}}"));
        if (!usesArtifact) {
            return Result.ok(service.command());
        }
        if (artifactPath == null || artifactPath.isEmpty()) {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("service", service.name());
            details.put("workingDir", service.workingDir().toString());
            details.put("artifactGlob", service.artifactGlob().orElse(""));
            service.buildConfig().ifPresent(cfg -> details.put("builder", cfg.builder()));
            String suffix = service.buildConfig().isPresent()
                ? " — build the Service before starting it"
                : "";
            return Result.err(DomainError.of(
                "ARTIFACT_NOT_FOUND",
                "No runnable artifact found for service '" + service.name() + "' (glob: '"
                    + service.artifactGlob().orElse("none") + "', workingDir: '" + service.workingDir() + "')" + suffix,
                details
            ));
        }
        String artifact = artifactPath.get().toString();
        List<String> resolved = new ArrayList<>(service.command().size());
        for (String arg : service.command()) {
            resolved.add(arg.replace("{{artifact}}", artifact));
        }
        return Result.ok(List.copyOf(resolved));
    }

    private static Result<ServiceDefinition, DomainError> resolveService(
        ServiceDraft draft,
        Path configDir,
        RawProjectConfig rawConfig,
        Map<Path, Map<String, String>> parsedEnvFiles,
        Map<String, String> topEnvFileOverlay,
        Map<String, String> hostEnv
    ) {
        String name = draft.name();
        RawProjectConfig.RawServiceConfig svc = draft.mergedConfig();
        String rtName = svc.runtime().orElseThrow();
        RawProjectConfig.RawRuntimeConfig rt = Objects.requireNonNull(
            rawConfig.runtimes().get(rtName),
            "Validated runtime missing: " + rtName
        );
        Map<String, VarValue> vars = draft.effectiveVars();

        Result<Optional<String>, DomainError> artifactGlobRes = expandOptionalScalar(
            rt.artifactGlob(), vars, Set.of(), "runtimes." + rtName + ".artifactGlob"
        );
        if (artifactGlobRes.isErr()) return artifactGlobRes.castError();
        Optional<String> artifactGlob = okVal(artifactGlobRes);

        Set<String> deferredCmdVars = artifactGlob.isPresent() ? Set.of("artifact") : Set.of();
        Result<List<String>, DomainError> hostExpandedCmd = expandHostEnvInArgv(
            rt.command(), hostEnv, "services." + name + ".command"
        );
        if (hostExpandedCmd.isErr()) return hostExpandedCmd.castError();
        Result<List<String>, DomainError> cmdRes = TemplateExpander.expandArgv(
            okVal(hostExpandedCmd), vars, deferredCmdVars, "services." + name + ".command"
        );
        if (cmdRes.isErr()) return cmdRes.castError();

        Optional<String> rawHealth = svc.healthPath().isPresent() ? svc.healthPath() : rt.healthPath();
        Result<Optional<String>, DomainError> healthRes = expandOptionalScalar(
            rawHealth, vars, Set.of(), "services." + name + ".healthPath"
        );
        if (healthRes.isErr()) return healthRes.castError();

        String rawProxy = svc.proxyPath()
            .or(() -> rawConfig.defaults().proxyPathTemplate())
            .orElse("/{{service}}");
        Result<String, DomainError> proxyRes = TemplateExpander.expandScalar(
            rawProxy, vars, Set.of(), "services." + name + ".proxyPath"
        );
        if (proxyRes.isErr()) return proxyRes.castError();
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
        if (remoteUrlRes.isErr()) return remoteUrlRes.castError();

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
            Result<String, DomainError> tplExpanded = TemplateExpander.expandScalar(entry.getValue(), vars, Set.of(), ctx);
            if (tplExpanded.isErr()) return tplExpanded.castError();
            Result<String, DomainError> hostExpanded = EnvFileParser.expandHostEnvRefs(okVal(tplExpanded), hostEnv, ctx);
            if (hostExpanded.isErr()) return hostExpanded.castError();
            finalEnv.put(entry.getKey(), okVal(hostExpanded));
        }

        Result<Optional<ServiceBuildConfig>, DomainError> buildConfigRes = resolveServiceBuildConfig(
            name, svc, draft, configDir, rawConfig, envFromFiles, hostEnv
        );
        if (buildConfigRes.isErr()) return buildConfigRes.castError();

        List<EnvFileSpec> effectiveEnvFiles = new ArrayList<>(rawConfig.defaults().envFiles());
        effectiveEnvFiles.addAll(svc.envFiles());

        return Result.ok(new ServiceDefinition(
            name,
            svc.port(),
            rtName,
            okVal(buildConfigRes),
            draft.repository(),
            draft.workingDir(),
            normalizeHealthPath(okVal(healthRes)),
            proxyPath,
            okVal(remoteUrlRes),
            artifactGlob,
            rt.artifactExclude(),
            okVal(cmdRes),
            SecretEnv.of(finalEnv),
            effectiveEnvFiles
        ));
    }

    private static Result<Optional<ServiceBuildConfig>, DomainError> resolveServiceBuildConfig(
        String name,
        RawProjectConfig.RawServiceConfig svc,
        ServiceDraft draft,
        Path configDir,
        RawProjectConfig rawConfig,
        Map<String, String> envFromFiles,
        Map<String, String> hostEnv
    ) {
        Optional<String> effectiveBuilderName = svc.builder().or(() -> rawConfig.defaults().builder());
        if (effectiveBuilderName.isEmpty()) {
            return Result.ok(Optional.empty());
        }
        String builderName = effectiveBuilderName.get();
        RawProjectConfig.RawBuilderConfig builderCfg = rawConfig.builders().get(builderName);
        if (builderCfg == null) {
            return Result.err(DomainError.of(
                "CONFIG_UNKNOWN_BUILDER",
                "Service '" + name + "' references unknown builder '" + builderName + "'",
                Map.of("service", name, "builder", builderName)
            ));
        }

        Map<String, VarValue> vars = draft.effectiveVars();
        Result<List<String>, DomainError> hostExpandedBuildCmd = expandHostEnvInArgv(
            builderCfg.command(), hostEnv, "services." + name + ".buildCommand"
        );
        if (hostExpandedBuildCmd.isErr()) return hostExpandedBuildCmd.castError();
        Result<List<String>, DomainError> buildCmdRes = TemplateExpander.expandArgv(
            okVal(hostExpandedBuildCmd), vars, Set.of(), "services." + name + ".buildCommand"
        );
        if (buildCmdRes.isErr()) return buildCmdRes.castError();

        Path buildWorkingDir = draft.workingDir();
        if (builderCfg.workingDir().isPresent()) {
            String wdCtx = "builders." + builderName + ".workingDir";
            Result<String, DomainError> hostWd = EnvFileParser.expandHostEnvRefs(
                builderCfg.workingDir().get(), hostEnv, wdCtx
            );
            if (hostWd.isErr()) return hostWd.castError();
            Result<String, DomainError> expandedWd = TemplateExpander.expandScalar(
                okVal(hostWd), vars, Set.of(), wdCtx
            );
            if (expandedWd.isErr()) return expandedWd.castError();
            Result<Path, DomainError> resolvedWd = resolvePath(configDir, okVal(expandedWd), wdCtx);
            if (resolvedWd.isErr()) return resolvedWd.castError();
            buildWorkingDir = okVal(resolvedWd);
        }

        Map<String, String> buildConfigEnvLayer = new LinkedHashMap<>(rawConfig.defaults().env());
        buildConfigEnvLayer.putAll(builderCfg.env());
        buildConfigEnvLayer.putAll(svc.env());

        Map<String, String> finalBuildEnv = new LinkedHashMap<>(envFromFiles);
        for (Map.Entry<String, String> entry : buildConfigEnvLayer.entrySet()) {
            String ctx = "services." + name + ".buildEnv." + entry.getKey();
            Result<String, DomainError> tplExpanded = TemplateExpander.expandScalar(entry.getValue(), vars, Set.of(), ctx);
            if (tplExpanded.isErr()) return tplExpanded.castError();
            Result<String, DomainError> hostExpanded = EnvFileParser.expandHostEnvRefs(okVal(tplExpanded), hostEnv, ctx);
            if (hostExpanded.isErr()) return hostExpanded.castError();
            finalBuildEnv.put(entry.getKey(), okVal(hostExpanded));
        }

        return Result.ok(Optional.of(new ServiceBuildConfig(
            builderName,
            okVal(buildCmdRes),
            buildWorkingDir,
            SecretEnv.of(finalBuildEnv)
        )));
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
        Map<String, VarValue> gwVars = new LinkedHashMap<>(plan.expandedDefaultsVars());
        gwVars.put("configDir", new VarValue.Scalar(plan.configDir().toString()));
        plan.repositoriesRoot().ifPresent(p -> gwVars.put("repositoriesRoot", new VarValue.Scalar(p.toString())));
        plan.currentUser().filter(s -> !s.isBlank()).ifPresent(u -> gwVars.put("user", new VarValue.Scalar(u)));
        gwVars.put("port", new VarValue.Scalar(Integer.toString(gw.port())));

        Result<List<String>, DomainError> hostExpandedGwCmd = expandHostEnvInArgv(
            gw.command(), hostEnv, "gateway.command"
        );
        if (hostExpandedGwCmd.isErr()) return hostExpandedGwCmd.castError();
        Result<List<String>, DomainError> cmdRes = TemplateExpander.expandArgv(
            okVal(hostExpandedGwCmd), gwVars, Set.of(), "gateway.command"
        );
        if (cmdRes.isErr()) return cmdRes.castError();

        Optional<Path> workingDir = Optional.empty();
        if (gw.workingDir().isPresent()) {
            Result<String, DomainError> hostExpandedWd = EnvFileParser.expandHostEnvRefs(
                gw.workingDir().get(), hostEnv, "gateway.workingDir"
            );
            if (hostExpandedWd.isErr()) return hostExpandedWd.castError();
            Result<String, DomainError> wdRes = TemplateExpander.expandScalar(
                okVal(hostExpandedWd), gwVars, Set.of(), "gateway.workingDir"
            );
            if (wdRes.isErr()) return wdRes.castError();
            Result<Path, DomainError> gwWdRes = resolvePath(plan.configDir(), okVal(wdRes), "gateway.workingDir");
            if (gwWdRes.isErr()) return gwWdRes.castError();
            workingDir = Optional.of(okVal(gwWdRes));
        }

        Result<Optional<String>, DomainError> healthRes = expandOptionalScalar(
            gw.healthPath(), gwVars, Set.of(), "gateway.healthPath"
        );
        if (healthRes.isErr()) return healthRes.castError();

        Map<String, String> gwEnv = new LinkedHashMap<>(topEnvFileOverlay);
        for (Map.Entry<String, String> entry : gw.env().entrySet()) {
            String ctx = "gateway.env." + entry.getKey();
            Result<String, DomainError> tplExpanded = TemplateExpander.expandScalar(entry.getValue(), gwVars, Set.of(), ctx);
            if (tplExpanded.isErr()) return tplExpanded.castError();
            Result<String, DomainError> hostExpanded = EnvFileParser.expandHostEnvRefs(okVal(tplExpanded), hostEnv, ctx);
            if (hostExpanded.isErr()) return hostExpanded.castError();
            gwEnv.put(entry.getKey(), okVal(hostExpanded));
        }

        Map<String, String> resolvedProxyHeaders = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : gw.proxyHeaders().entrySet()) {
            String ctx = "gateway.proxyHeaders." + entry.getKey();
            Result<String, DomainError> tplExpanded = TemplateExpander.expandScalar(entry.getValue(), gwVars, Set.of(), ctx);
            if (tplExpanded.isErr()) return tplExpanded.castError();
            Result<String, DomainError> hostExpanded = EnvFileParser.expandHostEnvRefs(okVal(tplExpanded), hostEnv, ctx);
            if (hostExpanded.isErr()) return hostExpanded.castError();
            resolvedProxyHeaders.put(entry.getKey(), okVal(hostExpanded));
        }

        Optional<FallbackRemote> resolvedFallbackRemote = Optional.empty();
        if (gw.fallbackRemote().isPresent()) {
            RawProjectConfig.RawFallbackRemoteConfig rawFb = gw.fallbackRemote().get();
            Result<String, DomainError> targetRes = expandScalarAndHostEnv(
                rawFb.targetUrl(), gwVars, hostEnv, "gateway.fallbackRemote.targetUrl"
            );
            if (targetRes.isErr()) return targetRes.castError();
            String targetUrl = okVal(targetRes).trim();
            if (targetUrl.isBlank()) {
                return Result.err(DomainError.of(
                    "CONFIG_INVALID_VALUE",
                    "Resolved gateway.fallbackRemote.targetUrl must not be blank",
                    Map.of("field", "gateway.fallbackRemote.targetUrl")
                ));
            }

            Optional<String> stripPrefix = Optional.empty();
            if (rawFb.stripPrefix().isPresent()) {
                Result<String, DomainError> stripRes = expandScalarAndHostEnv(
                    rawFb.stripPrefix().get(), gwVars, hostEnv, "gateway.fallbackRemote.stripPrefix"
                );
                if (stripRes.isErr()) return stripRes.castError();
                String trimmedStrip = okVal(stripRes).trim();
                if (!trimmedStrip.isEmpty()) {
                    stripPrefix = Optional.of(trimmedStrip);
                }
            }
            resolvedFallbackRemote = Optional.of(new FallbackRemote(targetUrl, stripPrefix));
        }

        return Result.ok(Optional.of(new GatewayDefinition(
            gw.type(),
            gw.port(),
            normalizeHealthPath(okVal(healthRes)),
            okVal(cmdRes),
            workingDir,
            SecretEnv.of(gwEnv),
            resolvedProxyHeaders,
            resolvedFallbackRemote
        )));
    }

    private static Result<Map<Path, Map<String, String>>, DomainError> loadAndParseEnvFiles(
        ResolutionPlan plan,
        FileLookup fileLookup
    ) {
        Map<Path, Optional<Map<String, String>>> cache = new LinkedHashMap<>();
        Map<Path, Map<String, String>> parsed = new LinkedHashMap<>();
        for (EnvFileTarget target : plan.allEnvFileTargets()) {
            Optional<Map<String, String>> entry = cache.get(target.path());
            if (entry == null) {
                Result<Optional<String>, DomainError> readRes = fileLookup.readIfPresent(target.path());
                if (readRes.isErr()) {
                    return readRes.castError();
                }
                Optional<String> contentOpt = okVal(readRes);
                if (contentOpt.isPresent()) {
                    Result<Map<String, String>, DomainError> parseRes =
                        EnvFileParser.parse(target.path().toString(), contentOpt.get());
                    if (parseRes.isErr()) {
                        return parseRes.castError();
                    }
                    Map<String, String> envMap = okVal(parseRes);
                    entry = Optional.of(envMap);
                    parsed.put(target.path(), envMap);
                } else {
                    entry = Optional.empty();
                }
                cache.put(target.path(), entry);
            }
            if (entry.isEmpty() && !target.optional()) {
                return Result.err(DomainError.of(
                    "CONFIG_MISSING_ENV_FILE",
                    "Required envFile '" + target.path() + "' not found (" + target.context() + ")",
                    Map.of("path", target.path().toString(), "context", target.context())
                ));
            }
        }
        return Result.ok(parsed);
    }

    private static Result<Optional<Path>, DomainError> resolveRepositoriesRoot(Path configDir, RawProjectConfig rawConfig) {
        if (rawConfig.repositoriesRoot().isEmpty()) {
            return Result.ok(Optional.empty());
        }
        return resolvePath(configDir, rawConfig.repositoriesRoot().get(), "repositoriesRoot").map(Optional::of);
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
                    override.builder().isPresent() ? override.builder() : base.builder(),
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
            if (expanded.isErr()) return expanded.castError();
            Result<Path, DomainError> repoPathRes = resolvePath(configDir, okVal(expanded), "services." + svcName + ".repository");
            if (repoPathRes.isErr()) return repoPathRes.castError();
            return Result.ok(Optional.of(new RepositoryRef(okVal(repoPathRes), svc.repositoryUrl())));
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
            if (basenameRes.isErr()) return basenameRes.castError();
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
        Map<String, VarValue> effectiveVars,
        Map<String, String> hostEnv
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
        String ctx = "services." + svcName + ".workingDir";
        Result<String, DomainError> hostExpanded = EnvFileParser.expandHostEnvRefs(template.get(), hostEnv, ctx);
        if (hostExpanded.isErr()) return hostExpanded.castError();
        Result<String, DomainError> expanded = TemplateExpander.expandScalar(
            okVal(hostExpanded), effectiveVars, Set.of(), ctx
        );
        if (expanded.isErr()) return expanded.castError();
        return resolvePath(configDir, okVal(expanded), "services." + svcName + ".workingDir");
    }

    private static Result<Path, DomainError> resolvePath(Path base, String raw, String fieldContext) {
        try {
            return Result.ok(base.resolve(raw).normalize());
        } catch (java.nio.file.InvalidPathException e) {
            return Result.err(DomainError.of(
                "CONFIG_INVALID_VALUE",
                "Invalid path '" + raw + "' in '" + fieldContext + "': " + e.getMessage(),
                Map.of("field", fieldContext, "value", raw)
            ));
        }
    }

    private static Map<String, VarValue> buildTemplateValidationVars(ResolutionPlan plan) {
        Map<String, VarValue> vars = new LinkedHashMap<>(plan.expandedDefaultsVars());
        for (ServiceDraft draft : plan.serviceDrafts()) {
            for (Map.Entry<String, VarValue> entry : draft.effectiveVars().entrySet()) {
                // Only add service-defined vars when not already present with an incompatible type
                vars.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        vars.put("configDir", new VarValue.Scalar(plan.configDir().toString()));
        plan.repositoriesRoot().ifPresent(p -> vars.put("repositoriesRoot", new VarValue.Scalar(p.toString())));
        plan.currentUser().filter(s -> !s.isBlank()).ifPresent(u -> vars.put("user", new VarValue.Scalar(u)));
        vars.put("service", new VarValue.Scalar("service"));
        vars.put("port", new VarValue.Scalar("0"));
        vars.put("repository", new VarValue.Scalar(plan.configDir().toString()));
        vars.put("artifact", new VarValue.Scalar("artifact"));
        return vars;
    }

    private static Result<procomp.core.common.Unit, DomainError> validateDefaultsTemplates(
        RawProjectConfig.RawDefaultsConfig defaults,
        Map<String, VarValue> validationVars,
        Map<String, String> hostEnv
    ) {
        Result<Optional<String>, DomainError> proxyCheck = expandOptionalScalar(
            defaults.proxyPathTemplate(), validationVars, Set.of(), "defaults.proxyPathTemplate"
        );
        if (proxyCheck.isErr()) return proxyCheck.castError();
        Result<Optional<String>, DomainError> remoteCheck = expandOptionalScalar(
            defaults.remoteUrlTemplate(), validationVars, Set.of(), "defaults.remoteUrlTemplate"
        );
        if (remoteCheck.isErr()) return remoteCheck.castError();
        if (defaults.workingDir().isPresent()) {
            Result<String, DomainError> hostWd = EnvFileParser.expandHostEnvRefs(
                defaults.workingDir().get(), hostEnv, "defaults.workingDir"
            );
            if (hostWd.isErr()) return hostWd.castError();
            Result<String, DomainError> wdCheck = TemplateExpander.expandScalar(
                okVal(hostWd), validationVars, Set.of(), "defaults.workingDir"
            );
            if (wdCheck.isErr()) return wdCheck.castError();
        }
        Result<Map<String, String>, DomainError> envCheck = expandAndValidateEnvMap(
            defaults.env(), validationVars, hostEnv, "defaults.env"
        );
        if (envCheck.isErr()) return envCheck.castError();
        if (!defaults.envFiles().isEmpty()) {
            Map<String, VarValue> envFileVars = new LinkedHashMap<>(validationVars);
            envFileVars.remove("artifact");
            for (int i = 0; i < defaults.envFiles().size(); i++) {
                String ctx = "defaults.envFiles[" + i + "]";
                Result<String, DomainError> pCheck = TemplateExpander.expandScalar(
                    defaults.envFiles().get(i).path(), envFileVars, Set.of(), ctx
                );
                if (pCheck.isErr()) return pCheck.castError();
            }
        }
        return Result.ok(procomp.core.common.Unit.VALUE);
    }

    private static Result<Map<String, String>, DomainError> validateBuilderTemplates(
        String builderName,
        RawProjectConfig.RawBuilderConfig builder,
        Map<String, VarValue> validationVars,
        Map<String, String> hostEnv
    ) {
        String prefix = "builders." + builderName;
        Map<String, VarValue> buildValidationVars = new LinkedHashMap<>(validationVars);
        buildValidationVars.remove("artifact");
        Result<List<String>, DomainError> hostCmd = expandHostEnvInArgv(builder.command(), hostEnv, prefix + ".command");
        if (hostCmd.isErr()) return hostCmd.castError();
        Result<List<String>, DomainError> cmdCheck = TemplateExpander.expandArgv(
            okVal(hostCmd), buildValidationVars, Set.of(), prefix + ".command"
        );
        if (cmdCheck.isErr()) return cmdCheck.castError();
        if (builder.workingDir().isPresent()) {
            Result<String, DomainError> hostWd = EnvFileParser.expandHostEnvRefs(
                builder.workingDir().get(), hostEnv, prefix + ".workingDir"
            );
            if (hostWd.isErr()) return hostWd.castError();
            Result<String, DomainError> wdCheck = TemplateExpander.expandScalar(
                okVal(hostWd), buildValidationVars, Set.of(), prefix + ".workingDir"
            );
            if (wdCheck.isErr()) return wdCheck.castError();
        }
        return expandAndValidateEnvMap(builder.env(), buildValidationVars, hostEnv, prefix + ".env");
    }

    private static Result<Map<String, String>, DomainError> validateRuntimeTemplates(
        String rtName,
        RawProjectConfig.RawRuntimeConfig rt,
        Map<String, VarValue> validationVars,
        Map<String, String> hostEnv
    ) {
        String prefix = "runtimes." + rtName;
        Result<List<String>, DomainError> hostCmd = expandHostEnvInArgv(rt.command(), hostEnv, prefix + ".command");
        if (hostCmd.isErr()) return hostCmd.castError();
        Result<List<String>, DomainError> cmdCheck = TemplateExpander.expandArgv(
            okVal(hostCmd), validationVars, Set.of(), prefix + ".command"
        );
        if (cmdCheck.isErr()) return cmdCheck.castError();
        Result<Optional<String>, DomainError> healthCheck = expandOptionalScalar(
            rt.healthPath(), validationVars, Set.of(), prefix + ".healthPath"
        );
        if (healthCheck.isErr()) return healthCheck.castError();
        Result<Optional<String>, DomainError> globCheck = expandOptionalScalar(
            rt.artifactGlob(), validationVars, Set.of(), prefix + ".artifactGlob"
        );
        if (globCheck.isErr()) return globCheck.castError();
        if (rt.workingDir().isPresent()) {
            Result<String, DomainError> hostWd = EnvFileParser.expandHostEnvRefs(
                rt.workingDir().get(), hostEnv, prefix + ".workingDir"
            );
            if (hostWd.isErr()) return hostWd.castError();
            Result<String, DomainError> wdCheck = TemplateExpander.expandScalar(
                okVal(hostWd), validationVars, Set.of(), prefix + ".workingDir"
            );
            if (wdCheck.isErr()) return wdCheck.castError();
        }
        return expandAndValidateEnvMap(rt.env(), validationVars, hostEnv, prefix + ".env");
    }

    private static Result<Map<String, VarValue>, DomainError> expandHostEnvInVars(
        Map<String, VarValue> rawVars,
        Map<String, String> hostEnv,
        String context
    ) {
        Map<String, VarValue> out = new LinkedHashMap<>();
        for (Map.Entry<String, VarValue> entry : rawVars.entrySet()) {
            String fieldCtx = context + "." + entry.getKey();
            switch (entry.getValue()) {
                case VarValue.Scalar(String s) -> {
                    Result<String, DomainError> expanded = EnvFileParser.expandHostEnvRefs(s, hostEnv, fieldCtx);
                    if (expanded.isErr()) return expanded.castError();
                    out.put(entry.getKey(), new VarValue.Scalar(okVal(expanded)));
                }
                case VarValue.ListValue(List<String> items) -> {
                    List<String> expandedItems = new ArrayList<>(items.size());
                    for (int i = 0; i < items.size(); i++) {
                        Result<String, DomainError> expanded =
                            EnvFileParser.expandHostEnvRefs(items.get(i), hostEnv, fieldCtx + "[" + i + "]");
                        if (expanded.isErr()) return expanded.castError();
                        expandedItems.add(okVal(expanded));
                    }
                    out.put(entry.getKey(), new VarValue.ListValue(expandedItems));
                }
            }
        }
        return Result.ok(out);
    }

    private static Result<List<String>, DomainError> expandHostEnvInArgv(
        List<String> rawArgv,
        Map<String, String> hostEnv,
        String context
    ) {
        List<String> out = new ArrayList<>(rawArgv.size());
        for (int i = 0; i < rawArgv.size(); i++) {
            Result<String, DomainError> expanded =
                EnvFileParser.expandHostEnvRefs(rawArgv.get(i), hostEnv, context + "[" + i + "]");
            if (expanded.isErr()) return expanded.castError();
            out.add(okVal(expanded));
        }
        return Result.ok(out);
    }

    private static Result<Map<String, String>, DomainError> expandAndValidateEnvMap(
        Map<String, String> envMap,
        Map<String, VarValue> validationVars,
        Map<String, String> hostEnv,
        String context
    ) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : envMap.entrySet()) {
            String fieldCtx = context + "." + entry.getKey();
            Result<String, DomainError> tplCheck = TemplateExpander.expandScalar(entry.getValue(), validationVars, Set.of(), fieldCtx);
            if (tplCheck.isErr()) return tplCheck.castError();
            Result<String, DomainError> hostExpanded = EnvFileParser.expandHostEnvRefs(entry.getValue(), hostEnv, fieldCtx);
            if (hostExpanded.isErr()) return hostExpanded.castError();
            out.put(entry.getKey(), okVal(hostExpanded));
        }
        return Result.ok(out);
    }

    private static Optional<String> normalizeHealthPath(Optional<String> raw) {
        return raw.map(String::trim).filter(s -> !s.isEmpty());
    }

    private static Result<String, DomainError> expandScalarAndHostEnv(
        String raw,
        Map<String, VarValue> vars,
        Map<String, String> hostEnv,
        String context
    ) {
        Result<String, DomainError> tplExpanded = TemplateExpander.expandScalar(raw, vars, Set.of(), context);
        if (tplExpanded.isErr()) return tplExpanded.castError();
        return EnvFileParser.expandHostEnvRefs(okVal(tplExpanded), hostEnv, context);
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

    private static <T> T okVal(Result<T, DomainError> res) {
        return ((Result.Ok<T, DomainError>) res).value();
    }
}
