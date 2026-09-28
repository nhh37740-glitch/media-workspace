// Declarative pipeline for media-workspace.
//
// Shape of the run, and why:
//
//   * The controller has zero executors. Every stage that compiles or runs tests executes on the
//     agent, so a build cannot occupy the controller that schedules it.
//   * The whole commit range is gated, not the last commit. A single-commit check would pass a
//     branch whose earlier commits crossed a module boundary.
//   * Nothing is deployed from a build that did not pass integration. RELEASE builds require
//     RUN_INTEGRATION, and the parameter cannot be used to skip a gate.
//   * Failures still archive. A red build that leaves no report is a red build nobody can act on.
//
// The parameters are deliberately explicit rather than derived: a rebuild of a past range must
// produce the same answer, which it would not if the range were recomputed at run time.

pipeline {
    agent { label 'media-workspace-agent' }

    parameters {
        string(name: 'BASE_COMMIT', defaultValue: 'HEAD^', description: '基线提交，范围门禁的起点')
        string(name: 'HEAD_COMMIT', defaultValue: '', description: '待验收提交；留空表示当前 checkout')
        choice(name: 'CHANGE_MODULE', choices: [
            'build-delivery', 'media-contracts', 'media-domain', 'media-application',
            'adapter-persistence', 'adapter-messaging', 'adapter-transcode', 'adapter-storage',
            'media-api', 'media-worker', 'web'
        ], description: '本次变更所属的唯一责任模块')
        booleanParam(name: 'RUN_INTEGRATION', defaultValue: true, description: '运行真实 MySQL/Kafka/FFmpeg 集成测试')
        booleanParam(name: 'DEPLOY_DEMO', defaultValue: false, description: '构建通过后部署演示环境并冒烟')
        string(name: 'RELEASE_VERSION', defaultValue: '', description: '发布版本号；留空表示不发布')
    }

    options {
        // A build that hangs must end: a wedged Gradle or a stuck encoder would otherwise hold the
        // single agent until somebody notices.
        timeout(time: 90, unit: 'MINUTES')
        // A branch pushes several commits while a build runs; only the newest is worth finishing.
        disableConcurrentBuilds(abortPrevious: true)
        buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '10'))
        timestamps()
        skipDefaultCheckout(true)
    }

    environment {
        JAVA_HOME = '/usr/lib/jvm/java-17-openjdk-amd64'
        MW_ENV_FILE = '/opt/media-workspace/config/media-workspace.env'
        DEPLOY_ROOT = '/opt/media-workspace'
        // A release build may not skip integration: the delivery contract makes the parameter
        // unable to weaken the gate.
        EFFECTIVE_INTEGRATION = "${params.RELEASE_VERSION?.trim() ? 'true' : params.RUN_INTEGRATION}"
    }

    stages {
        stage('Checkout') {
            steps {
                script {
                    def checkoutState = checkout scm
                    def requestedHead = params.HEAD_COMMIT?.toString()?.trim()
                    def head = ''
                    def isCommitId = { value ->
                        value != null && value.toString() ==~ /(?i)[0-9a-f]{7,64}/
                    }

                    // Some Jenkins agents do not populate GIT_COMMIT for this checkout. Use the
                    // checkout step's result when present, otherwise ask Git for the checked-out
                    // commit. Treat the literal string "null" like an omitted parameter.
                    if (requestedHead && !requestedHead.equalsIgnoreCase('null')) {
                        if (!isCommitId(requestedHead)) {
                            error("Invalid HEAD_COMMIT '${requestedHead}'; provide a commit SHA or leave it empty.")
                        }
                        head = requestedHead
                    } else {
                        def checkoutCommit = checkoutState instanceof Map ?
                            checkoutState.get('GIT_COMMIT')?.toString()?.trim() : null
                        if (isCommitId(checkoutCommit)) {
                            head = checkoutCommit
                        } else {
                            try {
                                head = sh(script: 'git rev-parse --verify HEAD^{commit}', returnStdout: true).trim()
                            } catch (Exception ignored) {
                                error('Unable to resolve the checked-out commit after checkout scm.')
                            }
                        }
                    }
                    sh "git fetch --no-tags --prune origin '+refs/heads/*:refs/remotes/origin/*' || true"
                    // The exact commit is resolved and recorded; every later stage quotes this value,
                    // so the artifact's provenance is a resolved object, not a branch name.
                    try {
                        env.HEAD_SHA = sh(script: "git rev-parse --verify ${head}^{commit}", returnStdout: true).trim()
                    } catch (Exception ignored) {
                        if (requestedHead && !requestedHead.equalsIgnoreCase('null')) {
                            error("HEAD_COMMIT '${requestedHead}' does not resolve to a commit in this checkout.")
                        }
                        error('Unable to resolve the checked-out commit to a Git commit object.')
                    }
                    // The release manifest reads GIT_COMMIT; the git plugin may leave it unset on
                    // this agent, so make the canonical checkout SHA explicit for provenance.
                    env.GIT_COMMIT = env.HEAD_SHA
                    env.BASE_SHA = sh(script: "git rev-parse --verify ${params.BASE_COMMIT}^{commit}", returnStdout: true).trim()
                    sh "git checkout --force ${env.HEAD_SHA}"
                    // A base that is not an ancestor means the range is not a reviewable change set.
                    sh "git merge-base --is-ancestor ${env.BASE_SHA} ${env.HEAD_SHA}"
                    echo "base=${env.BASE_SHA} head=${env.HEAD_SHA} module=${params.CHANGE_MODULE}"
                }
            }
        }

        stage('ScopeGate') {
            steps {
                // Fails the build immediately on a cross-module range, before anything is compiled.
                sh "python3 scripts/check_change_scope.py --repo . --base ${env.BASE_SHA} --head ${env.HEAD_SHA} --module ${params.CHANGE_MODULE}"
            }
        }

        stage('Validate') {
            steps {
                sh 'bash ./gradlew --no-daemon architectureCheck versionLockCheck'
                sh 'python3 scripts/validate-contracts.py'
            }
        }

        stage('Backend') {
            steps {
                // The test count is asserted, not assumed: a build that ran no tests is not a pass.
                sh 'bash ./gradlew --no-daemon clean check bootJar jar'
                sh 'python3 scripts/count-test-results.py --require-nonzero'
            }
            post {
                always {
                    junit(testResults: '**/build/test-results/test/*.xml', allowEmptyResults: true)
                }
            }
        }

        stage('Frontend') {
            steps {
                dir('web') {
                    sh 'npm ci --no-audit --no-fund'
                    sh 'npm test -- --run'
                    sh 'npm run build'
                }
            }
            post {
                always {
                    junit(testResults: 'web/test-results/**/*.xml', allowEmptyResults: true)
                }
            }
        }

        stage('Integration') {
            when { expression { env.EFFECTIVE_INTEGRATION == 'true' } }
            steps {
                // The demonstration services are stopped for the duration of the build: this host
                // does not hold two application JVMs, a broker, MySQL and a Gradle build at once.
                // Integration tests create their own schema per run, so stopping the services does
                // not affect them, and MySQL and Kafka stay up because they are the dependencies.
                script {
                    env.MEDIA_SERVICES_STOPPED_FOR_CI = 'true'
                    sh 'bash scripts/ci-prepare.sh'
                }
                sh 'bash scripts/run-integration-tests.sh'
            }
            post {
                always {
                    script {
                        if (env.MEDIA_SERVICES_STOPPED_FOR_CI == 'true'
                                && fileExists("${env.DEPLOY_ROOT}/current")) {
                            sh 'bash scripts/service.sh start all'
                            env.MEDIA_SERVICES_STOPPED_FOR_CI = 'false'
                        }
                    }
                    junit(testResults: '**/build/test-results/integrationTest/*.xml', allowEmptyResults: true)
                }
            }
        }

        stage('Package') {
            steps {
                // Backend and frontend were already built and tested. Assembly reuses those
                // artifacts so Jenkins does not run a second memory-heavy compile beside MySQL,
                // Kafka, and the restored demo services.
                sh 'SKIP_BACKEND_BUILD=1 WEB_DIST_DIR="$WORKSPACE/web/dist" bash scripts/build-release.sh'
                sh 'ls -la build/release/*.zip'
            }
        }

        stage('DeployDemo') {
            when {
                // Deployment needs both an explicit request and a release version, so an ordinary
                // branch build can never publish itself.
                expression { params.DEPLOY_DEMO && params.RELEASE_VERSION?.trim() }
            }
            steps {
                lock('media-workspace-demo-deployment') {
                    script {
                        def releaseDir = sh(
                            script: "find build/release -mindepth 1 -maxdepth 1 -type d -print -quit",
                            returnStdout: true
                        ).trim()
                        if (!releaseDir) {
                            error('Jenkins Package stage did not produce a release directory.')
                        }
                        env.MW_PREBUILT_RELEASE = "${pwd()}/${releaseDir}"
                        sh 'MW_PREBUILT_RELEASE="$MW_PREBUILT_RELEASE" bash scripts/deploy-demo.sh'
                    }
                }
            }
        }

        stage('Smoke') {
            when { expression { params.DEPLOY_DEMO && params.RELEASE_VERSION?.trim() } }
            steps {
                sh 'bash scripts/smoke-test.sh'
            }
        }
    }

    post {
        always {
            // Archives run before the workspace is cleaned, and they run for a failed build too: the
            // report of a failure is the most useful artifact it produces.
            archiveArtifacts(
                artifacts: 'build/release/*.zip, build/release/*/manifest.json, build/release/*/SHA256SUMS',
                allowEmptyArchive: true,
                fingerprint: true
            )
            archiveArtifacts(
                artifacts: '**/build/reports/tests/**/*.html, **/build/test-results/**/*.xml',
                allowEmptyArchive: true
            )
            junit(testResults: '**/build/test-results/**/*.xml', allowEmptyResults: true)
            echo "commit ${env.HEAD_SHA} module ${params.CHANGE_MODULE} result ${currentBuild.currentResult}"
        }
        cleanup {
            // The agent workspace is not shared between builds, and a stale build directory has
            // already caused one confusing result in this project.
            deleteDir()
        }
    }
}
