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
import jenkins.model.Jenkins

def instance = Jenkins.get()

def adminUser = System.getenv('JENKINS_ADMIN_USER') ?: 'admin'
def adminPassword = System.getenv('JENKINS_ADMIN_PASSWORD')

if (!adminPassword) {
    // Leaving the realm unconfigured would expose an unauthenticated controller, so the instance is
    // put into a state that serves nothing useful until an operator supplies the value.
    println('[init] JENKINS_ADMIN_PASSWORD is not set; security is left unconfigured and the ' +
            'controller will refuse to serve the pipeline')
    def refused = new FullControlOnceLoggedInAuthorizationStrategy()
    refused.setAllowAnonymousRead(false)
    instance.setAuthorizationStrategy(refused)
    instance.save()
    return
}

// The realm is created when it is not already this kind. Comparing against the "no authentication"
// sentinel by name does not compile against every Jenkins version - the nested class has moved - so
// the check is made the other way round, on the realm that this script itself would have installed.
def realm = instance.getSecurityRealm()
if (!(realm instanceof HudsonPrivateSecurityRealm)) {
    realm = new HudsonPrivateSecurityRealm(false)
    instance.setSecurityRealm(realm)
}

// Anonymous read access is denied by setting allowAnonymousRead to false. The setter under the
// inverse name does not exist on Jenkins 2.568.3:
//
//   groovy.lang.MissingMethodException: No signature of method:
//   hudson.security.FullControlOnceLoggedInAuthorizationStrategy.setDenyAnonymousReadAccess()
//   is applicable for argument types: (java.lang.Boolean) values: [true]
//
// That call aborted this script at the line it ran on, which is above setNumExecutors, above
// setSlaveAgentPort and above save(). Every start therefore logged "Failed to run script" and left
// the controller on its default of two executors.
def strategy = new FullControlOnceLoggedInAuthorizationStrategy()
strategy.setAllowAnonymousRead(false)
instance.setAuthorizationStrategy(strategy)

// Zero executors: the controller schedules, it does not build. A build running here would compete
// with the controller's own work and, on this host, with the application itself.
instance.setNumExecutors(0)
// No TCP agent port: the agent reaches the controller over the HTTP port using WebSocket.
instance.setSlaveAgentPort(-1)

// Written before anything below it runs, so a failure in a later section cannot leave the
// controller without the security configuration above.
instance.save()

// The credential is reconciled with the environment on every start, not only when the realm is
// first created. createAccount() resolves the account with User.getById(name, true) and then
// replaces that user's Details property, so it writes the password it is given whether or not the
// account already exists. Calling it only on first creation leaves an account written by an earlier
// start holding a hash that no longer matches the value in the environment file, and the operator
// who can read that file is rejected with 401.
def matches = false
if (realm.getUser(adminUser) != null) {
    try {
        matches = realm.load(adminUser).isPasswordCorrect(adminPassword)
    } catch (Throwable failure) {
        matches = false
    }
}
if (matches) {
    println("[init] the administrator account '${adminUser}' already matches the environment")
} else {
    realm.createAccount(adminUser, adminPassword)
    println("[init] wrote the administrator credential for '${adminUser}' from the environment")
}

instance.save()

// The first-run setup wizard is stopped from running again, because it installs an administrator
// account of its own that shadows the one above.
//
// That is what happened here. In $JENKINS_HOME, jenkins.install.UpgradeWizard.state was absent, and
// InstallUtil.getDefaultInstallState() returns INITIAL_SECURITY_SETUP whenever that file is missing.
// So a start without a config.xml did not stop at an unsecured controller: INITIAL_SECURITY_SETUP
// ran SetupWizard.init(true), which installed its own HudsonPrivateSecurityRealm, created 'admin'
// with a random UUID password, wrote that password to secrets/initialAdminPassword, and saved a
// config.xml. This script then ran, found the realm already present and left the account alone, so
// the password in the environment file never reached the account and every login was rejected with
// 401. secrets/initialAdminPassword, dated at the moment of that start, is the record of it.
//
// Writing the file is what the official Docker image does for the same reason (`echo 2.0 >
// .../jenkins.install.UpgradeWizard.state`). It is written only when absent, so a later upgrade of
// the war still reaches the upgrade path instead of being forced to look like a restart.
try {
    def setupStateFile = new File(instance.getRootDir(), 'jenkins.install.UpgradeWizard.state')
    if (!setupStateFile.exists()) {
        setupStateFile.text = instance.getVersion().toString() + System.lineSeparator()
        jenkins.install.InstallUtil.saveLastExecVersion()
        println('[init] recorded first-time setup as complete; the setup wizard cannot recreate the ' +
                'administrator account on a later start')
    }
} catch (Throwable failure) {
    println("[init] could not record the setup state: ${failure.message}")
}

// There is deliberately no call to AdminWhitelistRule#setMasterKillSwitch here any more.
//
// The method still exists on 2.568.3, and calling it produces a stack trace that reads like a
// failure, but nothing is thrown. Disassembled with `javap -c`, its entire body is a logging call:
//
//   LOGGER.log(state ? Level.WARNING : Level.INFO,
//       "Setting AdminWhitelistRule no longer has any effect. See
//        https://www.jenkins.io/redirect/AdminWhitelistRule to learn more.",
//       new Exception())
//
// The Exception is constructed only to be attached to the log record, which is why the journal shows
// a bare `java.lang.Exception` at AdminWhitelistRule.java:34 with a stack ending at this script. It
// never propagates, so it is not what aborted this script in the past: that was the
// MissingMethodException documented above. The call is a no-op that adds an INFO line and a
// misleading stack trace to every start, so it is gone.
//
// The agent-to-controller rules the old switch used to disable per build are configured per command
// in $JENKINS_HOME/jenkins.security.s2m.ConfigFile; the inbound agent needs no entry there for the
// WebSocket transport, because it opens the connection itself.

println('[init] security configured: authenticated access only, 0 executors on the controller')
