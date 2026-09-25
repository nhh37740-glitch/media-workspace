// Security and executor configuration for the media-workspace controller.
//
// Applied at startup from JENKINS_HOME/init.groovy.d. Doing it here rather than through the setup
// wizard means the controller is never briefly reachable in its unsecured default state, and the
// configuration is a file in the repository rather than a series of clicks nobody can review.
//
// The admin password comes from the environment. If it is absent the controller refuses to start
// configured, rather than falling back to a well-known default.

import hudson.security.FullControlOnceLoggedInAuthorizationStrategy
import hudson.security.HudsonPrivateSecurityRealm
import hudson.security.SecurityRealm
import jenkins.model.Jenkins
import jenkins.security.s2m.AdminWhitelistRule

def instance = Jenkins.get()

def adminUser = System.getenv('JENKINS_ADMIN_USER') ?: 'admin'
def adminPassword = System.getenv('JENKINS_ADMIN_PASSWORD')

if (!adminPassword) {
    // Leaving the realm unconfigured would expose an unauthenticated controller, so the instance is
    // put into a state that serves nothing useful until an operator supplies the value.
    println('[init] JENKINS_ADMIN_PASSWORD is not set; security is left unconfigured and the ' +
            'controller will refuse to serve the pipeline')
    instance.setAuthorizationStrategy(
            new FullControlOnceLoggedInAuthorizationStrategy().with { it.denyAnonymousReadAccess = true; it })
    instance.save()
    return
}

def realm = new HudsonPrivateSecurityRealm(false)
if (instance.getSecurityRealm() == SecurityRealm.NO_AUTHENTICATION ||
        instance.getSecurityRealm() instanceof SecurityRealm.NoAuthentication) {
    realm.createAccount(adminUser, adminPassword)
    instance.setSecurityRealm(realm)
    println("[init] created the administrator account '${adminUser}'")
} else {
    println('[init] a security realm is already configured; leaving it in place')
}

def strategy = new FullControlOnceLoggedInAuthorizationStrategy()
strategy.setDenyAnonymousReadAccess(true)
instance.setAuthorizationStrategy(strategy)

// Zero executors: the controller schedules, it does not build. A build running here would compete
// with the controller's own work and, on this host, with the application itself.
instance.setNumExecutors(0)
instance.setSlaveAgentPort(-1) // an inbound agent connects to the HTTP port

instance.save()

// The agent connects with a token issued by the controller; without this the connection is refused.
def rule = instance.getExtensionList(AdminWhitelistRule.class).get(0)
rule.setMasterKillSwitch(false)

println('[init] security configured: authenticated access only, 0 executors on the controller')
