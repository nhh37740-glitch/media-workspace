#!/usr/bin/env bash
#
# Installs and configures the Jenkins controller and its inbound agent.
#
# The controller is bound to loopback and runs with zero executors; the agent connects out to it and
# owns the workspace, the JDK and the Node installation the build needs. Both run on this host but
# with separate work directories and separate JVM options, because the delivery contract requires
# the controller and the agent to be separately configured even when they share a machine.
#
# Idempotent: re-running leaves an existing installation alone, except that the plugin list is
# re-applied, which is how a pipeline that needs one more plugin gets it.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/media-workspace}"
ENV_FILE="${MW_ENV_FILE:-$DEPLOY_ROOT/config/media-workspace.env}"
JENKINS_HOME_DIR="${JENKINS_HOME_DIR:-/opt/jenkins}"
AGENT_DIR="${JENKINS_AGENT_DIR:-/opt/jenkins-agent}"
JENKINS_URL="${JENKINS_URL:-http://127.0.0.1:8081}"
JENKINS_WAR_VERSION="${JENKINS_WAR_VERSION:-2.568.3}"
JENKINS_MIRROR="${JENKINS_MIRROR:-https://mirrors.huaweicloud.com/jenkins}"

log() { printf '[jenkins] %s\n' "$*"; }
fail() { printf '[jenkins] ERROR: %s\n' "$*" >&2; exit 1; }

require_root() {
  [ "$(id -u)" -eq 0 ] || fail "run with sudo"
}

require_root
[ -r "$ENV_FILE" ] || fail "cannot read $ENV_FILE; run scripts/provision-host.sh first"

# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a

# The administrator identity is generated once and kept in the environment file with the other
# secrets. Check both keys independently: older environment files may already contain a password
# but not the user key, which would make the CLI authenticate as an empty user and return 401.
if ! grep -qE '^JENKINS_ADMIN_USER=[^[:space:]]+$' "$ENV_FILE"; then
  printf 'JENKINS_ADMIN_USER=admin\n' >> "$ENV_FILE"
fi
if ! grep -qE '^JENKINS_ADMIN_PASSWORD=[^[:space:]]+$' "$ENV_FILE"; then
  printf 'JENKINS_ADMIN_PASSWORD=%s\n' "$(openssl rand -hex 20)" >> "$ENV_FILE"
  chmod 0640 "$ENV_FILE"
  chgrp ubuntu "$ENV_FILE" 2>/dev/null || true
  log "generated the Jenkins administrator password into the environment file"
fi
# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a
: "${JENKINS_ADMIN_USER:?JENKINS_ADMIN_USER must be present in the environment file}"
: "${JENKINS_ADMIN_PASSWORD:?JENKINS_ADMIN_PASSWORD must be present in the environment file}"

# Jenkins CLI accepts an @file credential source. The file must contain only the raw
# user:password pair; curl's `user = "..."` configuration syntax is not valid here. Keeping the
# secret in a mode-600 temporary file avoids exposing it in the process list or build log.
JENKINS_CLI_AUTH_FILE="$(mktemp)"
chmod 0600 "$JENKINS_CLI_AUTH_FILE"
printf '%s:%s\n' "$JENKINS_ADMIN_USER" "$JENKINS_ADMIN_PASSWORD" > "$JENKINS_CLI_AUTH_FILE"
cleanup() { rm -f "$JENKINS_CLI_AUTH_FILE"; }
trap cleanup EXIT

