// Housekeeping that keeps the controller inside its memory budget.
//
// The controller shares a 2 GiB host with the application, MySQL and a broker, so its own footprint
// is a constraint rather than an afterthought. Build history is the main thing that grows without
// bound, and the pipeline keeps what an investigation needs (thirty runs) while dropping the rest.
//
// The agent's workspace is deliberately not configured here: the Jenkinsfile's cleanup block removes
// it after every build, and doing it in two places would make a build that failed mid-checkout
// harder to diagnose.

import jenkins.model.Jenkins

def instance = Jenkins.get()

// Keep the controller's own log bounded: it is written continuously and is not the business log.
instance.setLogRotator(new hudson.tasks.LogRotator(7, 30, -1, -1))
instance.save()

println('[init] log rotation configured: 7 days or 30 builds per job')
