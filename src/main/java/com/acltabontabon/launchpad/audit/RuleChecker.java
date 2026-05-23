package com.acltabontabon.launchpad.audit;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.standards.Rule;
import java.nio.file.Path;
import java.util.List;

/**
 * Audits a single rule against the scanned project. Implementations are picked
 * by {@link AuditService} based on {@link com.acltabontabon.launchpad.standards.Check#kind()}.
 */
public interface RuleChecker {

    /** Kind value (matches {@code Check.kind}) this checker handles. */
    String kind();

    /** Returns findings for the rule. An empty list means the rule is satisfied. */
    List<Finding> check(Rule rule, ProjectContext ctx, Path projectRoot);
}