install_controller() {
  log "installing the controller"
  # The two tmp directories are the JVM temporary directories of the controller and of the agent,
  # set in their units with -Djava.io.tmpdir. They are created here so the units never point at a
  # path that does not exist, and they are on the root filesystem rather than on /tmp, which is a
  # 981 MiB tmpfs and therefore below the 1 GiB threshold at which Jenkins' DiskSpaceMonitor marks a
  # node offline.
  install -d -o ubuntu -g ubuntu "$JENKINS_HOME_DIR" "$AGENT_DIR" "$JENKINS_HOME_DIR/tmp" "$AGENT_DIR/tmp"

  if [ ! -f "$JENKINS_HOME_DIR/jenkins.war" ]; then
    curl -sSL --retry 3 -o /tmp/jenkins.war \
      "${JENKINS_MIRROR}/war-stable/${JENKINS_WAR_VERSION}/jenkins.war" \
      || curl -sSL --retry 3 -o /tmp/jenkins.war \
         "https://get.jenkins.io/war-stable/${JENKINS_WAR_VERSION}/jenkins.war"
    install -o ubuntu -g ubuntu -m 0644 /tmp/jenkins.war "$JENKINS_HOME_DIR/jenkins.war"
    rm -f /tmp/jenkins.war
  fi

  # The init scripts are copied into the controller's home. They configure security, the agent and
  # the job, so a rebuilt controller comes up in the same state without anyone clicking through a
  # wizard whose result is not in the repository.
  #
  # The directory is emptied first: the copy would otherwise leave a script that has been removed
  # from the repository behind, and it would keep running on every start. One did, and its failure
  # appeared in the log long after the file had been deleted from the sources.
  install -d -o ubuntu -g ubuntu "$JENKINS_HOME_DIR/init.groovy.d"
  rm -f "$JENKINS_HOME_DIR"/init.groovy.d/*.groovy
  install -o ubuntu -g ubuntu -m 0644 "$REPO_ROOT"/deploy/jenkins/init.groovy.d/*.groovy \
    "$JENKINS_HOME_DIR/init.groovy.d/"
  install -o root -g root -m 0644 "$REPO_ROOT/deploy/jenkins/jenkins.service" \
    /etc/systemd/system/media-jenkins.service
  install -o root -g root -m 0644 "$REPO_ROOT/deploy/jenkins/jenkins-agent.service" \
    /etc/systemd/system/media-jenkins-agent.service

  systemctl daemon-reload
  systemctl enable media-jenkins >/dev/null 2>&1 || true
}

wait_for_controller() {
  log "waiting for the controller"
  for _ in $(seq 1 90); do
    if curl -sf -o /dev/null "$JENKINS_URL/login" 2>/dev/null; then
      log "the controller is answering"
      return 0
    fi
    sleep 2
  done
  journalctl -u media-jenkins -n 40 --no-pager >&2 || true
  fail "the controller did not start"
}

install_plugins() {
  log "installing the plugins the pipeline needs"
  local cli_jar=/tmp/jenkins-cli.jar
  curl -sSf -o "$cli_jar" "$JENKINS_URL/jnlpJars/jenkins-cli.jar" \
    || fail "could not fetch the CLI jar"

  # The plugin list is applied as a set; already-installed plugins are left at their current
  # version, so re-running does not silently upgrade a build dependency.
  local plugins
  plugins="$(grep -vE '^\s*(#|$)' "$REPO_ROOT/deploy/jenkins/plugins.txt" | tr '\n' ' ')"

  set +e
  java -jar "$cli_jar" -s "$JENKINS_URL" -http -auth "@$JENKINS_CLI_AUTH_FILE" \
    install-plugin $plugins -deploy 2>&1 | tail -5
  local status=$?
  set -e
  rm -f "$cli_jar"
  if [ "$status" -ne 0 ]; then
    fail "the plugin installation command failed"
  fi
}

wait_for_plugins() {
  log "waiting for plugins to finish loading"
  local cli_jar=/tmp/jenkins-cli.jar
  curl -sSf -o "$cli_jar" "$JENKINS_URL/jnlpJars/jenkins-cli.jar" \
    || fail "could not fetch the CLI jar while checking plugins"
  for _ in $(seq 1 60); do
    local output
    output="$(java -jar "$cli_jar" -s "$JENKINS_URL" -http \
      -auth "@$JENKINS_CLI_AUTH_FILE" list-plugins 2>/dev/null || true)"
    local missing=0 plugin
    for plugin in $(grep -vE '^\s*(#|$)' "$REPO_ROOT/deploy/jenkins/plugins.txt"); do
      if ! printf '%s' "$output" | grep -qE "^${plugin}([[:space:]]|$)"; then
        missing=1
        break
      fi
    done
    if [ "$missing" -eq 0 ]; then
      log "all required pipeline plugins are present"
      rm -f "$cli_jar"
      return 0
    fi
    sleep 5
  done
  rm -f "$cli_jar"
  fail "workflow-aggregator did not become available"
}

create_job() {
  log "creating the pipeline job"
  local cli_jar=/tmp/jenkins-cli.jar
  curl -sSf -o "$cli_jar" "$JENKINS_URL/jnlpJars/jenkins-cli.jar" || fail "could not fetch the CLI jar"

  # The job definition is generated from the repository path, so the job always points at the
  # checkout this script was run from.
  local job_xml
  job_xml="$(mktemp)"
  cat > "$job_xml" <<XML
<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job">
  <description>media-workspace 的构建、集成测试、打包与部署流水线。</description>
  <keepDependencies>false</keepDependencies>
  <properties>
    <hudson.model.ParametersDefinitionProperty>
      <parameterDefinitions>
        <hudson.model.StringParameterDefinition>
          <name>BASE_COMMIT</name><defaultValue>HEAD^</defaultValue>
          <description>基线提交，范围门禁的起点</description><trim>true</trim>
        </hudson.model.StringParameterDefinition>
        <hudson.model.StringParameterDefinition>
          <name>HEAD_COMMIT</name><defaultValue></defaultValue>
          <description>待验收提交；留空表示当前 checkout</description><trim>true</trim>
        </hudson.model.StringParameterDefinition>
        <hudson.model.ChoiceParameterDefinition>
          <name>CHANGE_MODULE</name>
          <choices class="java.util.Arrays\$ArrayList">
            <a class="string-array">
              <string>build-delivery</string><string>media-contracts</string>
              <string>media-domain</string><string>media-application</string>
              <string>adapter-persistence</string><string>adapter-messaging</string>
              <string>adapter-transcode</string><string>adapter-storage</string>
              <string>media-api</string><string>media-worker</string><string>web</string>
            </a>
          </choices>
          <description>本次变更所属的唯一责任模块</description>
        </hudson.model.ChoiceParameterDefinition>
        <hudson.model.BooleanParameterDefinition>
          <name>RUN_INTEGRATION</name><defaultValue>true</defaultValue>
          <description>运行真实 MySQL/Kafka/FFmpeg 集成测试</description>
        </hudson.model.BooleanParameterDefinition>
        <hudson.model.BooleanParameterDefinition>
          <name>DEPLOY_DEMO</name><defaultValue>false</defaultValue>
          <description>构建通过后部署演示环境并冒烟</description>
        </hudson.model.BooleanParameterDefinition>
        <hudson.model.StringParameterDefinition>
          <name>RELEASE_VERSION</name><defaultValue></defaultValue>
          <description>发布版本号；留空表示不发布</description><trim>true</trim>
        </hudson.model.StringParameterDefinition>
      </parameterDefinitions>
    </hudson.model.ParametersDefinitionProperty>
    <org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty/>
  </properties>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition" plugin="workflow-cps">
    <scm class="hudson.plugins.git.GitSCM" plugin="git">
      <configVersion>2</configVersion>
      <userRemoteConfigs>
        <hudson.plugins.git.UserRemoteConfig>
          <url>${REPO_ROOT}</url>
        </hudson.plugins.git.UserRemoteConfig>
      </userRemoteConfigs>
      <branches>
        <hudson.plugins.git.BranchSpec><name>*/main</name></hudson.plugins.git.BranchSpec>
      </branches>
      <doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations>
    </scm>
    <scriptPath>Jenkinsfile</scriptPath>
    <lightweight>false</lightweight>
  </definition>
  <triggers/>
  <disabled>false</disabled>
</flow-definition>
XML

  local create_output
  if create_output="$(java -jar "$cli_jar" -s "$JENKINS_URL" -http \
      -auth "@$JENKINS_CLI_AUTH_FILE" create-job media-workspace < "$job_xml" 2>&1)"; then
    printf '%s\n' "$create_output"
  else
    local create_status=$?
    # A failed create is only recoverable when the job already exists. Check that explicitly before
    # trying update-job; otherwise the original create error is hidden by a misleading "No such job"
    # from the fallback command.
    if java -jar "$cli_jar" -s "$JENKINS_URL" -http \
        -auth "@$JENKINS_CLI_AUTH_FILE" get-job media-workspace >/dev/null 2>&1; then
      java -jar "$cli_jar" -s "$JENKINS_URL" -http \
        -auth "@$JENKINS_CLI_AUTH_FILE" update-job media-workspace < "$job_xml"
    else
      printf '%s\n' "$create_output" >&2
      rm -f "$cli_jar" "$job_xml"
      fail "could not create the pipeline job (create-job exit ${create_status})"
    fi
  fi
  rm -f "$cli_jar" "$job_xml"
  log "the pipeline job 'media-workspace' exists"
}

