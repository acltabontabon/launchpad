package com.acltabontabon.launchpad.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReadinessViewMapperTest {

    private final ReadinessViewMapper mapper = new ReadinessViewMapper();

    @Test
    void readyRecommendsNothingButOffersRefresh() {
        var view = map(ReadinessStatus.READY, List.of());

        assertThat(view.status()).isEqualTo(ReadinessStatus.READY);
        assertThat(view.recommendedAction()).isEqualTo(RecommendedAction.NONE);
        assertThat(view.availableActions()).containsExactly(RecommendedAction.REFRESH);
        assertThat(view.reasonLines()).isEmpty();
    }

    @Test
    void staleRecommendsRefresh() {
        var view = map(ReadinessStatus.STALE, List.of("Launchpad version changed."));

        assertThat(view.recommendedAction()).isEqualTo(RecommendedAction.REFRESH);
        assertThat(view.availableActions()).containsExactly(RecommendedAction.REFRESH);
        assertThat(view.reasonLines()).containsExactly("Launchpad version changed.");
    }

    @Test
    void partialRecommendsRefresh() {
        var view = map(ReadinessStatus.PARTIAL, List.of("Missing artifact: standards.index.json"));

        assertThat(view.recommendedAction()).isEqualTo(RecommendedAction.REFRESH);
        assertThat(view.availableActions()).containsExactly(RecommendedAction.REFRESH);
        assertThat(view.reasonLines()).isNotEmpty();
    }

    @Test
    void missingRecommendsPrepare() {
        var view = map(ReadinessStatus.MISSING, List.of("No preparation output found."));

        assertThat(view.recommendedAction()).isEqualTo(RecommendedAction.PREPARE);
        assertThat(view.availableActions()).containsExactly(RecommendedAction.PREPARE);
    }

    @Test
    void errorRecommendsRefresh() {
        var view = map(ReadinessStatus.ERROR, List.of("Unreadable artifact: project.model.json"));

        assertThat(view.recommendedAction()).isEqualTo(RecommendedAction.REFRESH);
        assertThat(view.availableActions()).containsExactly(RecommendedAction.REFRESH);
        assertThat(view.reasonLines()).isNotEmpty();
    }

    @Test
    void unsupportedRecommendsNothingAndNeverOffersPrepare() {
        var view = map(ReadinessStatus.UNSUPPORTED, List.of());

        assertThat(view.recommendedAction()).isEqualTo(RecommendedAction.NONE);
        assertThat(view.availableActions()).isEmpty();
        assertThat(view.availableActions()).doesNotContain(RecommendedAction.PREPARE);
    }

    @Test
    void ignoredRecommendsNothingAndNeverOffersPrepare() {
        var view = map(ReadinessStatus.IGNORED, List.of());

        assertThat(view.recommendedAction()).isEqualTo(RecommendedAction.NONE);
        assertThat(view.availableActions()).isEmpty();
        assertThat(view.availableActions()).doesNotContain(RecommendedAction.PREPARE);
    }

    @Test
    void copiesIdentityVerbatim() {
        var readiness = new ReadinessResult(
            ReadinessStatus.READY, List.of(), RecommendedAction.NONE, null);

        var view = mapper.map("my-service", "/home/dev/my-service", "Spring Boot / Maven", readiness);

        assertThat(view.name()).isEqualTo("my-service");
        assertThat(view.path()).isEqualTo("/home/dev/my-service");
        assertThat(view.typeLabel()).isEqualTo("Spring Boot / Maven");
    }

    @Test
    void nullReasonLinesBecomeEmptyList() {
        var readiness = new ReadinessResult(
            ReadinessStatus.READY, null, RecommendedAction.NONE, null);

        var view = mapper.map("svc", "/p", "Git repo", readiness);

        assertThat(view.reasonLines()).isEmpty();
    }

    @Test
    void returnedListsAreImmutable() {
        var view = map(ReadinessStatus.STALE, List.of("reason"));

        assertThatThrownBy(() -> view.reasonLines().add("x"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.availableActions().add(RecommendedAction.NONE))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    private ProjectReadinessView map(ReadinessStatus status, List<String> reasonLines) {
        // recommendedAction on the input is intentionally NONE for every case: the
        // mapper must derive the action from status, not copy it from the result.
        var readiness = new ReadinessResult(status, reasonLines, RecommendedAction.NONE, null);
        return mapper.map("demo", "/path/demo", "Spring Boot / Maven", readiness);
    }
}
