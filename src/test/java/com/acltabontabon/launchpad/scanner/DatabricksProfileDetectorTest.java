package com.acltabontabon.launchpad.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DatabricksProfileDetectorTest {

    private final DatabricksProfileDetector detector = new DatabricksProfileDetector();

    @Test
    void emptySignalsYieldEmptyProfile() {
        var profile = detector.detect(new ScanSignals());
        assertThat(profile.facets()).isEmpty();
        assertThat(profile.terraform()).isFalse();
        assertThat(profile.dlt()).isFalse();
        assertThat(profile.python()).isFalse();
        assertThat(profile.sql()).isFalse();
    }

    @Test
    void terraformSignalProducesTerraformFacet() {
        var s = new ScanSignals();
        s.hasTerraformFiles = true;
        var profile = detector.detect(s);
        assertThat(profile.terraform()).isTrue();
        assertThat(profile.facets()).containsExactly("terraform-deployment");
    }

    @Test
    void dltPythonSourceProducesDltFacet() {
        var s = new ScanSignals();
        s.hasDltSource = true;
        s.hasPythonSource = true;
        var profile = detector.detect(s);
        assertThat(profile.dlt()).isTrue();
        assertThat(profile.facets()).contains("dlt-pipeline");
    }

    @Test
    void dltSqlSourceAloneAlsoProducesDltFacet() {
        var s = new ScanSignals();
        s.hasDltSqlSource = true;
        s.hasSqlSource = true;
        var profile = detector.detect(s);
        assertThat(profile.dlt()).isTrue();
    }

    @Test
    void pythonAndSqlSignalsProduceTheirFacets() {
        var s = new ScanSignals();
        s.hasPythonSource = true;
        s.hasSqlSource = true;
        var profile = detector.detect(s);
        assertThat(profile.facets()).containsExactly("python-source", "sql-source");
    }

    @Test
    void typicalReconPipelineComposesAllFourFacetsInOrder() {
        var s = new ScanSignals();
        s.hasTerraformFiles = true;
        s.hasDatabricksProvider = true;
        s.hasDltSource = true;
        s.hasPythonSource = true;
        s.hasSqlSource = true;
        var profile = detector.detect(s);
        assertThat(profile.facets())
            .containsExactly("terraform-deployment", "dlt-pipeline",
                "python-source", "sql-source");
    }
}
