package procomp.core.project;

import procomp.core.common.DomainError;
import procomp.core.common.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure template variable expander supporting scalar {{var}} substitution and string[] splicing in argv.
 */
final class TemplateExpander {

    private TemplateExpander() {}

    static Result<List<String>, DomainError> expandArgv(
        List<String> templateArgv,
        Map<String, VarValue> vars,
        Set<String> deferredVars,
        String fieldContext
    ) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < templateArgv.size(); i++) {
            String elem = templateArgv.get(i);
            String elemContext = fieldContext + "[" + i + "]";

            if (isSinglePlaceholder(elem)) {
                String varName = elem.substring(2, elem.length() - 2);
                if (varName.isBlank()) {
                    return Result.err(malformedTemplate(elemContext));
                }
                if (deferredVars.contains(varName)) {
                    out.add(elem);
                    continue;
                }
                VarValue val = vars.get(varName);
                if (val == null) {
                    return Result.err(undefinedVar(varName, elemContext));
                }
                switch (val) {
                    case VarValue.Scalar(String s) -> out.add(s);
                    case VarValue.ListValue(List<String> items) -> out.addAll(items);
                }
            } else {
                Result<String, DomainError> expanded = expandScalar(elem, vars, deferredVars, elemContext);
                if (expanded.isErr()) {
                    return expanded.fold(ignored -> { throw new IllegalStateException(); }, Result::err);
                }
                out.add(((Result.Ok<String, DomainError>) expanded).value());
            }
        }
        return Result.ok(out);
    }

    static Result<String, DomainError> expandScalar(
        String template,
        Map<String, VarValue> vars,
        Set<String> deferredVars,
        String fieldContext
    ) {
        if (template == null || !template.contains("{{")) {
            return Result.ok(template == null ? "" : template);
        }
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (idx < template.length()) {
            int start = template.indexOf("{{", idx);
            if (start < 0) {
                sb.append(template, idx, template.length());
                break;
            }
            sb.append(template, idx, start);
            int nameStart = start + 2;
            int end = template.indexOf("}}", nameStart);
            if (end < 0) {
                return Result.err(malformedTemplate(fieldContext));
            }
            String varName = template.substring(nameStart, end);
            if (varName.isBlank()) {
                return Result.err(malformedTemplate(fieldContext));
            }
            if (deferredVars.contains(varName)) {
                sb.append("{{").append(varName).append("}}");
                idx = end + 2;
                continue;
            }
            VarValue val = vars.get(varName);
            if (val == null) {
                return Result.err(undefinedVar(varName, fieldContext));
            }
            switch (val) {
                case VarValue.Scalar(String s) -> sb.append(s);
                case VarValue.ListValue _ -> {
                    return Result.err(DomainError.of(
                        "CONFIG_INVALID_TEMPLATE_TYPE",
                        "Array variable '{{" + varName + "}}' cannot be embedded inside scalar string at '" + fieldContext + "'",
                        Map.of("field", fieldContext, "var", varName)
                    ));
                }
            }
            idx = end + 2;
        }
        return Result.ok(sb.toString());
    }

    private static boolean isSinglePlaceholder(String s) {
        if (!s.startsWith("{{") || !s.endsWith("}}") || s.length() < 5) {
            return false;
        }
        int firstClose = s.indexOf("}}", 2);
        return firstClose == s.length() - 2;
    }

    private static DomainError malformedTemplate(String fieldContext) {
        return DomainError.of(
            "CONFIG_MALFORMED_TEMPLATE",
            "Malformed {{...}} template placeholder at '" + fieldContext + "'",
            Map.of("field", fieldContext)
        );
    }

    private static DomainError undefinedVar(String varName, String fieldContext) {
        return DomainError.of(
            "CONFIG_UNDEFINED_TEMPLATE_VAR",
            "Undefined template variable '{{" + varName + "}}' referenced at '" + fieldContext + "'",
            Map.of("field", fieldContext, "var", varName)
        );
    }
}
