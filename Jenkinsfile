#!groovy

def jdks = ["jdk8", "jdk9", "jdk10"]
def oss = ["linux"]
def builds = [:]
for (def os in oss) {
  for (def jdk in jdks) {
    builds[os + "_" + jdk] = getFullBuild(jdk, os)
  }
}

parallel builds

def getFullBuild(jdk, os) {
  return {
    node(os) {
      // System Dependent Locations
      def mvntool = tool name: 'maven3.5', type: 'hudson.tasks.Maven$MavenInstallation'
      def jdktool = tool name: "$jdk", type: 'hudson.model.JDK'

      // Environment
      List mvnEnv = ["PATH+MVN=${mvntool}/bin", "PATH+JDK=${jdktool}/bin", "JAVA_HOME=${jdktool}/", "MAVEN_HOME=${mvntool}"]
      mvnEnv.add("MAVEN_OPTS=-Xms256m -Xmx1024m -Djava.awt.headless=true")

      try {
        stage("Checkout - ${jdk}") {
          checkout scm
        }
      } catch (Throwable e) {
        notifyBuild("Checkout Failure", jdk)
        throw e
      }

      try
      {
        stage("Javadoc - ${jdk}") {
          withEnv(mvnEnv) {
            timeout(time: 20, unit: 'MINUTES') {
              withMaven(
                      maven: 'maven3',
                      jdk: "$jdk",
                      publisherStrategy: 'EXPLICIT',
                      globalMavenSettingsConfig: 'oss-settings.xml',
                      mavenLocalRepo: "${env.JENKINS_HOME}/${env.EXECUTOR_NUMBER}") {
                sh "mvn -V -B javadoc:javadoc"
              }
            }
          }
        }
      } catch(Throwable e) {
        notifyBuild("Javadoc Failure", jdk)
        throw e
      }

      try {
        stage("Test - ${jdk}") {
          withEnv(mvnEnv) {
            timeout(time: 90, unit: 'MINUTES') {
              // Run test phase / ignore test failures
              withMaven(
                      maven: 'maven3',
                      jdk: "$jdk",
                      publisherStrategy: 'EXPLICIT',
                      //options: [invokerPublisher(disabled: false)],
                      globalMavenSettingsConfig: 'oss-settings.xml',
                      mavenLocalRepo: "${env.JENKINS_HOME}/${env.EXECUTOR_NUMBER}") {
                sh "mvn -V -B clean install -Dmaven.test.failure.ignore=true"
              }
              // Report failures in the jenkins UI.
              junit testResults: '**/target/surefire-reports/TEST-*.xml'
              // Collect up the jacoco execution results
              step([$class          : 'JacocoPublisher',
                    inclusionPattern: '**/org/eclipse/jetty/reactive/**/*.class',
                    execPattern     : '**/target/jacoco.exec',
                    classPattern    : '**/target/classes',
                    sourcePattern   : '**/src/main/java'])
              // Report on Maven and Javadoc warnings.
              step([$class        : 'WarningsPublisher',
                    consoleParsers: [[parserName: 'Maven'],
                                     [parserName: 'JavaDoc'],
                                     [parserName: 'JavaC']]])
            }
            if (isUnstable()) {
              notifyBuild("Unstable / Test Errors", jdk)
            }
          }
        }
      } catch (Throwable e) {
        notifyBuild("Test Failure", jdk)
        throw e
      }
    }
  }
}

// Test if the Jenkins Pipeline or Step has marked the current build as unstable.
def isUnstable() {
  return currentBuild.result == "UNSTABLE"
}

// Send a notification about the build status.
def notifyBuild(String buildStatus, String jdk) {
  // Currently does nothing.
}
