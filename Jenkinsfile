#!groovy

def oss = ["linux"]
def jdks = ["jdk8", "jdk11", "jdk13"]

def builds = [:]
for (def os in oss) {
  for (def jdk in jdks) {
    builds[os + "_" + jdk] = getFullBuild(os, jdk)
  }
}

parallel builds

def getFullBuild(os, jdk) {
  return {
    node(os) {
      def mvnName = 'maven3.5'
      def settingsName = 'oss-settings.xml'
      def mvnOpts = '-Xms1g -Xmx1g -Djava.awt.headless=true'

      stage("Checkout - ${jdk}") {
        checkout scm
      }

      stage("Build - ${jdk}") {
        timeout(time: 20, unit: 'MINUTES') {
          withMaven(maven: mvnName,
                  jdk: jdk,
                  publisherStrategy: 'EXPLICIT',
                  globalMavenSettingsConfig: settingsName,
                  mavenOpts: mvnOpts) {
            sh "mvn -V -B clean install -Dmaven.test.failure.ignore=true -e"
          }

          junit testResults: '**/target/surefire-reports/TEST-*.xml'
          // Collect the JaCoCo execution results.
          jacoco inclusionPattern: '**/org/eclipse/jetty/reactive/**/*.class',
                  execPattern: '**/target/jacoco.exec',
                  classPattern: '**/target/classes',
                  sourcePattern: '**/src/main/java'
        }
      }

      stage("Javadoc - ${jdk}") {
        timeout(time: 5, unit: 'MINUTES') {
          withMaven(maven: mvnName,
                  jdk: jdk,
                  publisherStrategy: 'EXPLICIT',
                  globalMavenSettingsConfig: settingsName,
                  mavenOpts: mvnOpts) {
            sh "mvn -V -B javadoc:javadoc -e"
          }
        }
      }
    }
  }
}
