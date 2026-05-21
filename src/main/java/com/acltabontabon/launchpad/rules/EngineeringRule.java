package com.acltabontabon.launchpad.rules;

/**
 * Predefined engineering rules injected into every generated context file,
 * regardless of target (Claude or Cursor). These are target-agnostic principles
 * that guide the AI agent working with the project.
 */
public enum EngineeringRule {

    CLEAN_CODE(
        "Clean Code",
        "Write self-documenting code. Prefer clear naming over comments. " +
        "Functions do one thing. Classes have a single responsibility."
    ),
    SOLID(
        "SOLID Principles",
        "Follow SOLID: single responsibility, open/closed, Liskov substitution, " +
        "interface segregation, dependency inversion."
    ),
    NO_PREMATURE_ABSTRACTION(
        "No Premature Abstraction",
        "Do not introduce abstractions for hypothetical future requirements. " +
        "Three similar implementations before extracting a shared abstraction."
    ),
    MINIMAL_SURFACE_AREA(
        "Minimal Surface Area",
        "Keep public APIs small. Prefer package-private over public. " +
        "Expose only what callers genuinely need."
    ),
    NO_DEFENSIVE_CODING(
        "No Defensive Coding",
        "Do not add error handling for scenarios that cannot happen. " +
        "Validate only at system boundaries (user input, external APIs)."
    ),
    TESTS_AS_SPEC(
        "Tests Are Specs",
        "Tests document expected behavior, not implementation details. " +
        "Name tests after the behavior they verify, not the method they call."
    ),
    IMMUTABILITY_FIRST(
        "Immutability First",
        "Prefer immutable data structures. Use records, final fields, and " +
        "value objects. Mutation should be explicit and localized."
    ),
    FAIL_FAST(
        "Fail Fast",
        "Surface errors early and loudly. Throw at the point of violation, " +
        "not downstream. Never swallow exceptions silently."
    ),
    DEPENDENCY_DIRECTION(
        "Dependency Direction",
        "Dependencies point inward: domain has no dependencies on infrastructure. " +
        "Infrastructure depends on domain interfaces, not the reverse."
    ),
    COMMIT_DISCIPLINE(
        "Commit Discipline",
        "Each commit is a single logical change. Commit messages explain why, " +
        "not what. The diff explains what."
    );

    public final String title;
    public final String description;

    EngineeringRule(String title, String description) {
        this.title = title;
        this.description = description;
    }
}
