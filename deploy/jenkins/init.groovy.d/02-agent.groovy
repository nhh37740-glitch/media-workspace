// The build agent definition.
//
// An inbound agent: it connects out to the controller, which means no listening port is opened on
// this host for builds and the controller never needs credentials to reach the agent. The agent's
// working directory is its own, and it runs the JDK and Node it needs for the build rather than
// borrowing the controller's.
//
// The node's secret is written to a file so the systemd unit for the agent can read it without the
// value appearing in a command line or in this script.

import hudson.model.Node
import hudson.slaves.EnvironmentVariablesNodeProperty
import hudson.slaves.JNLPLauncher
import hudson.slaves.NodeProperty
import hudson.slaves.RetentionStrategy
import jenkins.model.Jenkins
import jenkins.model.JenkinsLocationConfiguration

def instance = Jenkins.get()

def agentName = System.getenv('JENKINS_AGENT_NAME') ?: 'media-workspace-agent'
def agentDir = System.getenv('JENKINS_AGENT_DIR') ?: '/opt/jenkins-agent'
def agentSecretFile = System.getenv('JENKINS_AGENT_SECRET_FILE') ?: '/opt/jenkins/agent.secret'

def existing = instance.getNode(agentName)
if (existing != null) {
    println("[init] node '${agentName}' already exists")
    return
}

def node = new hudson.slaves.DumbSlave(
        agentName,
        agentDir,
        new JNLPLauncher(true),
        RetentionStrategy.INSTANCE,
        [new EnvironmentVariablesNodeProperty(
                new EnvironmentVariablesNodeProperty.Entry('JAVA_HOME', '/usr/lib/jvm/java-17-openjdk-amd64'),
                new EnvironmentVariablesNodeProperty.Entry('MW_ENV_FILE',
                        '/opt/media-workspace/config/media-workspace.env'),
                new EnvironmentVariablesNodeProperty.Entry('DEPLOY_ROOT', '/opt/media-workspace'))]
                as List<NodeProperty>)

// The pipeline targets this label, so a build only runs on a machine prepared for it.
node.setLabelString('media-workspace-agent')

instance.addNode(node)
instance.save()

// The secret is written for the agent's own unit to read; it is not printed.
def secret = node.getComputer().getJnlpMac()
new File(agentSecretFile).with {
    parentFile.mkdirs()
    text = secret
    setReadable(false, false)
    setReadable(true, true)
}
println("[init] created the inbound agent '${agentName}' with workspace ${agentDir}")
