package procomp.core.git;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure parsers for machine-readable {@code git} CLI outputs.
 */
public final class GitOutputParser {

    private static final String LOCAL_PREFIX = "refs/heads/";
    private static final String REMOTE_ORIGIN_PREFIX = "refs/remotes/origin/";

    private GitOutputParser() {}

    public record ParsedStatus(
        RepositoryObservation.HeadState head,
        Optional<String> upstream,
        int ahead,
        int behind,
        int stagedCount,
        int unstagedCount,
        int untrackedCount
    ) {}

    public static ParsedStatus parseStatusPorcelainV2(String stdout) {
        String oid = "";
        String branchHead = "";
        Optional<String> upstream = Optional.empty();
        int ahead = 0;
        int behind = 0;
        int staged = 0;
        int unstaged = 0;
        int untracked = 0;

        if (stdout != null && !stdout.isEmpty()) {
            for (String line : stdout.lines().toList()) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("# branch.oid ")) {
                    oid = line.substring("# branch.oid ".length()).trim();
                } else if (line.startsWith("# branch.head ")) {
                    branchHead = line.substring("# branch.head ".length()).trim();
                } else if (line.startsWith("# branch.upstream ")) {
                    String up = line.substring("# branch.upstream ".length()).trim();
                    if (!up.isEmpty()) {
                        upstream = Optional.of(up);
                    }
                } else if (line.startsWith("# branch.ab ")) {
                    String[] parts = line.substring("# branch.ab ".length()).trim().split("\\s+", -1);
                    if (parts.length >= 2) {
                        ahead = parseSignedCount(parts[0]);
                        behind = parseSignedCount(parts[1]);
                    }
                } else if (line.startsWith("1 ") || line.startsWith("2 ") || line.startsWith("u ")) {
                    // Format: "<type> <XY> ..."
                    if (line.length() >= 4) {
                        char x = line.charAt(2);
                        char y = line.charAt(3);
                        if (x != '.' && x != ' ') {
                            staged++;
                        }
                        if (y != '.' && y != ' ') {
                            unstaged++;
                        }
                    }
                } else if (line.startsWith("? ")) {
                    untracked++;
                }
            }
        }

        RepositoryObservation.HeadState head;
        if (branchHead.isEmpty() || "(detached)".equals(branchHead)) {
            String shortSha = oid.length() >= 7 ? oid.substring(0, 7) : (oid.isEmpty() ? "unknown" : oid);
            head = new RepositoryObservation.HeadState.Detached(shortSha);
        } else {
            head = new RepositoryObservation.HeadState.Branch(branchHead);
        }

        return new ParsedStatus(head, upstream, ahead, behind, staged, unstaged, untracked);
    }

    /**
     * Parses lines formatted as {@code %(refname)\t%(committerdate:iso-strict)\t%(subject)}.
     * Deduplicates {@code refs/heads/<name>} and {@code refs/remotes/origin/<name>} (ignoring {@code origin/HEAD})
     * and orders by committer date descending, then branch name ascending.
     */
    public static List<BranchInfo> parseBranchesForEachRef(String stdout, Optional<String> currentBranch) {
        if (stdout == null || stdout.isBlank()) {
            return List.of();
        }
        Map<String, BranchAccumulator> byName = new LinkedHashMap<>();
        for (String line : stdout.lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", 3);
            if (parts.length < 2) {
                continue;
            }
            String refName = parts[0].trim();
            Instant commitAt = parseIsoInstant(parts[1].trim()).orElse(Instant.EPOCH);
            String subject = parts.length >= 3 ? parts[2].trim() : "";

            String shortName;
            boolean isLocal;
            if (refName.startsWith(LOCAL_PREFIX)) {
                shortName = refName.substring(LOCAL_PREFIX.length());
                isLocal = true;
            } else if (refName.startsWith(REMOTE_ORIGIN_PREFIX)) {
                shortName = refName.substring(REMOTE_ORIGIN_PREFIX.length());
                if ("HEAD".equals(shortName) || shortName.isBlank()) {
                    continue;
                }
                isLocal = false;
            } else {
                continue;
            }

            if (shortName.isBlank()) {
                continue;
            }

            BranchAccumulator existing = byName.get(shortName);
            if (existing == null) {
                byName.put(shortName, new BranchAccumulator(shortName, isLocal, !isLocal, commitAt, subject));
            } else {
                existing.merge(isLocal, !isLocal, commitAt, subject);
            }
        }

        List<BranchInfo> result = new ArrayList<>(byName.size());
        for (BranchAccumulator acc : byName.values()) {
            BranchInfo.RefOrigin origin;
            if (acc.hasLocal && acc.hasRemote) {
                origin = BranchInfo.RefOrigin.LOCAL_AND_REMOTE;
            } else if (acc.hasLocal) {
                origin = BranchInfo.RefOrigin.LOCAL;
            } else {
                origin = BranchInfo.RefOrigin.REMOTE;
            }
            boolean isCurrent = currentBranch.map(b -> b.equals(acc.name)).orElse(false);
            result.add(new BranchInfo(acc.name, origin, acc.lastCommitAt, acc.shortSubject, isCurrent));
        }

        result.sort(Comparator.comparing(BranchInfo::lastCommitAt).reversed().thenComparing(BranchInfo::name));
        return List.copyOf(result);
    }

    public static List<StashEntry> parseStashList(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return List.of();
        }
        List<StashEntry> entries = new ArrayList<>();
        for (String line : stdout.lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                String ref = line.substring(0, colon).trim();
                String msg = line.substring(colon + 1).trim();
                entries.add(new StashEntry(ref, msg));
            } else {
                entries.add(new StashEntry(line.trim(), ""));
            }
        }
        return List.copyOf(entries);
    }

    public static Optional<String> parseOriginHead(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return Optional.empty();
        }
        String trimmed = stdout.trim();
        if (trimmed.startsWith(REMOTE_ORIGIN_PREFIX)) {
            String branch = trimmed.substring(REMOTE_ORIGIN_PREFIX.length()).trim();
            return branch.isEmpty() ? Optional.empty() : Optional.of(branch);
        }
        if (trimmed.startsWith("origin/")) {
            String branch = trimmed.substring("origin/".length()).trim();
            return branch.isEmpty() ? Optional.empty() : Optional.of(branch);
        }
        return Optional.of(trimmed);
    }

    private static int parseSignedCount(String token) {
        try {
            int val = Integer.parseInt(token);
            return Math.abs(val);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Optional<Instant> parseIsoInstant(String raw) {
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(raw).toInstant());
        } catch (DateTimeParseException e) {
            try {
                return Optional.of(Instant.parse(raw));
            } catch (DateTimeParseException ignored) {
                return Optional.empty();
            }
        }
    }

    private static final class BranchAccumulator {
        final String name;
        boolean hasLocal;
        boolean hasRemote;
        Instant lastCommitAt;
        String shortSubject;

        BranchAccumulator(String name, boolean hasLocal, boolean hasRemote, Instant lastCommitAt, String shortSubject) {
            this.name = name;
            this.hasLocal = hasLocal;
            this.hasRemote = hasRemote;
            this.lastCommitAt = lastCommitAt;
            this.shortSubject = shortSubject;
        }

        void merge(boolean local, boolean remote, Instant commitAt, String subject) {
            this.hasLocal |= local;
            this.hasRemote |= remote;
            if (commitAt.isAfter(this.lastCommitAt)) {
                this.lastCommitAt = commitAt;
                this.shortSubject = subject;
            }
        }
    }
}
