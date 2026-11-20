package procomp.core.project;

import java.util.List;
import java.util.Objects;

/**
 * Custom template variable value: either a single string or a string array spliced into argv.
 */
public sealed interface VarValue permits VarValue.Scalar, VarValue.ListValue {

    record Scalar(String value) implements VarValue {
        public Scalar {
            Objects.requireNonNull(value, "Scalar var value must not be null");
        }
    }

    record ListValue(List<String> values) implements VarValue {
        public ListValue {
            Objects.requireNonNull(values, "ListValue elements must not be null");
            values = List.copyOf(values);
        }
    }
}
