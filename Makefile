# Multi-module project — mvnw lives at the repo root (PROJECT_DIR := .)
PROJECT_DIR         := .
PROJECT_GROUP_ID    := dev.rafex.etherbrain
PROJECT_ARTIFACT_ID := ether-brain-parent
# Multi-module pom packaging: no separate source:jar / javadoc:jar at root level
PRE_DEPLOY_GOALS    :=

# Include shared build logic
include ../build-helpers/common.mk
include ../build-helpers/git.mk
