package procomp.core.git;

/**
 * Disposition taken for a single mandatory Git hook during installation/upgrade.
 */
public enum HookInstallAction {
    INSTALLED,
    UPGRADED,
    UNCHANGED
}
