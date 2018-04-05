#!groovy

def jdks = ["jdk8", "jdk9","jdk10"]
def oss = ["linux"] //windows?  ,"linux-docker"
def builds = [:]
for (def os in oss) {
  for (def jdk in jdks) {
    builds[os+"_"+jdk] = getFullBuild( jdk, os )
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

      try
      {
        stage("Checkout - ${jdk}") {
          checkout scm
        }
      } catch (Exception e) {
        notifyBuild("Checkout Failure", jdk)
        throw e
      }

      try
      {
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
                //
                sh "mvn -V -B clean install -Dmaven.test.failure.ignore=true"
              }
              // withMaven doesn't label..
              // Report failures in the jenkins UI
              junit testResults:'**/target/surefire-reports/TEST-*.xml'
              // Collect up the jacoco execution results
              def jacocoExcludes =
                      // build tools
                      "**/org/eclipse/jetty/ant/**" + ",**/org/eclipse/jetty/maven/**" +
                              ",**/org/eclipse/jetty/jspc/**" +
                              // example code / documentation
                              ",**/org/eclipse/jetty/embedded/**" + ",**/org/eclipse/jetty/asyncrest/**" +
                              ",**/org/eclipse/jetty/demo/**" +
                              // special environments / late integrations
                              ",**/org/eclipse/jetty/gcloud/**" + ",**/org/eclipse/jetty/infinispan/**" +
                              ",**/org/eclipse/jetty/osgi/**" + ",**/org/eclipse/jetty/spring/**" +
                              ",**/org/eclipse/jetty/http/spi/**" +
                              // test classes
                              ",**/org/eclipse/jetty/tests/**" + ",**/org/eclipse/jetty/test/**";
              step( [$class          : 'JacocoPublisher',
                     inclusionPattern: '**/org/eclipse/jetty/**/*.class',
                     exclusionPattern: jacocoExcludes,
                     execPattern     : '**/target/jacoco.exec',
                     classPattern    : '**/target/classes',
                     sourcePattern   : '**/src/main/java'] )
              // Report on Maven and Javadoc warnings
              step( [$class        : 'WarningsPublisher',
                     consoleParsers: [[parserName: 'Maven'],
                                      [parserName: 'JavaDoc'],
                                      [parserName: 'JavaC']]] )
            }
            if(isUnstable())
            {
              notifyBuild("Unstable / Test Errors", jdk)
            }
          }
        }
      } catch(Exception e) {
        notifyBuild("Test Failure", jdk)
        throw e
      }

    }
  }
}


// True if this build is part of the "active" branches
// for Jetty.
def isActiveBranch()
{
  def branchName = "${env.BRANCH_NAME}"
  return ( branchName == "master" ||
          ( branchName.startsWith("jetty-") && branchName.endsWith(".x") ) );
}

// Test if the Jenkins Pipeline or Step has marked the
// current build as unstable
def isUnstable()
{
  return currentBuild.result == "UNSTABLE"
}

// Send a notification about the build status
def notifyBuild(String buildStatus, String jdk)
{
  if ( !isActiveBranch() )
  {
    // don't send notifications on transient branches
    return
  }
  // currently nothing
  return;

}

// vim: et:ts=2:sw=2:ft=groovy