start_agent() {
  log "starting the build agent"
  install -d -o ubuntu -g ubuntu "$AGENT_DIR"
  # The remoting jar is served by the controller, so the agent always speaks the protocol version
  # the controller expects instead of one pinned in this repository that drifts as Jenkins updates.
  if [ ! -s "$AGENT_DIR/agent.jar" ]; then
    curl -sSf -o "$AGENT_DIR/agent.jar" "$JENKINS_URL/jnlpJars/agent.jar" \
      || fail "could not fetch the agent jar"
    chown ubuntu:ubuntu "$AGENT_DIR/agent.jar"
  fi
  systemctl enable media-jenkins-agent >/dev/null 2>&1 || true
  systemctl restart media-jenkins-agent
  for _ in $(seq 1 60); do
    local cli_jar=/tmp/jenkins-cli.jar
    curl -sSf -o "$cli_jar" "$JENKINS_URL/jnlpJars/jenkins-cli.jar" \
      || fail "could not fetch the CLI jar while checking the agent"
    # wait-node-online is a core CLI command and returns success only after the Computer is online.
    # A node name in get-node/list output only proves that its configuration exists, not that the
    # WebSocket agent has connected and can accept a build.
    if timeout 5s java -jar "$cli_jar" -s "$JENKINS_URL" -http \
      -auth "@$JENKINS_CLI_AUTH_FILE" wait-node-online media-workspace-agent >/dev/null 2>&1; then
      rm -f "$cli_jar"
      log "the agent is online"
      return 0
    fi
    rm -f "$cli_jar"
    sleep 3
  done
  fail "the agent did not report online; check journalctl -u media-jenkins-agent"
}

