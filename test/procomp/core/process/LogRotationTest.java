package procomp.core.process;

import procomp.Assert;
import procomp.core.process.LogRotation.RotationPlan;
import procomp.core.process.LogRotation.Step;

import java.util.List;
import java.util.Set;

public final class LogRotationTest {

    public static void testNoOpWhenBelowThresholdOrBackupsDisabled() {
        Assert.assertEquals(
            RotationPlan.NoOp.INSTANCE,
            LogRotation.plan(1024L, 10L * 1024 * 1024, 3, Set.of()),
            "below threshold returns NoOp"
        );
        Assert.assertEquals(
            RotationPlan.NoOp.INSTANCE,
            LogRotation.plan(0L, 100L, 3, Set.of(1, 2, 3)),
            "zero-byte file returns NoOp"
        );
        Assert.assertEquals(
            RotationPlan.NoOp.INSTANCE,
            LogRotation.plan(200L, 100L, 0, Set.of()),
            "maxBackups == 0 returns NoOp"
        );
    }

    public static void testRotatesAtOrAboveThresholdWithNoExistingBackups() {
        RotationPlan plan = LogRotation.plan(100L, 100L, 3, Set.of());
        Assert.assertEquals(
            new RotationPlan.Rotate(List.of(new Step.Rename(0, 1))),
            plan,
            "at threshold with no backups renames active file (0) to backup 1"
        );
    }

    public static void testShiftsExistingBackupsAndPrunesOldestAtLimit() {
        RotationPlan partial = LogRotation.plan(200L, 100L, 3, Set.of(1));
        Assert.assertEquals(
            new RotationPlan.Rotate(List.of(
                new Step.Rename(1, 2),
                new Step.Rename(0, 1)
            )),
            partial,
            "partial backups shift from highest to lowest"
        );

        RotationPlan full = LogRotation.plan(200L, 100L, 3, Set.of(1, 2, 3));
        Assert.assertEquals(
            new RotationPlan.Rotate(List.of(
                new Step.Delete(3),
                new Step.Rename(2, 3),
                new Step.Rename(1, 2),
                new Step.Rename(0, 1)
            )),
            full,
            "full backups delete oldest (3) before shifting 2->3, 1->2, 0->1"
        );

        RotationPlan withGap = LogRotation.plan(200L, 100L, 3, Set.of(1, 3));
        Assert.assertEquals(
            new RotationPlan.Rotate(List.of(
                new Step.Delete(3),
                new Step.Rename(1, 2),
                new Step.Rename(0, 1)
            )),
            withGap,
            "gaps in backup sequence only delete/rename existing backups"
        );
    }
}
