# Multi-module project — mvnw lives at the repo root (PROJECT_DIR := .)
PROJECT_DIR         := ether-brain
PROJECT_GROUP_ID    := dev.rafex.ether.brain
PROJECT_ARTIFACT_ID := ether-brain-parent
# Multi-module pom packaging: no separate source:jar / javadoc:jar at root level
PRE_DEPLOY_GOALS    :=
# Exclude test-only module from the deploy reactor so it is never staged to Central
DEPLOY_EXTRA_ARGS   := -pl !ether-brain-architecture-tests

# Include shared build logic
include ../build-helpers/common.mk
include ../build-helpers/git.mk
