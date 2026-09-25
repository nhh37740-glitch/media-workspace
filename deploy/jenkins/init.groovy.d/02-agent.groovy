// The build agent definition.
//
// An inbound agent: it connects out to the controller, so no port is opened on this host for builds
// and the controller needs no credential for reaching the agent. Its working directory, JDK and Node
// installation are its own, separate from the controller's, as the delivery contract requires even
// when both processes share a machine.
//
// The node's secret is written to a file so the agent's systemd unit can read it without the value
// ever appearing in a command line or in a unit file. The file is readable only by the account the
// agent runs as.

import hudson.slaves.DumbSlave
import hudson.slaves.EnvironmentVariablesNodeProperty
import hudson.slaves.JNLPLauncher
import hudson.slaves.NodeProperty
import hudson.slaves.RetentionStrategy
import jenkins.model.Jenkins

def instance = Jenkins.get()

def agentName = System.getenv('JENKINS_AGENT_NAME') ?: 'media-workspace-agent'
def agentDir = System.getenv('JENKINS_AGENT_DIR') ?: '/opt/jenkins-agent'
def agentSecretFile = System.getenv('JENKINS_AGENT_SECRET_FILE') ?: '/opt/jenkins/agent.secret'

if (instance.getNode(agentName) != null) {
    println("[init] node '${agentName}' already exists")
    return
}

// The three-argument constructor is used and everything else set afterwards. The wider constructors
// have changed shape between Jenkins releases, and a plugin load order that differs from the one
// they were written against makes them fail to bind.
def node = new DumbSlave(agentName, agentDir, new JNLPLauncher(true))
node.setNumExecutors(1)
node.setLabelString('media-workspace-agent')
node.setMode(hudson.model.Node.Mode.NORMAL)
node.setRetentionStrategy(RetentionStrategy.INSTANCE)

// The toolchain the build needs, exported into every build this agent runs, so the Jenkinsfile does
// not have to guess where Java and the runtime configuration live.
def environment = new EnvironmentVariablesNodeProperty([
        new EnvironmentVariablesNodeProperty.Entry('JAVA_HOME', '/usr/lib/jvm/java-17-openjdk-amd64'),
        new EnvironmentVariablesNodeProperty.Entry('MW_ENV_FILE',
                '/opt/media-workspace/config/media-workspace.env'),
        new EnvironmentVariablesNodeProperty.Entry('DEPLOY_ROOT', '/opt/media-workspace')
])
node.getNodeProperties().add(environment as NodeProperty)

instance.addNode(node)
instance.save()

// Written for the agent's own unit to read. Never printed.
def secret = node.getComputer().getJnlpMac()
def secretFile = new File(agentSecretFile)
secretFile.getParentFile().mkdirs()
secretFile.text = secret
secretFile.setReadable(false, false)
secretFile.setReadable(true, true)

println("[init] created the inbound agent '${agentName}' with workspace ${agentDir}")
