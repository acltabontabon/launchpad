package com.acltabontabon.launchpad.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TerraformExtractorTest {

    private final DependencyExtractor extractor = new DependencyExtractor();

    @Test
    void parsesRequiredProvidersBlock() {
        var tf = """
            terraform {
              required_version = ">= 1.6"
              required_providers {
                databricks = {
                  source  = "databricks/databricks"
                  version = "1.41.0"
                }
                aws = {
                  source  = "hashicorp/aws"
                  version = "5.31.0"
                }
              }
            }
            """;
        var deps = extractor.extract(Map.of("versions.tf", tf));
        assertThat(deps).extracting(Dependency::name)
            .contains("databricks/databricks", "hashicorp/aws");
        assertThat(deps).extracting(Dependency::scope)
            .allMatch("provider"::equals);
    }

    @Test
    void capturesVersionWhenPresent() {
        var tf = """
            terraform {
              required_providers {
                databricks = {
                  source  = "databricks/databricks"
                  version = "1.41.0"
                }
              }
            }
            """;
        var deps = extractor.extract(Map.of("versions.tf", tf));
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).version()).isEqualTo("1.41.0");
    }

    @Test
    void omittingVersionStillCapturesProvider() {
        var tf = """
            terraform {
              required_providers {
                databricks = {
                  source = "databricks/databricks"
                }
              }
            }
            """;
        var deps = extractor.extract(Map.of("versions.tf", tf));
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).name()).isEqualTo("databricks/databricks");
        assertThat(deps.get(0).version()).isEmpty();
    }

    @Test
    void tfFilesWithoutRequiredProvidersProduceNoDependencies() {
        var tf = """
            provider "aws" {
              region = "us-east-1"
            }
            resource "aws_s3_bucket" "example" {
              bucket = "my-bucket"
            }
            """;
        var deps = extractor.extract(Map.of("main.tf", tf));
        assertThat(deps).isEmpty();
    }
}