# The account no longer has to be deleted to be repaired. 01-security.groovy reconciles the stored
# password with JENKINS_ADMIN_PASSWORD on every start, because createAccount() resolves the account
# with User.getById(name, true) and then replaces its Details property, so it writes the credential
# it is given whether or not the account already exists. A restart alone corrects a stale hash.
#
# RESET_ADMIN_ACCOUNT=1 is kept for the case where the realm configuration itself has to be built
# from nothing: it removes users/ and config.xml so the next start constructs both from the
# environment file. It is no longer needed to repair a password.
if [ "${RESET_ADMIN_ACCOUNT:-0}" = "1" ]; then
  log "resetting the administrator account from the environment file"
  systemctl stop media-jenkins || true
  rm -rf "$JENKINS_HOME_DIR/users"
  # config.xml holds the security realm and the authorization strategy; the node and job definitions
  # live in their own directories and are not affected.
  rm -f "$JENKINS_HOME_DIR/config.xml"
fi

# Three starts, each with a reason:
#   1. the first applies the init scripts, which create the administrator account - and the plugin
#      installation needs that account to authenticate;
#   2. the second loads the plugins just installed, because a plugin's own extensions only come up
#      on a fresh start;
#   3. nothing else is restarted afterwards, so the job and agent work against the running instance.
install_controller
systemctl restart media-jenkins
wait_for_controller
install_plugins
systemctl restart media-jenkins
wait_for_controller
wait_for_plugins
create_job
start_agent

log "Jenkins is ready at $JENKINS_URL (loopback only)"
log "reach it with: ssh -L 8081:127.0.0.1:8081 -i codex.pem ubuntu@<host>"
log "the administrator password is in $ENV_FILE as JENKINS_ADMIN_PASSWORD"
