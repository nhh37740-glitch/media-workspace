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

import hudson.model.Node
import hudson.slaves.DumbSlave
import hudson.slaves.EnvironmentVariablesNodeProperty
import hudson.slaves.JNLPLauncher
import hudson.slaves.RetentionStrategy
import jenkins.model.Jenkins

def instance = Jenkins.get()

def agentName = System.getenv('JENKINS_AGENT_NAME') ?: 'media-workspace-agent'
def agentDir = System.getenv('JENKINS_AGENT_DIR') ?: '/opt/jenkins-agent'
def agentSecretFile = System.getenv('JENKINS_AGENT_SECRET_FILE') ?: '/opt/jenkins/agent.secret'

// The launcher is built before the node because the transport is selected on the launcher, and it
// has to be applied to the node whether that node is created here or already exists.
//
// JNLPLauncher(boolean) does NOT select the transport. Its argument decides whether the remoting
// work directory is enabled - it picks RemotingWorkDirSettings.getEnabledDefaults() or
// getDisabledDefaults() - and the webSocket field it leaves behind defaults to false. A node built
// with new JNLPLauncher(true) therefore speaks the TCP agent protocol, while the controller, whose
// slaveAgentPort is -1, has no TCP agent port to speak it to. The agent then retries forever and
// the node stays offline whatever the unit does.
def launcher = new JNLPLauncher(true) // enable the remoting work directory
launcher.setWebSocket(true)           // carry the connection over the controller's HTTP port

def node = instance.getNode(agentName)
if (node == null) {
    node = new DumbSlave(agentName, agentDir, launcher)
    instance.addNode(node)
    println("[init] created the inbound agent '${agentName}' with workspace ${agentDir}")
} else {
    // Re-applied rather than skipped: the settings the three-argument constructor carries are not
    // rewritten for a node that already exists, so a node registered by an earlier revision of this
    // script keeps its old launcher and the repair written here never reaches it.
    node.setLauncher(launcher)
    println("[init] the inbound agent '${agentName}' already exists; its configuration was re-applied")
}

node.setNumExecutors(1)
node.setLabelString('media-workspace-agent')
node.setMode(Node.Mode.NORMAL)
node.setRetentionStrategy(RetentionStrategy.INSTANCE)

// The toolchain the build needs, exported into every build this agent runs, so the Jenkinsfile does
// not have to guess where Java and the runtime configuration live. replace() overwrites the entry
// of the same type instead of stacking a second copy next to it.
def environment = new EnvironmentVariablesNodeProperty([
        new EnvironmentVariablesNodeProperty.Entry('JAVA_HOME', '/usr/lib/jvm/java-17-openjdk-amd64'),
        new EnvironmentVariablesNodeProperty.Entry('MW_ENV_FILE',
                '/opt/media-workspace/config/media-workspace.env'),
        new EnvironmentVariablesNodeProperty.Entry('DEPLOY_ROOT', '/opt/media-workspace')
])
node.getNodeProperties().replace(environment)

instance.save()

// Written for the agent's own unit to read. Never printed.
def secret = node.getComputer().getJnlpMac()
def secretFile = new File(agentSecretFile)
secretFile.getParentFile().mkdirs()
secretFile.text = secret
secretFile.setReadable(false, false)
secretFile.setReadable(true, true)

println("[init] the inbound agent '${agentName}' is configured for the WebSocket transport")
